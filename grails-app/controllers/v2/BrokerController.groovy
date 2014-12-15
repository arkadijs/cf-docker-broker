package v2

import com.datastax.driver.core.Cluster
import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.*
import groovy.sql.Sql
import groovy.text.SimpleTemplateEngine
import groovyx.gpars.actor.Actor
import static groovyx.gpars.actor.Actors.actor
import static groovyx.gpars.scheduler.Timer.timer
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import wslite.rest.ContentType
import wslite.http.HTTPClient
import wslite.rest.RESTClient

class BrokerController {
    def grailsApplication
    def grailsResourceLocator
    def log = org.apache.log4j.Logger.getLogger(getClass())

    final String serviceId = '017057ad-f188-41df-a75a-6a9ee46a976d'
    final String redisId = 'c209af0d-33dc-424b-8ec6-54a1d363734d'
    final String mysqlId = '7b387df6-3c61-4a4c-ae43-6f05c21e8912'
    final String mariaId = '45e953d3-1598-4b54-95c9-16427e5e7059'
    final String pgId    = 'f80271a3-0c58-4c23-a550-8828fcce63ad'
    final String mongoId = '1050698f-be8e-43ac-a3f2-a74eaec714de'
    final String cassId  = 'f6cc9ce6-ae3a-43cd-9ae0-ca45280bcc6e'
    final String oraId   = '1816c23f-6506-4704-959f-ee747ae6a06c'
    final String rmqId   = '302e9f58-106b-40c0-8bad-8a593bcc2243'
    final def plans = [
        (redisId): [ service: 'redis',      ports: [ [ port: 6379,  kind: 'api' ] ] ],
        (mysqlId): [ service: 'mysql',      ports: [ [ port: 3306,  kind: 'api' ] ] ],
        (mariaId): [ service: 'maria',      ports: [ [ port: 3306,  kind: 'api' ] ] ],
        (pgId):    [ service: 'postgresql', ports: [ [ port: 5432,  kind: 'api' ] ] ],
        (mongoId): [ service: 'mongodb',    ports: [ [ port: 27017, kind: 'api' ] ] ],
        (cassId):  [ service: 'cassandra',  ports: [ [ port: 9042,  kind: 'api' ] ] ],
        (oraId):   [ service: 'oracle',     ports: [ [ port: 1521,  kind: 'api' ], // [ port: 22, kind: 'ssh' ],
            [ port:  8080, kind: 'management', dashboard: 'http://$ip:$port/apex' ] ] ],
        (rmqId):   [ service: 'rabbitmq',   ports: [ [ port: 5672,  kind: 'api' ],
            // dashboard url stolen from https://github.com/nimbus-cloud/cf-rabbitmq-broker/blob/master/rabbitmq/service.go#L121
            [ port: 15672, kind: 'management', dashboard: 'http://$ip:$port/#/login/admin/$pass' ] ] ] ]

    final int tm = 10000 // ms
    String logo = 'data:image/png;base64,'
    // configuration
    String secret
    String ip
    // CoreOS specific vals
    boolean coreos   // coreos or docker
    boolean haproxy  // haproxy or GCE protocol forwarding
    String cloud     // gce or aws, for metadata retrieval
    boolean cloudGCE // simple boolean while only 2 clouds are supported
    RESTClient etcd  // ETCD is used to discover services location and track free ports for protocol forwarding
    final String etcdPrefix   = '/cf-docker-broker'
    final String etcdServices = etcdPrefix + '/services'
    final String etcdPorts    = etcdPrefix + '/ports'
    // GCE forwarder specific vals
    String project
    String region // we work at region level with a single IP dedicated to protocol forwarding
    Compute gce
    String gceUri = 'https://www.googleapis.com/compute/v1/projects/' // standard URI prefix for GCE resources

    String fleetctlFlags
    Thread discoverer // watches ETCD for published service endpoints
    Actor services    // (a) receives info from Discoverer to manipulate GCE protocol forwarding rules, (b) answers clients about service public endpoint
    Actor haproxyd    // manages haproxy config an process
    Actor portmap     // manages free ports for protocol forwarding, stores state in ETCD

    @Lazy volatile HTTPClient calmHttpClient = new HTTPClient().with { connectTimeout = readTimeout = tm*10; it }
    @Lazy volatile HTTPClient httpClient = new HTTPClient().with { connectTimeout = readTimeout = tm; it }
    @Lazy volatile RESTClient metadata = new RESTClient(
            cloudGCE ? 'http://metadata/computeMetadata/v1' : 'http://169.254.169.254/latest/meta-data', httpClient)

    def templateEngine = new SimpleTemplateEngine()

