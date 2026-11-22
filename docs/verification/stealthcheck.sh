#!/system/bin/sh
echo "== iptables local chain =="
iptables -t filter -S TUNCLASH_LOCAL 2>&1
echo "== output jump =="
iptables -t filter -S OUTPUT 2>&1 | grep TUNCLASH_LOCAL
echo "== ip6tables local chain =="
ip6tables -t filter -S TUNCLASH_LOCAL 2>&1
echo "== listening =="
ss -ltnp 2>/dev/null | grep -E '7890|9090|1053'
echo "== udp listening =="
ss -lunp 2>/dev/null | grep -E '7890|9090|1053'
echo "== app process =="
ps -A -o PID,USER,NAME 2>/dev/null | grep roottun
echo "== tun links =="
ip -o link show 2>/dev/null | head -20
echo "== ip rules =="
ip rule show | head -20
