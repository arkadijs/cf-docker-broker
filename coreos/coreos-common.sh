zone=europe-west1-a
index=
nr_nodes=3
#image=coreos-alpha-394-0-0-v20140801
image=coreos-stable-367-1-0-v20140724

set -e
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