    @javax.annotation.PostConstruct
    void init() {
        def c = grailsApplication.config?.broker?.v2
        if (!c) throw new RuntimeException('`broker.v2.backend` must be set in Grails app config `grails-app/conf/Config.groovy`')
        secret = c.secret ?: 'f779df95-2190-4a0d-ad5b-9f2ba4550ea9'
        coreos = c.backend == 'coreos' ?: c.backend == 'docker' ? false : { throw new IllegalArgumentException('`broker.v2.backend` must be one of: docker, coreos') }()
        if (!['gce', 'aws', null].contains(c.cloud)) throw new IllegalArgumentException('`broker.v2.cloud` must be one of: gce, aws; or unset')
        cloud = c.cloud
        cloudGCE = cloud == 'gce'
        if (coreos) {
            log.info('Initializing CoreOS interface...')
            if (!c.coreoshost) throw new RuntimeException('`broker.v2.coreoshost` must be set if broker.v2.backend = coreos')
            haproxy = c.forwarder == 'haproxy' ?: c.forwarder == 'gce' ? false : { throw new IllegalArgumentException('`broker.v2.forwarder` must be one of: haproxy, gce') }()
            if (haproxy) {
                ip = myIp(c.publicip)
                def h = c.haproxy
                if (!h || [h.bin, h.conf, h.pid, h.stoponexit].any { it == null })
                    throw new IllegalArgumentException('`broker.v2.haproxy.*` must be setup when `forwarder` is `haproxy`')
                haproxyd = manageHaproxy(h)
            } else { // GCE protocol forwarding
                if (!cloudGCE) throw new IllegalArgumentException('`broker.v2.cloud` must be `gce` when `forwarder` is `gce`')
                if (!looksIp(c.publicip)) throw new IllegalArgumentException('`broker.v2.public` must be set to a reserved static IP address if broker.v2.forwarder = gce')
                ip = c.publicip
                region = c.region ?: metadataRegion()
                project = c.project ?: metadataProject()
                gceUri += project
                gce = initGCE()
            }
            fleetctlFlags = "--request-timeout=10 --endpoint=http://${c.coreoshost}:4001/"
            // so far Fleet has no REST API available, we'll use ETCD API and fleetctl
            etcd = new RESTClient("http://${c.coreoshost}:4001/v2/keys", httpClient)
            portmap = managePorts()
            services = watchServices()
            discoverer = Thread.startDaemon('ETCD Watcher') { watchEtcd() }
            log.info('CoreOS interface initialized')
        } else { // docker
            ip = myIp(c.publicip)
        }
        logo += grailsResourceLocator.findResourceForURI('/images/docker-whale-150x150.png').file.bytes.encodeBase64().toString()
    }

    @javax.annotation.PreDestroy
    void shutdown() {
        if (coreos) {
            log.info('Shutting down CoreOS interface...')
            if (discoverer) discoverer.with { interrupt(); join() }
            if (services)     services.with { stop(); join() }
            if (haproxyd)     haproxyd.with { stop(); join() }
            if (portmap)       portmap.with { stop(); join() }
            log.info('CoreOS interface down')
        }
    }

    private String str(byte[] bytes) { new String(bytes, 'UTF-8') }
    private String sanitize(String str) {
        String word = str.toLowerCase().replaceAll('[^a-z0-9]', '')
      //word.charAt(0).letter ? word : 'z'+word
        word
    }
    private String basename(String path) {
        path.with {
            (lastIndexOf('/') > 0 ? substring(lastIndexOf('/')+1) : it).with {
                indexOf('.') > 0 ? substring(0, indexOf('.')) : it
            }
        }
    }
    private String _metadata(String path) {
        // URL.getText() cannot set HTTP header
        // GCE metadata server returns application/text which wslite doesn't like for providing response.text
        str(metadata.get(path: path, headers: cloudGCE ? [ 'X-Google-Metadata-Request': 'true' ] : []).data)
    }
    private String metadataProject() { _metadata('/project/project-id') } // GCE only
    private String metadataRegion() { // GCE only
        basename(_metadata('/instance/zone')).with { // projects/77511713522/zones/europe-west1-b
            substring(0, lastIndexOf('-'))
        }
    }
    private def looksIp(String maybe) { maybe ==~ /\d+\.\d+\.\d+\.\d+/ ? maybe : false } // anchored match
    private String myIp(boolean pub) { pub ? publicIp() : localIp() }
    private String publicIp() {
        metadataPublicIp() ?: {
            try {
                'http://v4.ipv6-test.com/api/myip.php'.toURL()
                    .getText([ connectTimeout: tm, readTimeout: tm, allowUserInteraction: false ])
                    .with { looksIp(it) ?: localIp() }
            } catch (e) { localIp() }
        }()
    }
    private String metadataPublicIp() {
        if (cloud) looksIp(_metadata(cloudGCE ? '/instance/network-interfaces/0/access-configs/0/external-ip' : '/public-ipv4')) ?: null
        else null
    }
    private String localIp() { metadataLocalIp() ?: Inet4Address.localHost.hostAddress } // IPv4 is blossoming!
    private String metadataLocalIp() {
        if (cloud) looksIp(_metadata(cloudGCE ? '/instance/network-interfaces/0/ip' : '/local-ipv4')) ?: null
        else null
    }
    private long now() { System.currentTimeMillis() }

