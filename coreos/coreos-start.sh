#!/bin/sh

. ./coreos-common.sh

cloud_config=$(mktemp)
cluster_token=$(curl https://discovery.etcd.io/new)
sed -e "s|{{cluster_token}}|$cluster_token|g" < cloud-config.yml > $cloud_config
# --boot_disk_size_gb=10 -- CoreOS is 9GB
# --external_ip_address=none \ -- cannot do `docker pull` w/o public IP
gcutil addinstance \
  --image=projects/coreos-cloud/global/images/$image \
  --boot_disk_type=pd-ssd --auto_delete_boot_disk --zone=$zone --machine_type=g1-small \
  --service_account_scopes=compute-ro,storage-full,userinfo-email \
  --tags=coreos \
  --metadata_from_file=user-data:$cloud_config \
  $(coreos_nodes)

gcutil adddisk --disk_type=pd-standard --size_gb=10 --zone=$zone $(coreos_swap_disks)

for i in $(seq 1 $nr_nodes); do
    node=$(coreos_node $i)
    gcutil attachdisk --disk=$(coreos_swap_disk $i) --zone=$zone $node &
    sleep 0.1
    gcutil addtargetinstance --instance=$node --zone=$zone $(coreos_ti $i) &
    sleep 0.1
done

gcutil reserveaddress --region=$region $reserved_ip_name
reserved_ip=$(gcutil getaddress --region=$region $reserved_ip_name | grep \ ip | awk '{print $4}')

wait

echo "\nCoreOS cluster is ready!"
echo "Do 'export FLEETCTL_ENDPOINT=http://core${index}1:4001' now."
echo "Build 'fleetctl' from https://github.com/coreos/fleet"
echo "Build 'etcdctl' from https://github.com/coreos/etcdctl"
echo "Configure cf-docker-broker/grails-app/conf/Config.groovy:"
echo "\t broker.v2.backend = 'coreos'"
echo "\t broker.v2.publicip = '$reserved_ip'"
echo "\t broker.v2.coreoshost = 'core${index}1'"
