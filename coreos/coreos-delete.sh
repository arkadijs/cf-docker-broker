#!/bin/sh

. ./coreos-common.sh

gcutil deleteinstance -f --delete_boot_pd --zone=$zone $(coreos_nodes)
gcutil deletedisk -f --zone=$zone $(coreos_swap_disks)
