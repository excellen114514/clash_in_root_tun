#!/system/bin/sh
BIN=/data/data/com.roottun.clash/files/clash/mihomo/mihomo
D=/data/local/tmp/mihomo-cfgtest
rm -rf "$D"; mkdir -p "$D"
cat > "$D/config.yaml" <<'EOF'
mixed-port: 0
mode: direct
log-level: info
external-controller: ""
external-controller-unix: /data/local/tmp/mihomo-cfgtest/ctl.sock
tun:
  enable: true
  stack: gvisor
  device: cfgtest0
  auto-route: false
  mtu: 1500
  inet4-address:
    - 192.0.2.1/30
  inet6-address: []
rules:
  - MATCH,DIRECT
EOF
"$BIN" -d "$D" -f "$D/config.yaml" > "$D/run.log" 2>&1 &
PID=$!
sleep 3
echo "== /configs tun section =="
curl -sS --unix-socket "$D/ctl.sock" http://localhost/configs 2>&1 | tr ',' '\n' | grep -i -E "inet|mtu|device|stack|fake-ip-range" | head -20
echo "== ip addr =="
ip addr show cfgtest0 2>&1 | sed -n '1,10p'
kill $PID 2>/dev/null