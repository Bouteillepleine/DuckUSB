MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

sleep 30
rm -f "$MODDIR/boot_attempts"
log -t DuckUSB "boot completed, attempt counter cleared"
