#!/system/bin/sh
P=/data/data/com.roottun.clash/shared_prefs
echo "== prefs files =="
ls -l "$P" 2>&1
echo "== keys =="
grep -o 'name="[^"]*"[^>]*value="[^"]*"' "$P"/*.xml 2>&1 | head -30
echo "== set tunMtu=9000 to simulate upgraded user =="
if [ -f "$P/roottun_settings.xml" ]; then
  sed -i 's/<int name="tunMtu" value="[0-9]*"/<int name="tunMtu" value="9000"/' "$P/roottun_settings.xml"
  if ! grep -q 'name="tunMtu"' "$P/roottun_settings.xml"; then
    sed -i 's#</map>#    <int name="tunMtu" value="9000" />\n</map>#' "$P/roottun_settings.xml"
  fi
  grep -o 'tunMtu[^/]*' "$P/roottun_settings.xml"
else
  echo "settings file not found; listing:"
  ls "$P"
fi
