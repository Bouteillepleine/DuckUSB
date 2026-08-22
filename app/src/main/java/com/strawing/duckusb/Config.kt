package com.strawing.duckusb

/** Shared constants for the hook and the UI. Both live toggles default ON. */
object Config {
    const val PKG = "com.strawing.duckusb"
    const val PREFS_NAME = "duck_usb_prefs"

    /**
     * Master pause. ANDed over every other gate: while true, no hook body does anything.
     * It does NOT uninstall hooks or unload the native library — those are decided at process
     * load — so it is a pause, not a module off-switch. LSPosed's own switch is the real one.
     * Pushed live over the binder, so pausing takes effect on system_server immediately.
     */
    const val KEY_PAUSED = "paused"

    /** Spoof adb_enabled / adb_wifi_enabled / development_settings_enabled to scoped apps. */
    const val KEY_SPOOF = "spoof_enabled"

    /**
     * Framework mode — EXPERIMENTAL, default OFF. A single hook in System Framework
     * (system_server) on ContentProvider$Transport.call would spoof the adb Settings keys
     * for every app at the server-side chokepoint. ⚠️ On some ROMs (verified OP15 / Android
     * 16) hooking Transport.call in system_server BOOTLOOPS the device, so this stays a
     * manual opt-in: scope System Framework yourself and enable this at your own risk.
     */
    const val KEY_FRAMEWORK_MODE = "framework_mode_enabled"

    /**
     * Per-app Settings spoof (default ON): the client-side hook on the static
     * Settings.Global/Secure getters, installed in each scoped app. The safe, proven path.
     * Restart the app to apply.
     */
    const val KEY_CLIENT_FALLBACK = "client_settings_fallback"

    /**
     * Verbose diagnostics: one LSPosed log line per injection (package / process / uid / which
     * guards matched). Invaluable when a hook silently fails to install — that logging is what
     * proved the property spoof was reaching system_server — but it is ~11 lines per boot, so
     * it stays OFF unless you are investigating.
     */
    const val KEY_VERBOSE_LOG = "verbose_log"

    /** Hide the persistent "USB debugging enabled" notification. */
    const val KEY_HIDE_NOTIF = "hide_notif_enabled"

    /**
     * Retired toggle. The property spoof is no longer switchable: it applies automatically in
     * every scoped non-core app, because scoping an app in LSPosed already expresses the intent
     * and the UI cannot read LSPosed's scope to gate a switch honestly. The key is kept only so
     * old preference files still parse; nothing reads it.
     */
    @Deprecated("Property spoof is automatic for scoped apps; no longer user-switchable.")
    const val KEY_SPOOF_PROPS = "spoof_props_enabled"

    /**
     * Property overrides applied to scoped apps. `persist.sys.usb.config` is deliberately
     * omitted (persisted / boot-influencing; DuckUSB has always left it alone).
     */
    /**
     * OS plumbing that must never be lied to, by either the settings spoof or the property
     * spoof. com.android.mtp is uid 10091 on OP15 — an app uid, so the "uid < 10000" sparing
     * does not reach it. Telling the MTP service sys.usb.state=mtp / init.svc.adbd=stopped is
     * how USB ends up stuck on charge-only. com.oplus.ota is here for a different reason: its
     * EntryActivity.onCreateOptionsMenu removes the "Local install" overflow entry outright
     * when development_settings_enabled reads 0, so spoofing it hides a feature the user needs.
     */
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
        // sys.usb.ffs.ready is deliberately NOT spoofed. It is the USB function-filesystem
        // ready flag — machinery the USB stack acts on, not telemetry detectors read. Claiming
        // "0" contributed to killing the gadget outright (no MTP and no adb, charge-only).
        // Detectors look at sys.usb.state / sys.usb.config / init.svc.adbd; none need ffs.ready.
        "sys.usb.config" to "mtp",
        "sys.usb.state" to "mtp",
        "init.svc.adbd" to "stopped",
        // persist.sys.usb.config stays untouched: persisted and boot-influencing.
    )
}
