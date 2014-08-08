package v2

import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.*
import groovy.sql.Sql
import groovy.text.SimpleTemplateEngine
import groovyx.gpars.actor.Actor
import static groovyx.gpars.actor.Actors.actor
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import wslite.rest.ContentType
import wslite.http.HTTPClient
import wslite.rest.RESTClient

class BrokerController {
    def grailsApplication
    def grailsResourceLocator
    def log = org.apache.log4j.Logger.getLogger(getClass())

    final String serviceId = '8409b0f6-cb01-46bf-bdb2-ef98a7ba7ee3'
    final String redisId = 'f73e4081-01ba-4cfa-9f20-817ff7fd2b47'
    final String mysqlId = 'ad8f3dc2-ec3c-4df1-a7bb-0e4bf3be9576'
    final String mariaId = 'be850e2f-83f4-4157-8f08-7cdba5c12fc4'
    final String pgId    = '6fb18536-2d47-4e51-b4a5-f6cf41da4eaf'
    final String mongoId = '8a145433-d206-457f-9ffa-1f2d850d6d58'
    final String rmqId   = 'c34c7a5d-7b6c-4095-b06e-831fbf40e3c1'
    final def plans = [
        (redisId): [ service: 'redis',      ports: [ [ port: 6379,  kind: 'api' ] ] ],
        (mysqlId): [ service: 'mysql',      ports: [ [ port: 3306,  kind: 'api' ] ] ],
        (mariaId): [ service: 'maria',      ports: [ [ port: 3306,  kind: 'api' ] ] ],
        (pgId):    [ service: 'postgresql', ports: [ [ port: 5432,  kind: 'api' ] ] ],
        (mongoId): [ service: 'mongodb',    ports: [ [ port: 27017, kind: 'api' ] ] ],
        (rmqId):   [ service: 'rabbitmq',   ports: [ [ port: 5672,  kind: 'api' ],
            // dashboard url stolen from https://github.com/nimbus-cloud/cf-rabbitmq-broker/blob/master/rabbitmq/service.go#L121
            [ port: 15672, kind: 'management', dashboard: 'http://$ip:$port/#/login/admin/$pass' ] ] ] ]

    final int tm = 10000 // ms
    String logo = 'data:image/png;base64,'
    // configuration
    String secret
    String ip
    boolean coreos
    // CoreOS and GCE specific vals
    String project
    String region // we work at region level with a single IP dedicated to protocol forwarding
    RESTClient etcd // ETCD is used to discover services location and track free ports for protocol forwarding
    final String etcdPrefix   = '/cf-docker-broker'
    final String etcdServices = etcdPrefix + '/services'
    final String etcdPorts    = etcdPrefix + '/ports'
    Compute gce
    String gceUri = 'https://www.googleapis.com/compute/v1/projects/'
    String fleetctlFlags
    Thread discoverer // watches ETCD for published service endpoints
    Actor services    // (a) receives info from Discoverer to manipulate GCE protocol forwarding rules, (b) answers clients about service public endpoint
    Actor portmap     // manages free ports for protocol forwarding, stores state in ETCD
    @Lazy volatile HTTPClient calmHttpClient = new HTTPClient().with { connectTimeout = readTimeout = tm*10; it }
    @Lazy volatile HTTPClient httpClient = new HTTPClient().with { connectTimeout = readTimeout = tm; it }
    @Lazy volatile RESTClient metadata = new RESTClient('http://metadata/computeMetadata/v1', httpClient)

