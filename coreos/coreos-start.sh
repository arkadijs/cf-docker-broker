#!/bin/sh

. ./coreos-common.sh

# --boot_disk_size_gb=10
# --external_ip_address=none \ -- cannot docker pull w/o public IP
gcutil addinstance \
  --image=projects/coreos-cloud/global/images/$image \
  --boot_disk_type=pd-ssd --auto_delete_boot_disk --zone=$zone --machine_type=f1-micro \
  --service_account_scopes=compute-ro,storage-full,userinfo-email \
  --tags=coreos \
  --metadata_from_file=user-data:cloud-config.yml \
  $(coreos_nodes)

gcutil adddisk --disk_type=pd-standard --size_gb=10 --zone=$zone $(coreos_swap_disks)

for i in $(seq 1 $nr_nodes); do
    gcutil attachdisk --disk=$(coreos_swap_disk $i) --zone=$zone $(coreos_node $i) &
    sleep 0.5
done

wait

echo "\nYour CoreOS cluster is ready!\nDo 'export FLEETCTL_ENDPOINT=http://core${index:+$index-}1:4001' now.\nBuild 'fleetctl' from https://github.com/coreos/fleet\nBuild 'etcdctl' from https://github.com/coreos/etcdctl"
