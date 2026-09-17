package com.strawing.duckusb.common

object Config {
    const val PKG = "com.strawing.duckusb.zygisk"
    const val MODULE_ID = "duckusb_zygisk"
    const val MODULE_VERSION = "2.0.0"
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

    val SPOOF_KEYS = setOf(
        "adb_enabled",
        "adb_wifi_enabled",
        "development_settings_enabled",
    )

    val SKIP_SPOOF_PROCESSES = setOf(
        "android",
        "system",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.phone",
    )

    val SPARE_PACKAGES: Set<String> = setOf(
        "com.android.mtp",
        "com.android.externalstorage",
        "com.android.storagemanager",
        "com.android.sharedstoragebackup",
        "com.oneplus.filemanager",
        "com.oplus.filemanager",
        "com.oplus.ota",
    )

    val PROP_OVERRIDES: Map<String, String> = mapOf(
        "sys.usb.config" to "mtp",
        "sys.usb.state" to "mtp",
        "init.svc.adbd" to "stopped",
    )

    val ADB_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")

    const val SETTINGS_AUTHORITY = "settings"
    const val CALL_VALUE = "value"
    const val CALL_GENERATION_INDEX = "_generation_index"

    val GET_METHODS = setOf("GET_global", "GET_secure", "GET_system")
    val LIST_METHODS = setOf("LIST_global", "LIST_secure", "LIST_system")
}
