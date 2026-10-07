package com.strawing.duckusb.common

object Config {
    const val PKG = "com.strawing.duckusb.zygisk"
    const val MODULE_ID = "duckusb_zygisk"
    const val MODULE_VERSION = "2.1.6"
    const val LIVE_PROPERTY = "duckusb.zygisk.live"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val MODULE_UPDATE_DIR = "/data/adb/modules_update/$MODULE_ID"
    const val CONFIG_FILE = "$MODULE_DIR/config.json"
    const val PACKAGES_DIR = "$MODULE_DIR/packages"
    const val DISABLE_FILE = "$MODULE_DIR/disable_hooks"
    const val BOOT_COUNT_FILE = "$MODULE_DIR/boot_attempts"
    const val MAX_BOOT_ATTEMPTS = 3

    const val SYSTEM_SERVER_PACKAGE = "android"
    const val ALL_PACKAGES = ".all"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val FIRST_APP_UID = 10000
    const val PER_USER_RANGE = 100000

    val SPOOF_VALUES: Map<String, String> = mapOf(
        "adb_enabled" to "0",
        "adb_wifi_enabled" to "0",
        "development_settings_enabled" to "0",
        "adb_allowed_connection_time" to "604800000",
        "verifier_verify_adb_installs" to "1",
        "stay_on_while_plugged_in" to "0",
    )

    val SPOOF_KEYS: Set<String> = SPOOF_VALUES.keys

    val SPARE_PACKAGES: Set<String> = setOf(
        "com.strawing.duckusb",
        "com.android.mtp",
        "com.android.externalstorage",
        "com.android.storagemanager",
        "com.android.sharedstoragebackup",
        "com.oneplus.filemanager",
        "com.oplus.filemanager",
        "com.oplus.ota",
    )

    const val PERSIST_USB_PROP = "persist.sys.usb.config"
    const val PERSIST_USB_SAFE = "mtp"

    // Only the key the property area cannot mask: init's "on property:sys.usb.config=*" rules
    // reconfigure the gadget from it. persist.sys.usb.config, init.svc.adbd and sys.usb.state
    // are masked globally by service.sh instead, so hooking them here would duplicate that at
    // the cost of an inline patch in every scoped app.
    val PROP_OVERRIDES: Map<String, String> = mapOf(
        "sys.usb.config" to "mtp",
    )

    val ADB_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")

    const val SETTINGS_AUTHORITY = "settings"
    const val CALL_VALUE = "value"
    const val CALL_GENERATION_INDEX = "_generation_index"
    const val CALL_SETTINGS_LIST = "result_settings_list"

    val GET_METHODS = setOf("GET_global", "GET_secure", "GET_system")
    val LIST_METHODS = setOf("LIST_global", "LIST_secure", "LIST_system")
}
