### Docker Service Broker for CloudFoundry

This a Grails / Groovy application prototyping Docker as a container service to allow rapid CloudFoundry service development and deployment.

CloudFoundry is an elastic runtime that by its definition does not provide services like databases, etc., but instead host custom (web) applications only. Traditionally, [BOSH] is embraced as the deployment technology for such [Service] and it's associated broker, which glues together CloudFoudry and the service in a service management life-cycle.

Developing new service by this approach requires development of a service-specific BOSH _release_, understanding of the BOSH usage and troubleshooting workflows, and, most important - writing the broker itself. While there are open-source examples of releases and brokers available, like [spring-boot-cf-service-broker], [cf-mysql-release], [cf-riak-cs-release], [cf-rmq-release], [logstash], to name a few that works - this is not a small task.

While there is significant value in using BOSH, some situations may dictate less complex infrastructure.

Luckily, the only thing required for a basic CloudFoundry service broker is an implementation of 5 REST calls of [Service Broker API]. Combining that with [Docker] and (optionally) [CoreOS], allows us to schedule arbitrary containers in almost generic way.

Some work is still required, though:

1. Providing metadata about the service for CloudFoundry [Service Catalog].
2. Development of Docker image. This is [surely easier](https://registry.hub.docker.com/) than [BOSH release].
3. A piece of custom code, in order of 10-20 lines, to provision (multi-tenant) service for a tenant/app, ie. create user, database, virtual host, etc. ([binding] in terms of API).

#### Docker

Configure `broker.v2.backend = 'docker'` in  `grails-app/conf/Config.groovy` and run:

    $ grails -reloading run-app    # or
    $ ./grailsw run-app

`-reloading` is required if you want development mode due to [GRAILS-10850](https://jira.grails.org/browse/GRAILS-10850)

#### CoreOS

The prototype only works on Google Compute Engine.

Create a new VM that will host the broker, `f1-micro` is good enough. Give it read-write access to the GCE project Compute resources. Via UI: press _Show advanced options_ on the right top of _New instance_ screen. Under _Project Access_ below, check _Enable Compute Engine service account_ and select _Read Write_ in _COMPUTE_ combo. Alternatively, supply `--service_account_scopes=compute` to `gcutil`.

`cd coreos/` and edit CoreOS cluster scripts, starting with `coreos-common.sh`. Launch CoreOS cluster with `./coreos-start.sh`.

The CoreOS itself will be ready in a minute, so that you could use `etcdctl` and `fleetctl` immediately. But, wait some 10-20 minutes before you _ssh_ to the nodes, as we pre-seed required docker images via `systemd` service (see `coreos/cloud-config.yml`), but due to some init sequence peculiarities _ssh keys_ are not imported from project's metadata by Google user management daemon until this process is done.

Configure `broker.v2.backend = 'coreos'` in  `grails-app/conf/Config.groovy` together with the rest of required attributes, then run:

    $ grails -reloading run-app    # or
    $ ./grailsw run-app

#### CloudFoundry

Login to CloudFoundry and setup initial space:

    $ cf login -a http://api.foundry.dom -u admin -p pass
    $ cf create-space demo
    $ cf target -o admin -s demo

Register broker with CloudFoundry and make it's plans public:

    $ cf create-service-broker docker a b http://broker-vm:8080
    $ for p in $(cf curl /v2/service_plans -X 'GET' |grep '"guid"' |cut -d\" -f4); do
        cf curl /v2/service_plans/$p -X 'PUT' -d '{"public":true}';
     done

[Resolved](https://www.pivotaltracker.com/s/projects/892938/stories/57752202) at 08/08/14. ~~(Somebody, tell me, why we need the PUT stanza instead of nice `cf service-plan` command or something?)~~

#### Internals

Alpha quality software ahead. Works on Google Compute Engine only.

The broker is almost stateless and, with some help, will hopefully recover from many errors. Fairly minimal amount of state is kept in ETCD: free port management, try `etcdctl ls /cf-docker-broker/ports`. Services (containers) are published under `/cf-docker-broker/services`. Broker does not write this sub-tree, only listen for changes.

Catalog is static and configured in `grails-app/controllers/v2/BrokerController.groovy : catalog()`.

On service creation, `create()` is invoked:

1. `def plans` definition is read and free ports are requested from Portmap Actor. The `/cf-docker-broker/ports/next` counter is advanced in ETCD.
2. Two _systemd service unit_ files are generated: (a) service docker container startup, and (b) _discovery_ shell script that publishes information to ETCD from the same host the service is running on, for example: `/cf-docker-broker/services/redis-123` => `europe-west1-a/core2-2.c.my-proj.internal/api:49153:6379`. Unit files are submitted to `fleetctl`.
3. ETCD is watched by ETCD Watcher thread and changes are sent to Services Actor.
4. Services Actor maintain some book-keeping to notify outstanding requesters about newly created endpoints. It will create Protocol Forwarding Rule pointing to the (GCE target) instance that host the service in that moment. Thus IP:port is kept stable for CloudFoundry clients. Do service migrate or service publication expire, changes will appear in ETCD and Rule will be updated or deleted. Port will be freed in later case by sending a message to Portmap Actor, that will write it in `/cf-docker-broker/ports/free`.
5. Do service randomly re-appear, the port will be erased from `free` list.
6. If service has `management` kind of access port defined with `dashboard` template, then a dashboard link is generated.

Next, when service is bound, `bind()` is invoked:

1. Service Actor is queried about port mapping. ETCD may not have the information yet. In that case requester is suspended and will be notified later, see above.
2. Service-specific code kicks-in to setup users, databases, vhosts, etc. in the service instance via its native API (JDBC, REST). You must write this piece of code if your service is _bindable_. Hope the infrastructure is ready - forwarding is up, service is listening - so that _bind_ won't timeout.

When service is unbound, `unbind()` is invoked that must erase the credentials, vhosts, etc. Not implemented yet.

When service is deleted, `delete()` is invoked that removes service units with `fleetctl destroy`. Changes are propagated via ETCD (expiry by TTL), forwarding rules are deleted, ports are freed.

There is also a short [slide-deck](http://goo.gl/tgTfXW).

#### Lessons learned

1. Persistent storage (like database tables) with restartable containers is still an enigma with Docker and/or CoreOS. More or less solved by BOSH (with correctly written BOSH release).
2. Docker default networking is limited. Only works for services that expose (a couple of) static ports. Say goodbye to Asterisk / SIP / RTP. Port management is a burden. Having a separate IP per container, like a VM, is possible, but an entirely your own [adventure](https://docs.docker.com/articles/networking/#building-your-own-bridge). Kubernetes people [agree](https://github.com/GoogleCloudPlatform/kubernetes/blob/master/docs/networking.md).
3. Even flagship Docker containers are of questionable quality.
4. Hopefully there are [ubuntu-upstart] and [phusion/baseimage-docker] for proper runtime init. People, let start using those! Let restart failed services so that failure won't bubble-up to Docker. (I agree, it depends.)  
5. Overloading a node in CoreOS cluster may not work very well for the whole cluster. Jobs will bounce in hordes overloading other nodes (in case they don't have the capacity), leaving cluster de-facto inoperable. ETCD and Fleet CLI tools will timeout, giving a hard chance to diagnose the problem. `systemctl` still works, luckily.
6. Supposed to be resolved. ~~[BOSH errands] are crap. Booting a VM to run a single command, like `curl -X POST` to register a broker? Listen, there are more: the VM(-s) is/are here to stay forever even if you don't need it/them anymore. Probably just in case you'll want to run the command again. To dismiss the errand's VM you change the (deployment) manifest and re-deploy to adjust resource pool setup. Who invented that, J2EE architects?~~
7. CloudFoundry doesn't work? Good luck finding why. Have a large deployment manifest with 25+ VM-s (?) - _ssh_ randomly across the machines and lure under `/var/vcap/sys/log`. Got `syslog_aggregator:` property prudently setup in CloudFoundry deployment manifest? You'll get everything at `user.notice` syslog facility/severity. Try to write more regexps for [logstash] - maybe it will help parse those pesky syslogs.
8. BOSH emits _cannot convert string to integer_? Look **very** carefully at deployment manifest. No clue? Ok, _ssh_ to BOSH machine, look into logs over there (in `/var/vcap/sys/log`). Doesn't work yet? I'm still with you: leave only one _resque_ worker alive: `monit summary`, `monit status`, `monit stop worker_2`, `monit stop worker_3` are your friends. Start adding logging to BOSH Ruby gems somewhere under `/var/vcap` (at least everything is under that legacy-named directory). Then `monit restart worker_1`. Resubmit the deployment. Wash-repeat.
9. `monit` is not spotless.
10. Surely it was once necessary to repackage everything under `/var/vcap` to create BOSH release. `systemd` solved the `monit` problem at least. Let users use distribution packages - changing `--datadir` to `/var/vcap/somewhere` is easier than [that](http://docs.cloudfoundry.org/bosh/create-release.html)!
11. [Stemcells] are built in dark undergrounds of [inferno]. We have a choice of Ubuntu and CentOS. No idea which build do work, or doesn't. Hope `go_agent` is faster than Ruby one, so it won't take BOSH several minutes to just pick-up a VM.
12. Last, but not least - **CoreOS is beautiful**. Someday it may even work.

[CloudFoundry]: http://docs.cloudfoundry.org/
[BOSH]: http://docs.cloudfoundry.org/bosh
[Service]: http://docs.cloudfoundry.org/services/overview.html
[spring-boot-cf-service-broker]: https://github.com/cloudfoundry-community/spring-boot-cf-service-broker
[cf-mysql-release]: https://github.com/cloudfoundry/cf-mysql-release
[cf-riak-cs-release]: https://github.com/cloudfoundry/cf-riak-cs-release
[cf-rmq-release]: https://github.com/FreightTrain/rmq-release
[logstash]: https://github.com/arkadijs/logstash-es-kibana-boshrelease
[Service Broker API]: http://docs.cloudfoundry.org/services/api.html
[Docker]: https://docker.com/
[CoreOS]: https://coreos.com/
[Service Catalog]: http://docs.cloudfoundry.org/services/api.html#catalog-mgmt
[BOSH release]: http://docs.cloudfoundry.org/bosh/create-release.html
[binding]: http://docs.cloudfoundry.org/services/api.html#binding
[BOSH errands]: http://docs.cloudfoundry.org/bosh/jobs.html#errand-running
[ubuntu-upstart]: https://registry.hub.docker.com/_/ubuntu-upstart/
[phusion/baseimage-docker]: http://phusion.github.io/baseimage-docker/
[Stemcells]: http://bosh_artifacts.cfapps.io/file_collections?type=stemcells
[inferno]: https://github.com/cloudfoundry/bosh/tree/master/bosh-stemcell
