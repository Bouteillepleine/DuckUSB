MODDIR=${0%/*}
LIMIT=150
USB_PROP=persist.sys.usb.config
USB_SAFE=mtp
USB_PASSES=10
USB_INTERVAL=30

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    i=$((i + 1))
    if [ "$i" -ge "$LIMIT" ]; then
        touch "$MODDIR/disable_hooks"
        chmod 0644 "$MODDIR/disable_hooks"
        log -t DuckUSB "boot did not complete in ${LIMIT}s, hooks disabled"
        setprop sys.powerctl reboot
        exit 0
    fi
    sleep 1
done

sleep 30
rm -f "$MODDIR/boot_attempts"
log -t DuckUSB "boot completed, attempt counter cleared"

find_resetprop() {
    for candidate in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /data/adb/magisk/resetprop; do
        [ -x "$candidate" ] && echo "$candidate" && return 0
    done
    command -v resetprop 2>/dev/null && return 0
    return 1
}

prop_spoof_wanted() {
    [ -f "$MODDIR/disable_hooks" ] && return 1
    CONFIG="$MODDIR/config.json"
    [ -f "$CONFIG" ] || return 1
    grep -q '"spoofProps"[[:space:]]*:[[:space:]]*true' "$CONFIG" || return 1
    grep -q '"paused"[[:space:]]*:[[:space:]]*true' "$CONFIG" && return 1
    return 0
}

prop_spoof_wanted || exit 0

RESETPROP=$(find_resetprop) || {
    log -t DuckUSB "no resetprop available, $USB_PROP left alone"
    exit 0
}

pass=0
while [ "$pass" -lt "$USB_PASSES" ]; do
    if [ "$(getprop $USB_PROP)" != "$USB_SAFE" ]; then
        "$RESETPROP" -n "$USB_PROP" "$USB_SAFE"
        log -t DuckUSB "$USB_PROP now reads $(getprop $USB_PROP) in memory"
    fi
    pass=$((pass + 1))
    sleep "$USB_INTERVAL"
done
