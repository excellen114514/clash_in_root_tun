#!/system/bin/sh
# 在 MuMu 上验证 mihomo tun 的 inet6-address / inet4-address 行为
BIN=/data/data/com.roottun.clash/files/clash/mihomo/mihomo
BASE=/data/local/tmp/mihomo-tun-test
rm -rf "$BASE"; mkdir -p "$BASE"

run_case() {
  NAME="$1"; EXTRA="$2"
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
    echo "  nameserver:"
    echo "    - 223.5.5.5"
    echo "tun:"
    echo "  enable: true"
    echo "  stack: system"
    echo "  device: stealthtest0"
    echo "  auto-route: false"
    echo "  mtu: 1500"
    echo "  dns-hijack:"
    echo "    - any:53"
    echo "$EXTRA"
    echo "rules:"
    echo "  - MATCH,DIRECT"
  } > "$D/config.yaml"

  "$BIN" -t -d "$D" -f "$D/config.yaml" > "$D/test.log" 2>&1
  echo "== $NAME: -t rc=$? =="
  "$BIN" -d "$D" -f "$D/config.yaml" > "$D/run.log" 2>&1 &
  PID=$!
  sleep 4
  echo "alive=$(kill -0 $PID 2>/dev/null && echo yes || echo no)"
  if ip link show stealthtest0 >/dev/null 2>&1; then
    ip addr show stealthtest0 | sed -n '1,8p'
  else
    echo "device stealthtest0 not found"
    tail -6 "$D/run.log"
  fi
  kill $PID 2>/dev/null
  sleep 2
}

run_case default ""
run_case noipv6 "  inet6-address: []
  inet4-address:
    - 192.0.2.1/30"
echo "== done =="
