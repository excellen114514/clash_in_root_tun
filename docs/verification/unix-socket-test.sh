#!/system/bin/sh
# 验证 mihomo 是否支持：external-controller-unix + mixed-port:0 + 无 dns.listen
T=/data/local/tmp/mihomo-stealth-test
BIN=/data/data/com.roottun.clash/files/clash/mihomo/mihomo
rm -rf "$T"
mkdir -p "$T"
cat > "$T/config.yaml" <<'EOF'
mixed-port: 0
mode: rule
log-level: info
external-controller: ""
external-controller-unix: /data/local/tmp/mihomo-stealth-test/ctl.sock
dns:
  enable: true
  enhanced-mode: fake-ip
  fake-ip-range: 198.19.0.0/16
  nameserver:
    - 223.5.5.5
rules:
  - MATCH,DIRECT
EOF
cd "$T" || exit 9
"$BIN" -t -d "$T" -f "$T/config.yaml"
echo "TEST_RC=$?"
"$BIN" -d "$T" -f "$T/config.yaml" > "$T/run.log" 2>&1 &
PID=$!
sleep 3
echo "PID=$PID alive=$(kill -0 $PID 2>/dev/null && echo yes || echo no)"
echo "== socket file =="
ls -l "$T" 2>&1
echo "== tcp listeners of mihomo =="
ss -ltnp 2>/dev/null | grep "pid=$PID" || echo "none"
echo "== unix socket query (curl) =="
if command -v curl >/dev/null 2>&1; then
  curl -sS --max-time 5 --unix-socket "$T/ctl.sock" http://localhost/version 2>&1
  echo ""
  curl -sS --max-time 5 --unix-socket "$T/ctl.sock" http://localhost/configs 2>&1 | head -c 300
  echo ""
fi
kill $PID 2>/dev/null
sleep 1
echo "== run log =="
tail -20 "$T/run.log"