    private void watchEtcd() {
        long index = -1
        // TODO loop here until success
        // TODO reconcile with the list of Forwarding Rules in GCE (by IP)
        maybe404 { etcd.get(path: etcdServices) } .with { all ->
            if (all?.statusCode == 200) {
                all.json.node.nodes.each { s ->
                    this.services << [op: 'add', service: basename(s.key), location: s.value]
                    if (s.modifiedIndex > index) index = s.modifiedIndex
                }
            }
        }
        while (!Thread.interrupted()) {
            try {
                def change = null
                try {
                    change = etcd.get(path: etcdServices, query: [ wait: true, recursive: true] + (index > 0 ? [ waitIndex: index ] : [:]))
                } catch (wslite.rest.RESTClientException ex) {
                    if (ex.cause?.cause instanceof SocketTimeoutException) continue
                    if (ex.response?.statusCode == 400) { // TODO register ETCD issue - it should be 410 Gone
                        // handle 'outdated' error due to no changes under /services
                        //   HTTP/1.1 400 Bad Request
                        //   Content-Type: application/json
                        //   X-Etcd-Index: 60428
                        //   {"errorCode":401,"message":"The event in requested index is outdated and cleared",
                        //    "cause":"the requested history has been cleared [59429/1]","index":60428}
                        if (ex.response.json?.cause?.startsWith('the requested history has been cleared')) {
                            index = ex.response.headers?.'X-Etcd-Index'?.toInteger() ?: -1
                            continue
                        }
                    }
                    log.error('ETCD watch request completed with error', ex)
                    index = -1
                }
                if (change?.statusCode != 200) {
                    Thread.sleep(tm)
                    continue
                }
                def j = change.json
                if (!j.node.key.startsWith(etcdServices + '/')) continue
                index = (j.node.modifiedIndex as long) + 1
                // {"action":"set","node":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":913,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:5555","modifiedIndex":909,"createdIndex":909}}
                // {"action":"update","node":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":920,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":913,"createdIndex":913}}
                // {"action":"delete","node":{"key":"/services/redis","modifiedIndex":3087,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":920,"createdIndex":913}}
                String service = basename(j.node.key)
                switch (j.action) {
                    case 'set': case 'update':
                        services << [ op: 'add', service: service, location: j.node.value, etcdIndex: j.node.modifiedIndex as long ]
                        break
                    case 'delete': case 'expire':
                        if (j.action == 'expire') log.info("Service '$service' publication expired")
                        services << [ op: 'del', service: service, etcdIndex: j.node.modifiedIndex as long ]
                        break
                }
            } catch(InterruptedException i) {
                return
            } catch (e) {
                e.printStackTrace()
                try { Thread.sleep(tm) } catch(InterruptedException i) { return }
            }
        }
    }

    private def maybe404(Closure call) {
        try {
            call()
        } catch (com.google.api.client.http.HttpResponseException ex) {
            if (ex.statusCode != 404) throw ex
        } catch (wslite.rest.RESTClientException ex) { // java.net.URL throws FileNotFoundException
            if (ex.response?.statusCode != 404) throw ex
            return ex.response
        }
    }

    /* TODO document: On Google Compute Engine ...
    */
    private Actor watchServices() {
        def services = [:] // service -> [ address: public ip:port, etcdIndex: index ]
        def pending = [:] // service -> [interested, parties]
        actor {
            loop {
                react { msg ->
                    try {
                        changeService(msg, services, pending, { reply it }, sender)
                    } catch (ex) {
                        log.error('Error managing services', ex)
                    }
                }
            }
        }
    }

    private void changeService(msg, services, pending, reply, sender) {
        switch (msg.op) {
            case 'add':
                if (services[msg.service]?.etcdIndex >= msg.etcdIndex) break
                // msg.location is from etcd with zone/hostname/kind:mapped:native,more...
                // europe-west1-a/core2-2.c.want-vpc.internal/api:49153:6379
                if (!(msg.location ==~ /[\w-]+\/[\w\.-]+\/(?:\w+:\d+:\d+,?)+/)) {
                    log.warn("Don't know how to handle service publishing '${msg.location}'")
                    break
                }
                def (zone, host, ports) = msg.location.split('/')
                host = basename(host)
                ports = ports.split(',').collect { it.split(':').with { [ kind: it[0], mapped: it[1] as int, native_: it[2] as int ] } }
                ports.each { mapping ->
                    if (haproxy) {
                        haproxyd << [ op: 'add', host: host, port: mapping.mapped ]
                    } else {
                        // query GCE for forwarding rule named '$service-$port_kind' that points to target_instance:port
                        String rule = "${msg.service}-${mapping.kind}"
                        String target = "$gceUri/zones/$zone/targetInstances/$host-ti" // $host-ti is a pre-maid 'target instance'
                        // TODO rate-limit polling due to API limits, as we ask GCE on every service announcement (that happens every minute for every service)
                        ForwardingRule fr = maybe404 { gce.forwardingRules().get(project, region, rule).execute() }
                        if (!fr) {
                            log.info("About to insert forwarding rule '$rule' pointing to '$target:${mapping.mapped}'")
                            // fire and forget, hope its ok
                            gce.forwardingRules().insert(project, region,
                                    new ForwardingRule().setName(rule).setIPAddress(ip).setPortRange(mapping.mapped.toString()).setTarget(target)).execute()
                        } else {
                            if ("${mapping.mapped}-${mapping.mapped}" != fr.portRange) {
                                // Hell froze over
                                // TODO recreate forwarding rule
                                log.error("Forwarding rule '$rule' port range '${fr.portRange}' doesn't match expected '$mapping'")
                            } else if (fr.target != target) {
                                log.info("About to change target of forwarding rule '$rule' to '$target:${mapping.mapped}'")
                                gce.forwardingRules().setTarget(project, region, rule, new TargetReference().setTarget(target)).execute()
                            }
                        }
                    }
                    portmap << [ op: 'ensure', port: mapping.mapped ]
                }

                services[msg.service] = [ ports: ports, etcdIndex: msg.etcdIndex ]

                if (pending[msg.service]) {
                    def replies = pending[msg.service].collectEntries { req -> [req.sender, mapping(ports, req.port).mapped] }
                    pending.remove(msg.service)
                    timer.schedule({ replies.each { r, port -> r << port } }, 5, TimeUnit.SECONDS)
                }
                break

            case 'del':
                // wait a little bit for 'add' to arrive
                timer.schedule({ this.services << [ op: 'try-del', service: msg.service, etcdIndex: msg.etcdIndex ] }, 57, TimeUnit.SECONDS)
                break

            case 'try-del':
                if (services.containsKey(msg.service)) {
                    def service = services[msg.service]
                    if (service.etcdIndex <= msg.etcdIndex) {
                        service.ports.each { mapping ->
                            if (haproxy) {
                                haproxyd << [ op: 'del', port: mapping.mapped ]
                            } else {
                                String rule = "${msg.service}-${mapping.kind}"
                                log.info("About to delete forwarding rule '$rule'")
                                maybe404 { gce.forwardingRules().delete(project, region, rule).execute() }
                            }
                            portmap << [ op: 'free', port: mapping.mapped ]
                        }
                        services.remove(msg.service)
                    }
                }
                break

            case 'get':
                if (services.containsKey(msg.service))
                    reply mapping(services[msg.service].ports, msg.port).mapped
                else {
                    def req = [ sender: sender, port: msg.port ]
                    if (pending[msg.service]) pending[msg.service] += req
                    else pending[msg.service] = [req]
                }
                break
        }
    }

