package com.strawing.duckadb

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Info-only screen. All the work happens in the Xposed hook; this just tells the user
 * whether the module is live and reminds them to set the scope in LSPosed.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = isModuleActive()
        val status = if (active) "✅ Module active" else "⚠️ Not active — enable in LSPosed"
        val tv = TextView(this).apply {
            text = "DuckADB\n\n$status\n\n" +
                "Makes scoped apps read USB debugging as OFF while it stays on, and " +
                "hides the persistent 'USB debugging enabled' notification.\n\n" +
                "1. Enable DuckADB in LSPosed.\n" +
                "2. Scope → tick your detector apps (banking, Intune, games) for the spoof.\n" +
                "3. Scope → also tick 'System Framework' + 'System UI' to hide the notification.\n" +
                "4. Force-stop those apps / reboot.\n\n" +
                "Spoofed keys: adb_enabled, adb_wifi_enabled, development_settings_enabled."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        setContentView(tv)
    }

    /** LSPosed replaces the body of this method at runtime when the module is active. */
    private fun isModuleActive(): Boolean = false
}
