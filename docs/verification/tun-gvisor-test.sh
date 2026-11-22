#!/system/bin/sh
BIN=/data/data/com.roottun.clash/files/clash/mihomo/mihomo
BASE=/data/local/tmp/mihomo-tun-test2
rm -rf "$BASE"; mkdir -p "$BASE"
run_case() {
  NAME="$1"; STACK="$2"; EXTRA="$3"
  D="$BASE/$NAME"; mkdir -p "$D"
  {
    echo "mixed-port: 0"
    echo "mode: direct"
    echo "log-level: info"
    echo "external-controller: \"\""
    echo "external-controller-unix: $D/ctl.sock"
    echo "dns:"
    echo "  enable: true"
    echo "  enhanced-mode: fake-ip"
    echo "  fake-ip-range: 198.19.0.0/16"
    echo "  nameserver: [223.5.5.5]"
    echo "tun:"
    echo "  enable: true"
    echo "  stack: $STACK"
    echo "  device: stealthtest0"
    echo "  auto-route: false"
    echo "  mtu: 1500"
    echo "  dns-hijack: [any:53]"
    echo "$EXTRA"
    echo "rules:"
    echo "  - MATCH,DIRECT"
  } > "$D/config.yaml"
  "$BIN" -t -d "$D" -f "$D/config.yaml" > "$D/test.log" 2>&1
  echo "== $NAME ($STACK): -t rc=$? =="
  "$BIN" -d "$D" -f "$D/config.yaml" > "$D/run.log" 2>&1 &
  PID=$!
  sleep 4
  if ip link show stealthtest0 >/dev/null 2>&1; then
    ip addr show stealthtest0 | sed -n '1,10p'
  else
    echo "not found"; tail -4 "$D/run.log"
  fi
  kill $PID 2>/dev/null; sleep 2
}
run_case gvisor_default gvisor ""
run_case gvisor_noipv6 gvisor "  inet6-address: []"