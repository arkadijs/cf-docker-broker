package v2

import com.google.api.services.compute.Compute
import groovy.sql.Sql
import groovyx.gpars.actor.Actor
import static groovyx.gpars.actor.Actors.actor
import wslite.rest.ContentType
import wslite.rest.RESTClient

class BrokerController {

    String serviceId = '8409b0f6-cb01-46bf-bdb2-ef98a7ba7ee3'
    String redisId = 'f73e4081-01ba-4cfa-9f20-817ff7fd2b47'
    String mysqlId = 'ad8f3dc2-ec3c-4df1-a7bb-0e4bf3be9576'
    String pgId    = '6fb18536-2d47-4e51-b4a5-f6cf41da4eaf'
    String mongoId = '8a145433-d206-457f-9ffa-1f2d850d6d58'
    String rmqId   = 'c34c7a5d-7b6c-4095-b06e-831fbf40e3c1'
    def plans = [ (redisId): [ s: 'redis',      p: 6379 ],
                  (mysqlId): [ s: 'mysql',      p: 3306 ],
                  (pgId):    [ s: 'postgresql', p: 5432 ],
                  (mongoId): [ s: 'mongodb',    p: 27017 ],
                  (rmqId):   [ s: 'rabbitmq',   p: 5672 ] ]
    int rmqMgmt = 15672
    String logo = 'data:image/png;base64,'
    def grailsApplication
    def grailsResourceLocator
    String secret
    boolean coreos
    String ip
    private final String etcdPrefix = '/cf-docker-broker'
    Thread _etcdWatch
    Actor _serviceWatch
    @Lazy private static volatile RESTClient metadata = new RESTClient('http://metadata//computeMetadata/v1')
    final int tm = 10000 // ms

    @javax.annotation.PostConstruct
    void init() {
        def c = grailsApplication.config?.broker?.v2
        if (!c) throw new RuntimeException('`broker.v2.backend` must be set in Grails app config')
        this.secret = c.secret ?: 'f779df95-2190-4a0d-ad5b-9f2ba4550ea9'
        this.coreos = c.backend == 'coreos' ? true : c.backend == 'docker' ? false : { throw new RuntimeException('`broker.v2.backend` must be one of: docker, coreos') }()
        if (this.coreos) {
            if (!c.coreoshost) throw new RuntimeException('`broker.v2.coreoshost` must be set if broker.v2.backend = coreos')
            // so far Fleet has no REST API available, we'll use etcd API and fleetctl
            def etcd = new RESTClient("http://${c.coreoshost}:4001/v2/keys")
            def gce = initGCE()
            this._serviceWatch = watchServices(gce)
            this._etcdWatch = Thread.startDaemon { watchEtcd(etcd, this._serviceWatch) }
        }
        this.ip = (c.publicip ? publicIp() : null) ?: Inet4Address.localHost.hostAddress // IPv4 is blossoming!
        this.logo += grailsResourceLocator.findResourceForURI('/images/docker-whale-150x150.png').file.bytes.encodeBase64().toString()
    }

    @javax.annotation.PreDestroy
    void shutdown() {
        if (this._etcdWatch) this._etcdWatch.with { interrupt(); join() }
        if (this._serviceWatch) this._serviceWatch.with { terminate(); join() }
    }

    private String publicIp() {
        try {
            String maybe =  this.coreos ? // CoreOS -> we're running on GCE
                 // URL.getText() cannot set HTTP header
                 // Metadata returns application/text which wslite doesn't like for providing response.text
                new String(metadata.get(path: '/instance/network-interfaces/0/access-configs/0/external-ip',
                                            headers: [ 'X-Google-Metadata-Request': true ], connectTimeout: tm, readTimeout: tm).data, 'UTF-8') :
                new URL('http://v4.ipv6-test.com/api/myip.php').getText([ connectTimeout: tm, readTimeout: tm, allowUserInteraction: false ])
            maybe ==~ /\d+\.\d+\.\d+\.\d+/ ? maybe : null
        } catch (e) { null }
    }

