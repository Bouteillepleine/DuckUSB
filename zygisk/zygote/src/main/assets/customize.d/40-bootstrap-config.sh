# Keep the user's config across updates, create defaults on a fresh install.

OLD_CONFIG="/data/adb/modules/duckusb_zygisk/config.json"

if [ -f "$OLD_CONFIG" ]; then
    ui_print "- Keeping the existing configuration"
    cp -af "$OLD_CONFIG" "$MODPATH/config.json"
else
    ui_print "- Writing the default configuration"
    cat > "$MODPATH/config.json" <<'JSON'
{
  "version": 1,
  "paused": false,
  "spoofSettings": true,
  "spoofProps": true,
  "hideNotif": true,
  "coverQueryPath": true,
  "verboseLog": false,
  "targets": []
}
JSON
fi

OLD_PACKAGES="/data/adb/modules/duckusb_zygisk/packages"
if [ -d "$OLD_PACKAGES" ]; then
    for f in "$OLD_PACKAGES"/*; do
        [ -e "$f" ] || continue
        cp -af "$f" "$MODPATH/packages/"
    done
fi

rm -f "$MODPATH/boot_attempts" "$MODPATH/disable_hooks"

set_perm "$MODPATH/config.json" 0 0 0644
set_perm_recursive "$MODPATH/packages" 0 0 0755 0644