    private void etcdPortsSave(String suf, value) {
        def resp = etcd.put(path: "$etcdPorts/$suf") { urlenc value: value } // TODO do I really need to url-encode?
        if (![200, 201].contains(resp.statusCode))
            throw new RuntimeException("Cannot save '$etcdPorts/$suf' -> '$value' to ETCD: $resp")
    }

    // GCE balancer/forwarder cannot change port number - try the best we can
    private Actor managePorts() {
        // TODO loop here until success else we'll get strange failures later
        def r = maybe404 { etcd.get(path: etcdPorts) }
        if (r?.statusCode == 404) {
            etcdPortsSave('range', '10000-32000') // assuming Linux default sysctl net.ipv4.ip_local_port_range = 32768 61000
            etcdPortsSave('next', '10000')
            etcdPortsSave('free', '')
        }
        actor {
            loop {
                react { msg ->
                    try {
                        managePort(msg, { reply it })
                    } catch (ex) {
                        log.error("Failure managing free ports $msg", ex)
                        if (msg.op == 'alloc') reply '(actor error)'
                    }
                }
            }
        }
    }

    private void managePort(msg, reply) {
        // TODO these string splitting/recombination are not terribly efficient
        switch (msg.op) {
            case 'alloc':
                int port = 0
                def rnext = etcd.get(path: etcdPorts + '/next', query: [ consistent: 'true' ]) // quorum=true doesn't work
                def rrange = etcd.get(path: etcdPorts + '/range')
                int next = rnext.json.node.value as int
                String range = rrange.json.node.value
                def (start, end) = range.split('-').collect { it as int }
                if (next <= end) {
                    if (start > next)
                        next = start
                    port = next
                    etcdPortsSave('next', next+1)
                }
                if (!port) {
                    log.warn("Encountered end of port range to allocate free forwarding ports from, continuing with free-list")
                    // I would love to reuse free ports first, but it introduces subtle race-conditions all over the place
                    // like, service disappears from ETCD due to TTL, port is released, then it appears again, but port is already
                    // allocated to another service
                    def rfree = etcd.get(path: etcdPorts + '/free')
                    def str = rfree.json.node.value
                    if (str) {
                        def ports = str.split(',') as List
                        if (ports) {
                            port = ports.remove(0) as int
                            etcdPortsSave('free', ports.join(','))
                        }
                    }
                }
                log.info("Forwarding port allocated: $port")
                reply port ?: '(no free port found)'
                break

            case 'free':
                String port = msg.port.toString()
                log.info("Freeing port $port")
                def rfree = etcd.get(path: etcdPorts + '/free')
                def str = rfree.json.node.value ?: ''
                if (!str.split(',').grep(port))
                    etcdPortsSave('free', str ? "$str,$port" : port)
                break

            case 'ensure':
                // that port was already given out
                // ensure it is not stuck in free-list due to ETCD publication TTL expiration
                log.trace("Ensuring port ${msg.port} is not in free-list")
                def rfree = etcd.get(path: etcdPorts + '/free')
                def str = rfree.json.node.value
                if (str) {
                    def ports = str.split(',') as List
                    if (ports) {
                        int i = ports.indexOf(msg.port.toString())
                        if (i >= 0) {
                            log.warn("Forcibly removing port ${msg.port} from free-list")
                            ports.remove(i)
                            etcdPortsSave('free', ports.join(','))
                        }
                    }
                }
                break
        }
    }

    // TODO detect we're on `systemd` managed host and use that for HAProxy process management,
    // but systemd supported schemes does not match haproxy -st / sf lifecycle?
    private Actor manageHaproxy(config) {
        def ports = [:]
        def process = [
            reload: false,
            // at startup, let wait one minute to gather all port publications
            // otherwise we can overwrite perfectly working setup with limited
            // set of forwarding rules
            reloadAt: now() + (new File(config.pid).exists() ? 60000 : 0)
        ]
        actor {
            if (config.stoponexit) delegate.metaClass.onStop = { stopHaproxy(config) } // TODO doesn't work
            loop {
                react(1570) { msg ->
                    if (msg == Actor.TIMEOUT) {
                        // do nothing
                    } else {
                        try {
                            manageHaproxyPorts(config, msg, ports, process)
                        } catch (ex) {
                            log.error("Failure applying $msg to HAProxy config", ex)
                        }
                    }
                    if (process.reload && process.reloadAt <= now()) {
                        try {
                            process.reload = reloadHaproxy(config, ports)
                        } catch (ex) {
                            log.error("Failure reloading HAProxy with new config", ex)
                        }
                    }
                }
            }
        }
    }