    private void watchEtcd(RESTClient etcd, Actor services) {
        while (!Thread.interrupted()) {
            try {
                def r = etcd.get(path: etcdPrefix, query: [ wait: true, recursive: true /*, waitIndex: */ ], accept: ContentType.JSON, connectTimeout: tm, readTimeout: tm)
                if (r.statusCode != 200) continue
                def j = r.json
                // {"action":"set","node":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":913,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:5555","modifiedIndex":909,"createdIndex":909}}
                // {"action":"update","node":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":920,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":913,"createdIndex":913}}
                // {"action":"delete","node":{"key":"/services/redis","modifiedIndex":3087,"createdIndex":913},"prevNode":{"key":"/services/redis","value":"1.2.3.4:6666","modifiedIndex":920,"createdIndex":913}}
                if (!j.node.key.startsWith(etcdPrefix + '/')) continue
                String service = j.node.key.substring(etcdPrefix.length + 1)
                switch (j.action) {
                    case 'set': case 'update':
                        services << [ op: 'add', service: service, address: j.node.value ]
                        break
                    case 'delete':
                        services << [ op: 'del', service: service ]
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

    private Actor watchServices(Compute gce) {
        def services = [:]
        actor {
            loop {
                react { msg ->
                    try {
                        switch (msg.op) {
                            case 'add':
                                break

                            case 'del':
                                break

                            case 'get':
                                break
                        }
                    } catch (e) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private Compute initGCE() {
        /* https://developers.google.com/compute/docs/authentication
           We use GCE service account by querying metadata server for an access token.
           Full Oauth2 flow is like the following - on first use, a prompt will be
           issued on the console to perform a manual interaction with Google in browser.

            import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
            import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
            import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
            import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
            import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
            import com.google.api.client.json.jackson2.JacksonFactory
            import com.google.api.client.util.store.FileDataStoreFactory
            import com.google.api.services.compute.Compute
            import com.google.api.services.compute.ComputeScopes
            @Lazy static volatile Compute compute = {
                // http://samples.google-api-java-client.googlecode.com/hg/compute-engine-cmdline-sample/instructions.html
                // hg clone https://code.google.com/p/google-api-java-client.samples/
                // alternatively we may use https://code.google.com/p/google-api-java-client/wiki/ClientLogin (deprecated)
                //   more info:
                // https://code.google.com/p/google-api-java-client/wiki/DeveloperGuide
                // https://developers.google.com/resources/api-libraries/documentation/compute/v1/java/latest/
                // http://javadoc.google-api-java-client.googlecode.com/hg/1.19.0/index.html
                def http = GoogleNetHttpTransport.newTrustedTransport()
                def dataStore = new FileDataStoreFactory(new java.io.File(System.getProperty("user.home"), (windows() ? "AppData/Local" : ".cache") + "/multicloud_google_compute_engine"))
                def json = JacksonFactory.getDefaultInstance()
                def secret = GoogleClientSecrets.load(json, new InputStreamReader(getClass().getResourceAsStream("/client_secret.json")))
                def flow = new GoogleAuthorizationCodeFlow.Builder(http, json, secret, [ ComputeScopes.COMPUTE ]).setDataStoreFactory(dataStore).build()
                def credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user")
                new Compute.Builder(http, json, null).setApplicationName("Accenture-MultiCloud/1.0").setHttpRequestInitializer(credential).build()
            } ()
        */
        def initializer = new com.google.api.services.compute.ComputeRequestInitializer() {
            @Override
            void initializeComputeRequest(com.google.api.services.compute.ComputeRequest<?> request) {
                super.initializeComputeRequest(request)
                request.setKey(
                    metadata.get(path: '/instance/service-accounts/default/token',
                        headers: [ 'X-Google-Metadata-Request': true ], accept: ContentType.JSON).json.access_token)
            }
        }
        new com.google.api.services.compute.Compute.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance(), null)
            .setApplicationName("${grailsApplication.metadata.'app.name'}/${grailsApplication.metadata.'app.version'}")
            .setGoogleClientRequestInitializer(initializer)
            .build()
    }

    def catalog() {
        render(contentType: 'application/json') { [ services: [ [
                id: serviceId,
                name: 'docker',
                description: 'Docker container deployment service',
                tags: [ 'docker', 'accenture' ],
                bindable: true,
                metadata: [
                    displayName: 'Docker',
                    imageUrl: logo,
                    longDescription: 'Docker container deployment service augments CloudFoundry with pre-packaged and ready to run software like databases, KV stores, analytics, etc. All of that in a manageable Docker format.',
                    providerDisplayName: 'Accenture',
                    documentationUrl: 'https://redmine.hosting.lv/projects/cloudfoundry/wiki/Docker_service_broker',
                    supportUrl: 'mailto:arkadijs.sislovs@accenture.com'
                ],
                plans: [
                    [ id: redisId, name: 'redis',      description: 'Redis data structure server', metadata: [ displayName: 'Redis',      bullets: [ 'Redis 2.8', '1GB pool', 'Persistence' ],                costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: mysqlId, name: 'mysql',      description: 'MySQL database',              metadata: [ displayName: 'MySQL',      bullets: [ 'MySQL 5.6', '1GB memory pool', '10GB InnoDB storage' ], costs: [ [ amount: [ usd: 10 ],   unit: 'month' ] ] ] ],
                    [ id: pgId,    name: 'postgresql', description: 'PostgreSQL database',         metadata: [ displayName: 'PostgreSQL', bullets: [ 'PostgreSQL 9.3', '1GB memory pool', '10GB storage' ],   costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ],
                    [ id: mongoId, name: 'mongodb',    description: 'MongoDB NoSQL database',      metadata: [ displayName: 'MongoDB',    bullets: [ 'MongoDB 2.6',    '1GB memory pool', '10GB storage' ],   costs: [ [ amount: [ usd: 0.02 ], unit: 'hour'  ] ] ] ],
                    [ id: rmqId,   name: 'rabbitmq',   description: 'RabbitMQ messaging broker',   metadata: [ displayName: 'RabbitMQ',   bullets: [ 'RabbitMQ 3.3',   '1GB persistence' ],                   costs: [ [ amount: [ usd: 0 ],    unit: 'month' ] ] ] ]
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
            String msg = "`docker` failed with status $status\n\$ $cmd\n$stderr\n$stdout"
            render(status: 500, text: msg)
        }
        else if (closure) closure(docker.inputStream.text)
        status > 0
    }

    private def say(int status, Closure closure = null) {
        render(status: status, contentType: 'application/json', closure ?: { [:] })
    }

    private boolean check(request) {
        switch (request.method) {
            case 'DELETE': break

            case 'PUT':
                def r = request.JSON
                if (r.service_id != serviceId || !plans.containsKey(r.plan_id)) {
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

    private Map plan(javax.servlet.http.HttpServletRequest request) {
        plans[request.JSON.plan_id]
    }

    private Map plan(Map params) {
        plans[params.plan_id]
    }

    private String sanitize(String str) {
        str.toLowerCase().replaceAll('[^a-z0-9]', '')
    }

    private String container(request, params) {
        def plan = params.plan_id ? plan(params) : plan(request)
        "${plan.s}-${sanitize(params.instance_id)}"
    }

    private boolean publicPort(String container, int privatePort, Closure closure) {
        docker("docker port $container $privatePort") { String stdout ->
            int port = stdout.split(':')[1].toInteger()
            closure(port)
        }
    }

    def create() {
        if (check(request)) return

        def plan = plan(request)
        String container = container(request, params)
        String pass = password(params.instance_id)
        String args = null
        switch (plan.s) {
            case 'redis':      args = 'redis'; break
            case 'mysql':      args = "-e MYSQL_ROOT_PASSWORD=$pass mysql"; break
            case 'postgresql': args = 'postgres'; break
            case 'mongodb':    args = 'mongo'; break
            case 'rabbitmq':   args = "-e RABBITMQ_PASS=$pass tutum/rabbitmq"; break
            default:
                render(status: 404, text: "No '${plan.s}' plan accepted here")
                return
        }
        if (coreos) {
            throw new RuntimeException('CoreOS support not implemented')
        } else {
            if (docker("docker run --name $container -P -d $args")) return
        }
        if (plan.s != 'rabbitmq') say(201) else
            publicPort(container, rmqMgmt) { int port ->
                say(201) {
                    // a proper way could be http://docs.cloudfoundry.org/services/dashboard-sso.html
                    // stolen from https://github.com/nimbus-cloud/cf-rabbitmq-broker/blob/master/rabbitmq/service.go#L121
                    [ dashboard_url: "http://$ip:$port/#/login/admin/$pass" ]
                }
            }
    }

    def delete() {
        if (check(request)) return
        def container = container(request, params)
        if (docker("docker stop $container")) return
        if (docker("docker rm $container")) return
        say(200)
    }

    private String user(String id) { ('u' + sanitize(id)).substring(0, 16) }
    private String database(String id) { 'db' + sanitize(id) }
    private String vhost(String id) { 'v' + sanitize(id) }

    private String mydrv = 'com.mysql.jdbc.Driver'
    private String pgdrv = 'org.postgresql.Driver'

    def bind() {
        if (check(request)) return

        def plan = plan(request)
        String container = container(request, params)
        String adminPass = password(params.instance_id)
        String pass      = password(params.binding_id)
        String db        = database(params.binding_id)
        String user      = user(params.binding_id)
        String vhost     = vhost(params.binding_id)

        publicPort(container, plan.p) { int port ->
            def creds = null
            switch (plan.s) {
                case 'redis': creds = [ uri: "redis://$ip:$port", host: ip, port: port ]; break

                case 'mysql':
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
                    publicPort(container, rmqMgmt) { int managementPort ->
                        def rmq = new RESTClient("http://$ip:$managementPort/api/")
                        rmq.authorization = new wslite.http.auth.HTTPBasicAuthorization('admin', adminPass)
                        rmq.put(path: "users/$user") { json password: pass, tags: '' }
                        rmq.put(path: "vhosts/$vhost") { type ContentType.JSON }
                        rmq.put(path: "permissions/$vhost/$user") { json configure: '.*', write: '.*', read: '.*' }
                        creds = [ uri: "amqp://$user:$pass@$ip:$port/$vhost", host: ip, port: port, username: user, password: pass ]
                    }
                    break

                default:
                    render(status: 404, text: "No '${plan.s}' plan accepted here")
                    return
            }

            if (creds) say(201) { [ credentials: creds ] }
        }
    }

    def unbind() {
        if (check(request)) return
        // TODO erase credentials
        say(200)
    }
}
