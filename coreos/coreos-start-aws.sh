#!/bin/sh

region=us-west-1
stack=coreos-7
command_host=172.31.28.249/32

set -e

autoscaling_group=CoreOSServerAutoScale
initial_delay=60
retry_delay=10
retry_count=10

cloud_config=$(mktemp)
cluster_token=$(curl -sS https://discovery.etcd.io/new)
sed -e "s|{{cluster_token}}|$cluster_token|" < cloud-config.yml |
  sed -re "s/(metadata:).*/\\1 region=$region/" |
  sed -re 's|([/-])sdb|\1xvdb|g' > $cloud_config

# TODO 'fleetctl' works, but 'etcdctl' does not
#   $ etcdctl --debug ls /
#   Cluster-Peers: http://172.31.10.73:4001 http://172.31.26.189:4001 http://172.31.10.74:4001
#   Curl-Example: curl -X GET http://172.31.10.73:4001/v2/keys/?consistent=true&recursive=false&sorted=false
# must use 'public_ipv4' but then we have troubles connecting within cluster as nodes want to use public IP
# and that is not allowed by our security group
#   sed -e 's/private_ipv4:/public_ipv4:/'
# so, currently cluster users must live in the same region to be able to use private addresses to connect

aws --region $region cloudformation create-stack \
  --stack-name $stack \
  --template-body file://cloudformation.template \
  --tags Key=Name,Value=$stack \
  --parameters \
      ParameterKey=InstanceType,ParameterValue=t2.small ParameterKey=KeyPair,ParameterValue=arkadi ParameterKey=AllowFleetCommandFrom,ParameterValue=$command_host ParameterKey=CloudConfig,ParameterValue="$(base64 $cloud_config)"
rm $cloud_config
set +e
echo Waiting for CloudFormation...
sleep $initial_delay
i=0
problem () {
    echo "CloudFormation error, waited $(($initial_delay+$i*$retry_delay)) seconds"
    aws --output table --region $region cloudformation describe-stack-resources --stack $stack
    exit 1
}
while :; do
    status=$(aws --region $region cloudformation describe-stack-resource --stack $stack --logical-resource-id $autoscaling_group 2>&1 | grep ResourceStatus)
    if test -n "$status"; then
        if echo "$status" | grep CREATE_COMPLETE; then break; fi
        if echo "$status" | grep -Ev '(CREATE_|Resource creation Initiated)'; then problem; fi
    fi
    i=$(($i+1))
    if test $i -gt $retry_count; then problem; fi
    echo Still waiting...
    sleep $retry_delay
done

first_node=$(aws --region $region ec2 describe-instances --filters "Name=tag-value,Values=$stack" |
  grep PublicDnsName |
  sed -re 's/.*PublicDnsName.*(ec2-.+\.compute\.amazonaws\.com).*/\1/' |
  head -1)

echo "\nCoreOS cluster is ready!"
echo "Do 'export FLEETCTL_ENDPOINT=http://$first_node:4001' now."
echo "Build 'fleetctl' from https://github.com/coreos/fleet"
echo "Build 'etcdctl' from https://github.com/coreos/etcdctl"
echo "Configure cf-docker-broker/grails-app/conf/Config.groovy:"
echo "\t broker.v2.backend = 'coreos'"
echo "\t broker.v2.forwarder = 'haproxy'"
echo "\t broker.v2.publicip = true"
echo "\t broker.v2.coreoshost = '$first_node'"
