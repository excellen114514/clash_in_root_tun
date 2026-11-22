#!/system/bin/sh
echo "== all mihomo listeners =="
ss -ltnp 2>/dev/null | grep mihomo
ss -lunp 2>/dev/null | grep mihomo
echo "== configs via unix socket =="
curl -sS --max-time 5 --unix-socket /data/data/com.roottun.clash/files/clash/mihomo/ctl.sock http://localhost/configs 2>&1 | head -c 800
echo ""
echo "== process fds (first 30) =="
ls -l /proc/11792/fd 2>/dev/null | head -30
echo "== tun links =="
ip -o link show | grep -E "net0|tun"
echo "== ip rules =="
ip rule show | head -12
echo ""