    @javax.annotation.PostConstruct
    void init() {
        def c = grailsApplication.config?.broker?.v2
        if (!c) throw new RuntimeException('`broker.v2.backend` must be set in Grails app config')
        this.secret = c.secret ?: 'f779df95-2190-4a0d-ad5b-9f2ba4550ea9'
        this.coreos = c.backend == 'coreos' ? true : c.backend == 'docker' ? false : { throw new IllegalArgumentException('`broker.v2.backend` must be one of: docker, coreos') }()
        if (this.coreos) {
            if (!c.coreoshost) throw new RuntimeException('`broker.v2.coreoshost` must be set if broker.v2.backend = coreos')
            if (!looksIp(c.publicip)) throw new IllegalArgumentException('`broker.v2.public` must be set to a reserved static IP address if broker.v2.backend = coreos')
            this.ip = c.publicip
            this.region = c.region ?: metadataRegion()
            this.project = c.project ?: metadataProject()
            this.gceUri += project
            this.fleetctlFlags = "--request-timeout=10 --endpoint=http://${c.coreoshost}:4001/"
            // so far Fleet has no REST API available, we'll use ETCD API and fleetctl
            this.etcd = new RESTClient("http://${c.coreoshost}:4001/v2/keys", httpClient)
            this.gce = initGCE()
            this.portmap = managePorts()
            this.services = watchServices()
            this.discoverer = Thread.startDaemon('ETCD Watcher') { watchEtcd() }
        } else { // docker
            this.ip = (c.publicip ? publicIp() : null) ?: Inet4Address.localHost.hostAddress // IPv4 is blossoming!
        }
        this.logo += grailsResourceLocator.findResourceForURI('/images/docker-whale-150x150.png').file.bytes.encodeBase64().toString()
    }

    @javax.annotation.PreDestroy
    void shutdown() {
        if (coreos) {
            if (this.discoverer) this.discoverer.with { interrupt(); join() }
            if (this.services) this.services.with { terminate(); join() }
            if (this.portmap) this.portmap.with { terminate(); join() }
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
    private boolean looksIp(String maybe) { maybe ==~ /\d+\.\d+\.\d+\.\d+/ ? maybe : false } // anchored match
    private String _metadata(String path) {
        // URL.getText() cannot set HTTP header
        // Metadata returns application/text which wslite doesn't like for providing response.text
        str(metadata.get(path: path, headers: [ 'X-Google-Metadata-Request': 'true' ]).data)
    }
    private String metadataIp() { _metadata('/instance/network-interfaces/0/access-configs/0/external-ip') }
    private String metadataProject() { _metadata('/project/project-id') }
    private String metadataRegion() {
        _metadata('/instance/zone').with { // projects/77511713522/zones/europe-west1-b
            substring(lastIndexOf('/') + 1, lastIndexOf('-'))
        }
    }
    private String publicIp() {
        try {
            'http://v4.ipv6-test.com/api/myip.php'.toURL()
                .getText([ connectTimeout: tm, readTimeout: tm, allowUserInteraction: false ])
                .with { looksIp(maybe) ?: null }
        } catch (e) { null }
    }

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
                    if (ex.cause instanceof SocketTimeoutException) continue
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
                if (change?.statusCode != 200) continue
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
                // query GCE for forwarding rule named '$service-$port_kind' that points to target_instance:port
                ports.each { mapping ->
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
                    portmap << [ op: 'ensure', port: mapping.mapped ]
                }

                services[msg.service] = [ ports: ports, etcdIndex: msg.etcdIndex ]

                if (pending[msg.service]) {
                    pending[msg.service].each { req -> req.sender << mapping(ports, req.port).mapped }
                    pending.remove(msg.service)
                }
                break

            case 'del':
                // wait a little bit for 'add' to arrive
                groovyx.gpars.scheduler.Timer.timer.schedule(
                    { this.services << [ op: 'try-del', service: msg.service, etcdIndex: msg.etcdIndex ] },
                    57, TimeUnit.SECONDS)
                break

            case 'try-del':
                if (services.containsKey(msg.service)) {
                    def service = services[msg.service]
                    if (service.etcdIndex <= msg.etcdIndex) {
                        service.ports.each { mapping ->
                            String rule = "${msg.service}-${mapping.kind}"
                            log.info("About to delete forwarding rule '$rule'")
                            maybe404 { gce.forwardingRules().delete(project, region, rule).execute() }
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
                def rnext = etcd.get(path: etcdPorts + '/next')
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
                log.debug("Ensuring port ${msg.port} is not in free-list")
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
                    [ id: rmqId,   name: 'rabbitmq',   description: 'RabbitMQ messaging broker',   metadata: [ displayName: 'RabbitMQ',   bullets: [ 'RabbitMQ 3.3',   '1GB persistence' ],                                       costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ]
                ]
            ] ] ]
        }
    }

