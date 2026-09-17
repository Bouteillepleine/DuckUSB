# Abort unless exactly one Zygisk implementation is installed and enabled.

ZYGISK_NAME=""

find_zygisk() {
    if [ -d "/data/adb/modules/$1" ] || [ -d "/data/adb/modules_update/$1" ]; then
        [ -f "/data/adb/modules/$1/disable" ] && return
        [ -f "/data/adb/modules/$1/remove" ] && return
        [ -n "$ZYGISK_NAME" ] && abort "! Multiple Zygisk frameworks found. Aborting to prevent conflicts"
        ZYGISK_NAME="$2"
    fi
}

find_zygisk "zygisksu" "ZygiskNext / NeoZygisk"
find_zygisk "rezygisk" "ReZygisk"
find_zygisk "admirepowered" "Zygisk Mod"
find_zygisk "zygisk_on_ksu" "Zygisk on KernelSU"
find_zygisk "yukizygisk" "YukiZygisk"
find_zygisk "onyxzygisk" "OnyxZygisk"

if [ -z "$ZYGISK_NAME" ]; then
    if [ "$ZYGISK_ENABLED" = "1" ]; then
        ZYGISK_NAME="Zygisk"
    else
        MAGISK_ZYGISK=$(magisk --sqlite "SELECT value FROM settings WHERE key = 'zygisk'" 2>/dev/null | cut -f2 -d=)
        [ "$MAGISK_ZYGISK" = "1" ] && ZYGISK_NAME="Zygisk"
    fi
fi

if [ -z "$ZYGISK_NAME" ]; then
    abort "! No Zygisk framework found. DuckUSB (Zygisk) needs one. Installation aborted"
else
    ui_print "- Found $ZYGISK_NAME"
fi
