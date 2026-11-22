#!/system/bin/sh
CFG=/data/data/com.roottun.clash/files/clash/mihomo/config.yaml
for k in mixed-port port socks-port external-controller tun dns proxies proxy-groups rules geodata-mode; do
  n=$(grep -c "^$k:" "$CFG")
  echo "$k=$n"
done