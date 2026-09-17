MODDIR=${0%/*}
LIMIT=150

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