    private void scheduleHaproxyReload(process) {
        if (!process.reload) {
            process.reload = true
            if (process.reloadAt < now()) process.reloadAt = now() + 1530
        }
    }

    private void manageHaproxyPorts(config, msg, ports, process) {
        switch (msg.op) {
            case 'add':
                if (ports[msg.port] != msg.host) {
                    ports[msg.port] = msg.host
                    scheduleHaproxyReload(process)
                }
                break

            case 'del':
                if (ports.containsKey(msg.port)) {
                    ports.remove(msg.port)
                    if (ports)
                        scheduleHaproxyReload(process)
                    else
                        stopHaproxy(config)
                } else
                    log.warn("HAProxy manager were asked to remove nonexisting mapping $msg, we have $ports")
                break
        }
    }

    def haproxyHeaderTemplate = templateEngine.createTemplate('''
        global
                log /dev/log user info
                maxconn 2000
                daemon
                pidfile $pidfile

        defaults
                log     global
                mode    tcp
                option  tcplog
                retries 3
                contimeout      5000
                clitimeout      3600000
                srvtimeout      3600000
        '''.stripIndent())
    def haproxyServiceTemplate = templateEngine.createTemplate('''
        listen  service-$port 0.0.0.0:$port
                dispatch $host:$port
        '''.stripIndent())

    private boolean generateHaproxyConfig(config, ports) {
        def conf = new File(config.conf)
        conf.withWriter { w ->
            haproxyHeaderTemplate.make([ pidfile: config.pid ]).writeTo(w)
            ports.each { port, host ->
                haproxyServiceTemplate.make([ host: host, port: port ]).writeTo(w)
            }
        }
    }

    private boolean reloadHaproxy(config, ports) {
        generateHaproxyConfig(config, ports)
        // haproxy doesn't remove pid file on exit, but anyway
        def pid = new File(config.pid)
        String pids = pid.exists() ? pid.text.with { String p = it.trim().replaceAll('\\s+', ' '); p ?: null } : null
        haproxyCmd("${config.bin} -f ${config.conf}" + (pids ? " -sf $pids" : ''))
    }

    private void stopHaproxy(config) {
        def pid = new File(config.pid)
        if (pid.exists()) {
            haproxyCmd("kill ${pid.text.trim()}")
            pid.delete()
        }
    }

    private boolean haproxyCmd(String cmd) {
        log.info("executing `$cmd`")
        def h = Runtime.runtime.exec(cmd)
        int status = h.waitFor()
        if (status > 0) {
            String stdout = h.inputStream.text
            String stderr = h.errorStream.text
            log.error("`haproxy` failed with status $status\n\t$cmd\n$stderr\n$stdout")
        }
        status > 0
    }