    private boolean docker(String cmd, Closure closure = null) {
        def docker = Runtime.runtime.exec(cmd)
        int status = docker.waitFor()
        if (status > 0) {
            String stdout = docker.inputStream.text
            String stderr = docker.errorStream.text
            if (cmd.startsWith('docker rm') && stderr.contains('No such container:'))
                return false // not an error
            String error = "`docker` failed with status $status\n\$ $cmd\n$stderr\n$stdout"
            render(status: 500, text: error)
        }
        else if (closure) closure(docker.inputStream.text)
        status > 0
    }

    private boolean fleet(String cmd, Closure closure = null) {
        def fleetctl = Runtime.runtime.exec(cmd)
        int status = fleetctl.waitFor()
        if (status > 0) {
            String stdout = fleetctl.inputStream.text
            String stderr = fleetctl.errorStream.text
            if (cmd ==~ /fleetctl.* destroy .+/ && stderr.contains('could not find Job'))
                return false // not an error
            String error = "`fleetctl` failed with status $status\n\$ $cmd\n$stderr\n$stdout"
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
            case 'postgresql': args = 'postgres'; break
            case 'mongodb':    args = 'mongo'; break
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
            }
            // TODO verify ports collected are integers, but not some errors
            String tmpdir = System.getProperty('java.io.tmpdir')
            def unitParams = [
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

    def templateEngine = new SimpleTemplateEngine()
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
        ExecStart=/bin/sh -c 'zone=\\$(curl -s http://metadata/computeMetadata/v1/instance/zone -H "X-Google-Metadata-Request: true" | cut -d/ -f4); \
            sleep 3; while :; do etcdctl set $etcd_prefix/$service \\$zone/%H/$etcd_ports_mapping --ttl 61; sleep 47; done'
        ExecStop=/usr/bin/etcdctl rm $etcd_prefix/$service

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

    def bind() {
        if (check(request)) return

        def plan = plan(request)
        String container = container(request, params)
        String adminPass = password(params.instance_id)
        String pass      = password(params.binding_id)
        String db        = database(params.binding_id)
        String user      = user(params.binding_id)
        String vhost     = vhost(params.binding_id)

        publicEndpoint(container, api(plan.ports).port) { String ip, int port ->
            def creds = null
            switch (plan.service) {
                case 'redis': creds = [ uri: "redis://$ip:$port", host: ip, port: port ]; break

                case 'mysql': case 'maria':
                    Sql.withInstance("jdbc:mysql://$ip:$port", 'root', adminPass, mydrv) { Sql mysql ->
                        mysql.execute("create database $db default character set utf8 default collate utf8_general_ci".toString())
                        mysql.execute("grant all privileges on $db.* to $user identified by '$pass'".toString())
                    }
                    creds = [ uri: "mysql://$user:$pass@$ip:$port/$db", host: ip, port: port, username: user, password: pass, database: db ]
                    break

                case 'postgresql':
                    Sql.withInstance("jdbc:postgresql://$ip:$port/template1", 'postgres', '', pgdrv) { Sql pg ->
                        pg.execute("create user $user password '$pass'".toString())
                        pg.execute("create database $db owner $user template template0 encoding = 'UNICODE'".toString())
                    }
                    creds = [ uri: "postgresql://$user:$pass@$ip:$port/$db", host: ip, port: port, username: user, password: pass, database: db ]
                    break

                case 'mongodb': creds = [ uri: "mongodb://$ip:$port", host: ip, port: port ]; break // TODO create database and credentials

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

    def unbind() {
        if (check(request, params)) return
        // TODO erase credentials
        say(200)
    }
}
