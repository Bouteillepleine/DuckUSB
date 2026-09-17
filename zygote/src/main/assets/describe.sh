MODDIR=${0%/*}
PROP="$MODDIR/module.prop"
CONFIG="$MODDIR/config.json"
BASE="Hides USB debugging, wireless debugging and Developer Options from every app."

[ -f "$PROP" ] || exit 0

flag() {
    [ -f "$CONFIG" ] || return 1
    grep -q "\"$1\"[[:space:]]*:[[:space:]]*true" "$CONFIG"
}

if [ -f "$MODDIR/disable_hooks" ]; then
    STATUS="⛔ Hooks disabled"
    DETAIL="Every app reads the truth. Turn the kill switch off, then reboot."
elif ! [ -f "$CONFIG" ]; then
    STATUS="⚠️ No configuration"
    DETAIL="$BASE"
elif flag paused; then
    STATUS="⏸️ Paused"
    DETAIL="Hooks are loaded but everything reads true."
elif ! flag frameworkMode; then
    STATUS="⚠️ Framework mode off"
    DETAIL="Nothing is spoofing. Turn it on in the app, then reboot."
elif ! flag spoofSettings; then
    STATUS="⚠️ Settings spoof off"
    DETAIL="The hook is live but the settings spoof is switched off."
else
    STATUS="✅ Active"
    if flag frameworkAllApps; then
        DETAIL="Every app reads USB debugging as off"
    else
        DETAIL="Selected apps read USB debugging as off"
    fi
    flag hideNotif && DETAIL="$DETAIL · notification hidden"
    flag spoofProps && DETAIL="$DETAIL · usb prop masked"
fi

DESC="[$STATUS] $DETAIL. Checked $(date '+%d %b %H:%M')."

TMP="$MODDIR/.module.prop.new"
awk -v d="$DESC" '/^description=/ { print "description=" d; found = 1; next } { print }
    END { if (!found) print "description=" d }' "$PROP" > "$TMP" 2>/dev/null || exit 0
[ -s "$TMP" ] || { rm -f "$TMP"; exit 0; }
cat "$TMP" > "$PROP"
rm -f "$TMP"
