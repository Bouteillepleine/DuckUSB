MODDIR=${0%/*}
LIMIT=150
MASK_INTERVAL=10

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

flag() {
    CONFIG="$MODDIR/config.json"
    [ -f "$CONFIG" ] || return 1
    grep -q "\"$1\"[[:space:]]*:[[:space:]]*true" "$CONFIG"
}

masked_list() {
    [ -f "$MODDIR/disable_hooks" ] && return 1
    flag paused && return 1
    flag spoofProps || return 1
    # sys.usb.config stays out of this: init's "on property:sys.usb.config=*" rules act on it
    # and reconfigure the gadget, so masking it drops the adb function. sys.usb.state has no
    # init consumer - init only ever writes it as an echo of sys.usb.config - and masking it
    # keeps the three readable values consistent, because a half-masked set is its own tell.
    LIST="persist.sys.usb.config=mtp init.svc.adbd=stopped sys.usb.state=mtp"
    echo "$LIST"
    return 0
}

RESETPROP=$(find_resetprop) || {
    log -t DuckUSB "no resetprop available, properties left alone"
    exit 0
}

# resetprop -w parks on the property's own futex until the key is written again, so the mask
# comes back in milliseconds instead of up to MASK_INTERVAL seconds. Flags have to precede the
# positionals - "-w KEY VALUE --timeout N" is rejected by the parser - and a build without it
# falls back to the plain interval.
WAIT=0
"$RESETPROP" -h 2>&1 | grep -q -- "--wait" && WAIT=1

# Runs for the life of the boot, not a fixed number of passes: init rewrites init.svc.* on
# every service state change, so a USB mode switch or a stop/start of adbd unmasks it again.
#
# -n is load-bearing. Going through property_service would fire init's
# "on property:init.svc.adbd=stopped" rule, which clears sys.usb.ffs.ready and takes the USB
# gadget down entirely.
while true; do
    if MASKED=$(masked_list); then
        WAIT_KEY=
        for pair in $MASKED; do
            key=${pair%%=*}
            want=${pair#*=}
            if [ "$(getprop $key)" != "$want" ]; then
                "$RESETPROP" -n "$key" "$want"
                log -t DuckUSB "$key now reads $(getprop $key) in memory"
            fi
            [ "$key" = "init.svc.adbd" ] && WAIT_KEY=$key
        done
        if [ "$WAIT" = 1 ] && [ -n "$WAIT_KEY" ]; then
            # Every adbd restart - USB reconnect, function switch, adb usb/tcpip - rewrites this
            # key, and that is the same event that makes init rewrite sys.usb.state.
            START=$(date +%s)
            "$RESETPROP" -w --timeout "$MASK_INTERVAL" "$WAIT_KEY" "$(getprop $WAIT_KEY)" >/dev/null 2>&1
            # The wait is woken by our own -n write as well, so bound the loop if the key flaps.
            [ "$(( $(date +%s) - START ))" -le 0 ] && sleep 1
            continue
        fi
    fi
    sleep "$MASK_INTERVAL"
done
