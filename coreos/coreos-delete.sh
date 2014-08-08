#!/bin/sh

. ./coreos-common.sh
set +e
gcutil releaseaddress -f --region=$region $reserved_ip_name
gcutil deletetargetinstance -f --zone=$zone $(coreos_tis)
gcutil deleteinstance -f --delete_boot_pd --zone=$zone $(coreos_nodes)
gcutil deletedisk -f --zone=$zone $(coreos_swap_disks)
