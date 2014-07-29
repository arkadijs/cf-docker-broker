package v2

import groovy.sql.Sql
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
    String logo = 'data:image/png;base64,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'
    String ip = publicIp() ?: Inet4Address.localHost.hostAddress //(grailsApplication.config.broker.v2.publicip ? publicIp() : null) ?: Inet4Address.localHost.hostAddress // IPv4 is blossoming!
    String secret = 'f779df95-2190-4a0d-ad5b-9f2ba4550ea9' //grailsApplication.config.broker.v2.secret

    private String publicIp() {
        try {
            int tm = 10000 // ms
            String maybe = new URL('http://v4.ipv6-test.com/api/myip.php').getText([ connectTimeout: tm, readTimeout: tm, allowUserInteraction: false ])
            maybe ==~ /\d+\.\d+\.\d+\.\d+/ ? maybe : null
        } catch (e) { null }
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
            String msg = "`docker` failed with status $status\n\$ $cmd\n${docker.errorStream.text}\n${docker.inputStream.text}"
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
        if (docker("docker run --name $container -P -d $args")) return
        if (plan.s != 'rabbitmq') say(201) else
            publicPort(container, rmqMgmt) { int port ->
                say(201) {
                    // doesn't work
                    // proper way could be http://docs.cloudfoundry.org/services/dashboard-sso.html
                    [ dashboard_url: "http://admin:$pass@$ip:$port/" ]
                }
            }
    }

    def delete() {
        if (check(request)) return
        def container = container(request, params)
        docker("docker stop $container")
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
                        rmq.put(path: "vhosts/$vhost") { type wslite.rest.ContentType.JSON }
                        rmq.put(path: "permissions/$vhost/$user") { json configure: '.*', write: '.*', read: '.*' }
                        creds = [ uri: "rabbitmq://$user:$pass@$ip:$port/$vhost", host: ip, port: port, username: user, password: pass ]
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
