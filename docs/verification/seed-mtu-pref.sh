#!/system/bin/sh
P=/data/data/com.roottun.clash/shared_prefs/root_tun_clash_settings.xml
cat > "$P" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="tunMtu" value="9000" />
</map>
EOF
chown 10020:10020 "$P"
chmod 660 "$P"
ls -l "$P"
cat "$P"