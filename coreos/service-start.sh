#!/bin/sh

set -e

if test $# -ne 3; then
    echo "Usage:\n\tstart-service.sh <service-name> <port> <docker-args>"
    exit 1
fi

etcd_prefix=/cf-docker-broker

service=$1
port=$2
shift 2
docker_args=$@

unit=/tmp/$service.service
discovery=/tmp/$service-discovery.service

cat >$unit <<EOU
[Unit]
Description=$service
Requires=docker.service
After=docker.service

[Service]
# TODO implement port management
ExecStart=/bin/sh -c '/usr/bin/docker start -a $service || exec /usr/bin/docker run -P --name $service $docker_args'
ExecStop=/usr/bin/docker stop $service
ExecStop=/usr/bin/docker rm $service
EOU

cat >$discovery <<EOD
[Unit]
Description=$service discovery
Requires=$service.service
After=$service.service

[Service]
ExecStart=/bin/sh -c 'while :; do port=\$(docker port $service $port | cut -d: -f2); \
 zone=\$(curl -s http://metadata/computeMetadata/v1/instance/zone -H 'X-Google-Metadata-Request: true' | cut -d/ -f4); \
 etcdctl set $etcd_prefix/$service \$zone:%H:\$port --ttl 60; sleep 45; done'
ExecStop=/usr/bin/etcdctl rm /services/$service

[X-Fleet]
X-ConditionMachineOf=$service.service
EOD

fleetctl start $unit $discovery
