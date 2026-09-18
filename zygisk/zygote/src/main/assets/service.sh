MODDIR=${0%/*}
LIMIT=150
MASKED="persist.sys.usb.config=mtp init.svc.adbd=stopped"
MASK_PASSES=10
MASK_INTERVAL=30

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

sh "$MODDIR/describe.sh" 2>/dev/null

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
    log -t DuckUSB "no resetprop available, properties left alone"
    exit 0
}

# -n is load-bearing: it writes the property area directly. Going through
# property_service would fire init's "on property:init.svc.adbd=stopped" rule, which
# clears sys.usb.ffs.ready and takes the USB gadget down entirely.
pass=0
while [ "$pass" -lt "$MASK_PASSES" ]; do
    for pair in $MASKED; do
        key=${pair%%=*}
        want=${pair#*=}
        if [ "$(getprop $key)" != "$want" ]; then
            "$RESETPROP" -n "$key" "$want"
            log -t DuckUSB "$key now reads $(getprop $key) in memory"
        fi
    done
    pass=$((pass + 1))
    sleep "$MASK_INTERVAL"
done
