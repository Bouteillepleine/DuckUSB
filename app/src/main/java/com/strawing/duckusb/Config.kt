package com.strawing.duckusb

/** Shared constants for the hook and the UI. Both live toggles default ON. */
object Config {
    const val PKG = "com.strawing.duckusb"
    const val PREFS_NAME = "duck_usb_prefs"

    /** Spoof adb_enabled / adb_wifi_enabled / development_settings_enabled to scoped apps. */
    const val KEY_SPOOF = "spoof_enabled"

    /** Hide the persistent "USB debugging enabled" notification. */
    const val KEY_HIDE_NOTIF = "hide_notif_enabled"
}
