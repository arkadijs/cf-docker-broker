#!/bin/sh

region=us-west-1
stack=coreos-5
command_host=54.201.191.184/32

set -e

cloud_config=$(mktemp)
cluster_token=$(curl -sS https://discovery.etcd.io/new)
sed -e "s|{{cluster_token}}|$cluster_token|" < cloud-config.yml |
  sed -re "s/(metadata:).*/\\1 region=$region/" > $cloud_config

# TODO 'fleetctl' works, but 'etcdctl' does not
#   $ etcdctl --debug ls /
#   Cluster-Peers: http://172.31.10.73:4001 http://172.31.26.189:4001 http://172.31.10.74:4001
#   Curl-Example: curl -X GET http://172.31.10.73:4001/v2/keys/?consistent=true&recursive=false&sorted=false
# must use 'public_ipv4' but then we have troubles connecting within cluster as nodes want to use public IP
# and that is not allowed by our security group
#   sed -e 's/private_ipv4:/public_ipv4:/'

aws --region $region cloudformation create-stack \
  --stack-name $stack \
  --template-body file://cloudformation.template \
  --tags Key=Name,Value=$stack \
  --parameters \
      ParameterKey=InstanceType,ParameterValue=t2.small ParameterKey=KeyPair,ParameterValue=arkadi ParameterKey=AllowFleetCommandFrom,ParameterValue=$command_host ParameterKey=CloudConfig,ParameterValue="$(base64 $cloud_config)"

rm $cloud_config
