package com.strawing.duckusb

/** Shared constants for the hook and the UI. Both live toggles default ON. */
object Config {
    const val PKG = "com.strawing.duckusb"
    const val PREFS_NAME = "duck_usb_prefs"

    /** Spoof adb_enabled / adb_wifi_enabled / development_settings_enabled to scoped apps. */
    const val KEY_SPOOF = "spoof_enabled"

    /**
     * Framework mode (default ON): a single hook in System Framework (system_server) on
     * ContentProvider$Transport.call spoofs the adb Settings keys for EVERY caller app at
     * the server-side chokepoint, gated by caller UID (apps only, never shell/system). No
     * per-app scope needed — just scope System Framework. Install-time; reboot to apply.
     */
    const val KEY_FRAMEWORK_MODE = "framework_mode_enabled"

    /**
     * Per-app client-side Settings fallback (default OFF): the legacy hook on the static
     * Settings.Global/Secure getters, installed only in scoped apps. Framework mode already
     * covers the normal paths; enable this only for an app that dodges it. Restart the app
     * to apply.
     */
    const val KEY_CLIENT_FALLBACK = "client_settings_fallback"

    /** Hide the persistent "USB debugging enabled" notification. */
    const val KEY_HIDE_NOTIF = "hide_notif_enabled"

    /**
     * Also spoof the raw USB/adb *system properties* (not just the Settings provider) so
     * detectors that read them via android.os.SystemProperties or native libc are fooled
     * too. Applies only to scoped, non-core-OS apps — same as the Settings spoof.
     */
    const val KEY_SPOOF_PROPS = "spoof_props_enabled"

    /**
     * Property overrides applied to scoped apps. `persist.sys.usb.config` is deliberately
     * omitted (persisted / boot-influencing; DuckUSB has always left it alone).
     */
    val PROP_OVERRIDES: Map<String, String> = mapOf(
        "sys.usb.ffs.ready" to "0",
        "sys.usb.config" to "mtp",
        "sys.usb.state" to "mtp",
        "init.svc.adbd" to "stopped",
        // "persist.sys.usb.config" to "mtp",
    )
}
