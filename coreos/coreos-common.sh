region=europe-west1
zone=europe-west1-a
machine_type=g1-small
index=3
nr_nodes=5
image=coreos-stable-444-4-0-v20141010

set -e
reserved_ip_name=core$index-services
index=${index:+$index-}
coreos_node () {
    echo core$index$1
}
coreos_nodes () {
    seq -s\  -f core$index%.0f 1 $nr_nodes
}
coreos_swap_disk () {
    echo core$index$1-swap
}
coreos_swap_disks () {
    seq -s\  -f core$index%.0f-swap 1 $nr_nodes
}
coreos_ti () {
    echo core$index$1-ti
}
coreos_tis () {
    seq -s\  -f core$index%.0f-ti 1 $nr_nodes
}