    private Compute initGCE() {
        // https://developers.google.com/compute/docs/authentication
        // https://gist.github.com/arkadijs/1969f3a02391cc8eca25
        // we use GCE Service Account by querying metadata server for an access token
        def initializer = new com.google.api.client.http.HttpRequestInitializer() {
            @Override
            void initialize(com.google.api.client.http.HttpRequest request) {
                request.headers.setAuthorization(
                    metadata.get(path: '/instance/service-accounts/default/token',
                        headers: [ 'X-Google-Metadata-Request': 'true' ], accept: ContentType.JSON)
                    .json.with { "$token_type $access_token" })
            }
        }
        new com.google.api.services.compute.Compute.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance(), initializer)
            .setApplicationName("${grailsApplication.metadata.'app.name'}/${grailsApplication.metadata.'app.version'}")
            .build()
    }

    def catalog() {
        render(contentType: 'application/json') { [ services: [ [
                id: serviceId,
                name: 'docker',
                description: 'Docker container deployment service',
                tags: [ 'docker', 'coreos' ],
                bindable: true,
                metadata: [
                    displayName: 'Docker',
                    imageUrl: logo,
                    longDescription: 'Docker container deployment service augments CloudFoundry with pre-packaged and ready to run software like databases, KV stores, analytics, etc. All of that in a manageable Docker format, backed by CoreOS cluster.',
                    providerDisplayName: 'ACP',
                    documentationUrl: 'https://redmine.hosting.lv/projects/cloudfoundry/wiki/Docker_service_broker',
                    supportUrl: 'mailto:a@hosting.lv'
                ],
                plans: [
                    [ id: redisId, name: 'redis',      description: 'Redis data structure server', metadata: [ displayName: 'Redis',      bullets: [ 'Redis 2.8',      '1GB pool', 'Persistence' ],                               costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: mysqlId, name: 'mysql',      description: 'MySQL database',              metadata: [ displayName: 'MySQL',      bullets: [ 'MySQL 5.6',      '1GB memory pool', '10GB InnoDB storage' ],                costs: [ [ amount: [ usd: 10 ],   unit: 'month' ] ] ] ],
                    [ id: mariaId, name: 'maria',      description: 'MariaDB database',metadata: [ displayName: 'MariaDB Galera Cluster', bullets: [ 'MariaDB 10.0.12','2 nodes / 1GB memory pool each', '10GB XtraDB storage' ], costs: [ [ amount: [ usd: 20 ],   unit: 'month' ] ] ] ],
                    [ id: pgId,    name: 'postgresql', description: 'PostgreSQL database',         metadata: [ displayName: 'PostgreSQL', bullets: [ 'PostgreSQL 9.3', '1GB memory pool', '10GB storage' ],                       costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: mongoId, name: 'mongodb',    description: 'MongoDB NoSQL database',      metadata: [ displayName: 'MongoDB',    bullets: [ 'MongoDB 2.6',    '1GB memory pool', '10GB storage' ],                       costs: [ [ amount: [ usd: 0.02 ], unit: 'hour'  ] ] ] ],
                    [ id: cassId,  name: 'cassandra',  description: 'Cassandra NoSQL database',    metadata: [ displayName: 'Cassandra',  bullets: [ 'Cassandra 2.1',  '2GB memory pool', '10GB storage' ],                       costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: oraId,   name: 'oracle',     description: 'Oracle database',             metadata: [ displayName: 'Oracle',     bullets: [ 'Oracle 11gR2 XE','1GB memory pool', '10GB storage' ],                       costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: rmqId,   name: 'rabbitmq',   description: 'RabbitMQ messaging broker',   metadata: [ displayName: 'RabbitMQ',   bullets: [ 'RabbitMQ 3.3',   '1GB persistence' ],                                       costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ]
                ]
            ] ] ]
        }
    }

    private boolean docker(String cmd, Closure closure = null) {
        log.debug("executing `$cmd`")
        def _cmd = (cmd =~ /([^"]\S*|".+?")\s*/).collect { _, arg -> arg.replace('"', '') } as String[]
        def docker = Runtime.runtime.exec(_cmd)
        int status = docker.waitFor()
        if (status > 0) {
            String stdout = docker.inputStream.text
            String stderr = docker.errorStream.text
            if (cmd =~ /^docker (rm|stop) / && stderr.contains('No such container:'))
                return false // not an error
            String error = "`docker` failed with status $status\n\t$cmd\n\t$_cmd\n$stderr\n$stdout"
            render(status: 500, text: error)
        }
        else if (closure) closure(docker.inputStream.text)
        status > 0
    }

    private boolean fleet(String cmd, Closure closure = null) {
        log.debug("executing `$cmd`")
        def fleetctl = Runtime.runtime.exec(cmd)
        int status = fleetctl.waitFor()
        if (status > 0) {
            String stdout = fleetctl.inputStream.text
            String stderr = fleetctl.errorStream.text
            if (cmd ==~ /fleetctl.* destroy .+/ && stderr.contains('could not find Job'))
                return false // not an error
            String error = "`fleetctl` failed with status $status\n\t$cmd\n$stderr\n$stdout"
            render(status: 500, text: error)
        }
        else if (closure) closure(fleetctl.inputStream.text)
        status > 0
    }

    private def say(int status, Closure closure = null) {
        render(status: status, contentType: 'application/json', closure ?: { [:] })
    }

    private boolean check(request, Map params = null) {
        switch (request.method) {
            case 'DELETE':
                if (params.service_id != serviceId || !plans.containsKey(params.plan_id)) {
                    render(status: 410, text: "No service/plan accepted here")
                    return true
                }
                break

            case 'PUT':
                def j = request.JSON
                if (j.service_id != serviceId || !plans.containsKey(j.plan_id)) {
                    render(status: 404, text: "No service/plan accepted here")
                    return true
                }
                break

            default: return true
        }
        false
    }

    private String password(String id) {
        new BigInteger(1, java.security.MessageDigest.getInstance('SHA-256').digest((secret + id).getBytes('UTF-8'))).toString(16).padLeft(64, '0')
    }
    private Map plan(HttpServletRequest request) { plans[request.JSON.plan_id] }
    private Map plan(Map params) { plans[params.plan_id] }
    private String container(HttpServletRequest request, Map params) {
        def plan = params.plan_id ? plan(params) : plan(request)
        "${plan.service}-${sanitize(params.instance_id)}"
    }
    private Map api(List<Map> ports)  { ports.find { it.kind == 'api' } }
    private Map mgmt(List<Map> ports) { ports.find { it.kind == 'management' } }
    private Map mapping(List<Map> ports, int native_) { ports.find { it.native_ == native_ } }

    private boolean publicEndpoint(String container, int nativePort, Closure closure) {
        if (coreos) {
            // asking Services Actor because both ETCD and Fleet may not know the answer yet
            // but Actor will reply when service is ready, or maybe not yet :/
            def port = services.sendAndWait([op: 'get', service: container, port: nativePort], 30, TimeUnit.SECONDS)
            if (port) closure(this.ip, port)
            else {
                String msg = "Failed to fetch port mapping of $container port $nativePort due to timeout"
                log.error(msg)
                render(status: 500, text: msg)
            }
        } else
            docker("docker port $container $nativePort") { String stdout ->
                int port = stdout.split(':')[1] as int
                closure(this.ip, port)
            }
    }

    def create() {
        if (check(request)) return

        def plan = plan(request)
        String container = container(request, params)
        String pass = password(params.instance_id)
        String args
        switch (plan.service) {
            case 'redis':      args = 'redis'; break
            case 'mysql':      args = "-e MYSQL_ROOT_PASSWORD=$pass mysql"; break
            case 'maria':      args = "-e MARIA_ROOT_PASSWORD=$pass arkadi/mariadb"; break
            case 'postgresql': args = 'postgres'; break // TODO introduce image that has password setup for postgres admin user
            case 'mongodb':    args = "-e \"MONGOD_OPTIONS=--nojournal --smallfiles --noprealloc --auth\" -e MONGO_ROOT_PASSWORD=$pass arkadi/mongodb"; break
          //case 'mongodb':    args = 'mongo mongod --nojournal --smallfiles --noprealloc'; break -- _/mongo image has no admin password
            case 'cassandra':  args = "arkadi/cassandra"; break // based on poklet/cassandra but with PasswordAuthenticator and CassandraAuthorizer
            case 'oracle':     args = "alexeiled/docker-oracle-xe-11g"; break
            case 'rabbitmq':   args = "-e RABBITMQ_PASS=$pass tutum/rabbitmq"; break
            default:
                render(status: 404, text: "No '${plan.service}' plan accepted here")
                return
        }
        def ports
        if (coreos) {
            ports = plan.ports.collect { p -> [ kind: p.kind, native_: p.port, mapped: portmap.sendAndWait([ op: 'alloc' ]) ] }
            if (ports.find { it.mapped in String }) {
                String error = "Error allocating forwarding ports"
                log.error("$error $ports")
                render(status: 500, text: error)
                return
            }
            // TODO verify ports collected are integers, but not some errors
            String tmpdir = System.getProperty('java.io.tmpdir')
            def unitParams = [
                cloud: cloud,
                service: container,
                docker_args: args,
                docker_ports_mapping: ports.collect { p -> "-p ${p.mapped}:${p.native_}" } .join(' '),
                etcd_ports_mapping: ports.collect { p -> "${p.kind}:${p.mapped}:${p.native_}" } .join(','),
                etcd_prefix: etcdServices
            ]
            def serviceUnit = new File(tmpdir, container + '.service')
            def discoveryUnit = new File(tmpdir, container + '-discovery.service')
            serviceUnit.withWriter { unitTemplate.make(unitParams).writeTo(it) }
            discoveryUnit.withWriter { discoveryTemplate.make(unitParams).writeTo(it) }
            fleet("fleetctl $fleetctlFlags start ${serviceUnit.absolutePath} ${discoveryUnit.absolutePath}")
            serviceUnit.delete()
            discoveryUnit.delete()
        } else {
            if (docker("docker run --name $container -P -d $args")) return
        }

        def management = mgmt(plan.ports)
        if (!management || !management.dashboard) say(201) else {
            def handler = { String ip, int port ->
                say(201) {
                    // an alternative way could be http://docs.cloudfoundry.org/services/dashboard-sso.html
                    def template = templateEngine.createTemplate(management.dashboard)
                    [ dashboard_url: template.make([ ip: ip, port: port, pass: pass ]).toString() ]
                }
            }
            if (coreos)
                // cannot ask for management port via publicEndpoint() as it may not be ready yet
                handler(this.ip, mgmt(ports).mapped)
            else
                publicEndpoint(container, management.port, handler)
        }
    }

    def unitTemplate = templateEngine.createTemplate('''
        [Unit]
        Description=$service
        Requires=docker.service
        After=docker.service
        Wants=${service}-discovery.service

        [Service]
        ExecStart=/bin/sh -c '/usr/bin/docker start -a $service || exec /usr/bin/docker run $docker_ports_mapping --name $service $docker_args'
        ExecStop=/usr/bin/docker stop $service
        ExecStop=/usr/bin/docker rm $service
        Restart=always
        '''.stripIndent())
    def discoveryTemplate = templateEngine.createTemplate('''
        [Unit]
        Description=$service discovery
        Requires=${service}.service
        After=${service}.service

        [Service]
        # let sleep to allow Docker container to start properly
        ExecStart=/bin/sh -c 'set -e; \
            case $cloud in \
                gce) zone=\\$(curl -s http://metadata/computeMetadata/v1/instance/zone -H "X-Google-Metadata-Request: true" | cut -d/ -f4);; \
                aws) zone=\\$(curl -s http://169.254.169.254/latest/meta-data/placement/availability-zone);; \
                *) zone=local;; \
            esac; \
            sleep 3; while :; do etcdctl set $etcd_prefix/$service \\$zone/%H/$etcd_ports_mapping --ttl 61; sleep 47; done'
        ExecStop=/usr/bin/etcdctl rm $etcd_prefix/$service
        Restart=always

        [X-Fleet]
        X-ConditionMachineOf=${service}.service
        '''.stripIndent())

    def delete() {
        if (check(request, params)) return
        def container = container(request, params)
        if (coreos) {
            if (fleet("fleetctl $fleetctlFlags destroy $container-discovery $container")) return
        } else {
            if (docker("docker stop $container")) return
            if (docker("docker rm $container")) return
        }
        say(200)
    }

    private String user(String id) { ('u' + sanitize(id)).substring(0, 16) }
    private String database(String id) { 'db' + sanitize(id) }
    private String vhost(String id) { 'v' + sanitize(id) }

    final String mydrv = 'com.mysql.jdbc.Driver'
    final String pgdrv = 'org.postgresql.Driver'
    final String oradrv = 'oracle.jdbc.OracleDriver'

    def bind() {
        if (check(request)) return

        def plan = plan(request)
        String container = container(request, params)
        String adminPass = password(params.instance_id)
        String pass      = password(params.binding_id)
        String db        = database(params.binding_id)
        String user      = user(params.binding_id)
        String vhost     = vhost(params.binding_id)

        // TODO retry Connection Refused errors from services a little bit
        // that may happen in Docker mode when bind() is called early after
        // service create(), even though CF retries on broker error, there will
        // be just 3 econnrefused induced errors in a row
        publicEndpoint(container, api(plan.ports).port) { String ip, int port ->
            def creds = null
            switch (plan.service) {
                // consider Redis as a single-tenant service
                case 'redis': creds = [ uri: "redis://$ip:$port", host: ip, port: port ]; break

                case 'mysql': case 'maria':
                    Sql.withInstance("jdbc:mysql://$ip:$port", 'root', adminPass, mydrv) { Sql mysql ->
                        mysql.execute("create database $db default character set utf8 default collate utf8_general_ci".toString())
                        mysql.execute("grant all privileges on $db.* to $user identified by '$pass'".toString())
                    }
                    creds = [ uri: "mysql://$ip:$port/$db", host: ip, port: port, username: user, password: pass, database: db,
                              jdbcUrl: "jdbc:mysql://$ip:$port/$db?user=$user&password=$pass" ]
                    break

                case 'postgresql':
                    Sql.withInstance("jdbc:postgresql://$ip:$port/template1", 'postgres', '', pgdrv) { Sql pg ->
                        pg.execute("create user $user password '$pass'".toString())
                        pg.execute("create database $db owner $user template template0 encoding = 'UNICODE'".toString())
                    }
                    creds = [ uri: "postgresql://$ip:$port/$db", host: ip, port: port, username: user, password: pass, database: db,
                              jdbcUrl: "jdbc:postgresql://$ip:$port/$db?user=$user&password=$pass" ]
                    break

                case 'mongodb':
                    // 'root' user is user manager - cannot manipulate data
                    // create dbOwner in db when bound to app
                    def mongo = new com.mongodb.MongoClient(
                        new com.mongodb.ServerAddress(ip, port),
                        [com.mongodb.MongoCredential.createMongoCRCredential('root', 'admin', adminPass.chars)],
                        new com.mongodb.MongoClientOptions.Builder().writeConcern(com.mongodb.WriteConcern.JOURNALED).build())
                    // db.createUser({ user: '$user', pwd: '$pass', roles: [ 'dbOwner' ] })
                    def create = new com.mongodb.BasicDBObjectBuilder()
                        .add('createUser', user).add('pwd', pass).add('roles', new com.mongodb.BasicDBList().with { add('dbOwner'); it })
                        .get()
                    mongo.getDB(db).command(create).throwOnError()
                    mongo.close()
                    creds = [ uri: "mongodb://$ip:$port/$db", host: ip, port: port, username: user, password: pass ]
                    break

                case 'cassandra':
                    def (cluster, cass) = cassandra(ip, port, adminPass)
                    try {
                        cass.execute("create keyspace $db with replication = {'class': 'SimpleStrategy', 'replication_factor': 1}")
                        cass.execute("create user $user with password '$pass'")
                        cass.execute("grant all on keyspace $db to $user")
                    } finally {
                        cass.closeAsync()
                        cluster.closeAsync()
                    }
                    creds = [ uri: "cassandra://$ip:$port/$db", host: ip, port: port, username: user, password: pass ]
                    break

                case 'oracle':
                    db = user
                    pass = pass.substring(0, 16)
                    Sql.withInstance("jdbc:oracle:thin:@$ip:$port:xe", 'system', 'oracle', oradrv) { Sql ora ->
                        ora.execute("create user $user identified by \"$pass\" default tablespace users temporary tablespace temp quota unlimited on users".toString())
                        ora.execute("grant connect, resource to $user".toString())
                    }
                    creds = [ uri: "oracle://$ip:$port/$db", host: ip, port: port, username: user, password: pass, database: db,
                              jdbcUrl: "jdbc:oracle:thin:$user/$pass@$ip:$port:xe" ]
                    break

                case 'rabbitmq':
                    publicEndpoint(container, mgmt(plan.ports).port) { String managementIp, int managementPort ->
                        def rmq = new RESTClient("http://$managementIp:$managementPort/api/", calmHttpClient)
                        rmq.authorization = new wslite.http.auth.HTTPBasicAuthorization('admin', adminPass)
                        rmq.put(path: "users/$user") { json password: pass, tags: '' }
                        rmq.put(path: "vhosts/$vhost") { type ContentType.JSON }
                        rmq.put(path: "permissions/$vhost/$user") { json configure: '.*', write: '.*', read: '.*' }
                        creds = [ uri: "amqp://$user:$pass@$ip:$port/$vhost", host: ip, port: port, username: user, password: pass ]
                    }
                    break

                default:
                    render(status: 404, text: "No '${plan.service}' plan accepted here")
                    return
            }

            if (creds) say(201) { [ credentials: creds ] }
        }
    }

    def cassandra(String ip, int port, String adminPass) {
        // http://www.datastax.com/drivers/java/2.0/com/datastax/driver/core/Cluster.Builder.html#addContactPointsWithPorts(java.util.Collection)
        // ... if the Cassandra nodes are behind a router and are not accessed directly. Note that if you are in this situation (Cassandra nodes
        // are behind a router, not directly accessible), you almost surely want to provide a specific AddressTranslater (through
        // withAddressTranslater(com.datastax.driver.core.policies.AddressTranslater)) to translate actual Cassandra node addresses to the
        // addresses the driver should use, otherwise the driver will not be able to auto-detect new nodes (and will generally not function optimally).
        def builder = Cluster.builder().addContactPointsWithPorts([new java.net.InetSocketAddress(ip, port)])
        def cluster = builder.withCredentials('cassandra', adminPass).build()
        try {
            [cluster, cluster.connect()]
        } catch (e) {
            log.info('Cannot authenticate as `cassandra` user, assuming default password must be changed: ' + e.message)
            cluster = builder.withCredentials('cassandra', 'cassandra').build()
            def cass = cluster.connect()
            cass.execute("alter user cassandra with password '$adminPass'")
            [cluster, cass]
        }
    }

    def unbind() {
        if (check(request, params)) return
        // TODO erase credentials
        say(200)
    }
}
