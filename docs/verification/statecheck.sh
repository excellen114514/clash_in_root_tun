#!/system/bin/sh
echo "== tun device =="
ip link show mihomo0 2>&1 | head -n 1
echo "== mihomo pid =="
pidof mihomo
echo "== ip rules (8100 / 2022) =="
ip rule show | grep -E '8100|2022'
echo "== table 2022 =="
ip route show table 2022
echo "== iptables chain TUNCLASH =="
iptables -t mangle -S TUNCLASH 2>&1
echo "== done =="
