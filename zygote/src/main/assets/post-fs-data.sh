MODDIR=${0%/*}
COUNT_FILE="$MODDIR/boot_attempts"
MAX=3

COUNT=0
[ -f "$COUNT_FILE" ] && COUNT=$(cat "$COUNT_FILE" 2>/dev/null)
case "$COUNT" in
    ''|*[!0-9]*) COUNT=0 ;;
esac

COUNT=$((COUNT + 1))
echo "$COUNT" > "$COUNT_FILE"
chmod 0644 "$COUNT_FILE"

if [ "$COUNT" -ge "$MAX" ]; then
    touch "$MODDIR/disable_hooks"
    chmod 0644 "$MODDIR/disable_hooks"
    log -t DuckUSB "boot attempt $COUNT reached the limit, hooks disabled"
fi
