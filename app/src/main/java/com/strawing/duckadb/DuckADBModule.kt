package com.strawing.duckadb

import android.app.Notification
import android.content.res.Resources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * DuckADB — two independent tricks:
 *
 *  A) SETTINGS SPOOF (per detector app). Makes scoped apps read USB debugging,
 *     wireless debugging and Developer Options as OFF while they stay really ON,
 *     by hooking the static getters on Settings.Global / Settings.Secure. Runs in
 *     every scoped app EXCEPT the core OS packages (so adbd / the Settings toggle
 *     itself are never lied to).
 *
 *  B) NOTIFICATION SUPPRESSOR (System Framework / System UI). Hides the persistent
 *     "USB debugging enabled / Débogage USB activé" notification. That notification
 *     is posted by system_server, so this half deliberately DOES run in the "android"
 *     and "com.android.systemui" processes — the ones the spoof half skips.
 *
 * Scope both halves in LSPosed:
 *   - tick your detector apps (banking, Intune, games…) for the spoof, and
 *   - tick "System Framework" + "System UI" to kill the notification.
 */
class DuckADBModule : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DuckADB"

        /** Setting keys we force to the "disabled" value. */
        private val SPOOF_KEYS = setOf(
            "adb_enabled",                  // Settings.Global.ADB_ENABLED — USB debugging
            "adb_wifi_enabled",             // wireless / ADB-over-Wi-Fi
            "development_settings_enabled"  // Developer Options master toggle
        )

        /** Core packages the SETTINGS SPOOF never touches (the notif suppressor still may). */
        private val SKIP_SPOOF_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.shell",
            "com.android.phone"
        )

        /** Static getters that take a String key we can inspect. */
        private val GETTERS = arrayOf("getInt", "getString", "getLong", "getFloat")

        /** Notification channels the ADB notifications live on (AOSP). */
        private val ADB_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")
    }

    /** World-readable prefs written by the UI; re-read live so toggles apply without reboot. */
    private val prefs = XSharedPreferences(Config.PKG, Config.PREFS_NAME).apply { makeWorldReadable() }

    private fun spoofOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_SPOOF, true)
    }

    private fun hideNotifOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_HIDE_NOTIF, true)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val cl = lpparam.classLoader

        // B) Notification suppressor — only where the ADB notification can originate.
        if (pkg == "android" || pkg == "com.android.systemui") {
            installNotificationSuppressor(cl)
            if (pkg == "android") installSystemServerSuppressor(cl)
        }

        // A) Settings spoof — everywhere except the core OS packages.
        if (pkg !in SKIP_SPOOF_PACKAGES) {
            installSettingsSpoof(cl)
        }
    }

    // ============================ A) SETTINGS SPOOF ============================

    private fun installSettingsSpoof(cl: ClassLoader) {
        for (clazz in arrayOf("android.provider.Settings\$Global", "android.provider.Settings\$Secure")) {
            val settings = XposedHelpers.findClassIfExists(clazz, cl) ?: continue
            for (getter in GETTERS) {
                try {
                    XposedBridge.hookAllMethods(settings, getter, settingsHook)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: settings hook failed $clazz.$getter: $t")
                }
            }
        }
    }

    private val settingsHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!spoofOn()) return
            // The key is the first String argument (getInt/getString/… (cr, key[, def])).
            val key = param.args.firstOrNull { it is String } as? String ?: return
            if (key !in SPOOF_KEYS) return

            param.result = when ((param.method as? Method)?.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Integer.TYPE -> 0
                String::class.java -> "0"
                else -> 0
            }
        }
    }

    // ========================= B) NOTIFICATION SUPPRESSOR =========================

    /** Hook the public wrapper UsbDeviceManager uses: NotificationManager.notify* . */
    private fun installNotificationSuppressor(cl: ClassLoader) {
        val nm = XposedHelpers.findClassIfExists("android.app.NotificationManager", cl) ?: return
        for (m in arrayOf("notify", "notifyAsUser")) {
            try {
                XposedBridge.hookAllMethods(nm, m, notifHook)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: NotificationManager.$m hook failed: $t")
            }
        }
    }

    /** Deeper chokepoint inside system_server so we catch it whatever path posts it. */
    private fun installSystemServerSuppressor(cl: ClassLoader) {
        val nms = XposedHelpers.findClassIfExists(
            "com.android.server.notification.NotificationManagerService", cl
        ) ?: return
        try {
            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal", notifHook)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: enqueueNotificationInternal hook failed: $t")
        }
    }

    private val notifHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!hideNotifOn()) return
            val n = param.args.firstOrNull { it is Notification } as? Notification ?: return
            if (isAdbNotification(n)) {
                // Swallow the post: original never runs, nothing is shown.
                param.result = null
            }
        }
    }

    private fun isAdbNotification(n: Notification): Boolean {
        // 1) By channel (fast path).
        try {
            if (n.channelId in ADB_CHANNELS) return true
        } catch (_: Throwable) {}
        // 2) By (localized) title — resolved live from framework resources, so it
        //    matches whatever wording the ROM uses ("Débogage USB activé", etc.).
        try {
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (title != null && title in adbTitles) return true
        } catch (_: Throwable) {}
        return false
    }

    /** The ROM's own strings for the USB/Wi-Fi debugging notification titles. */
    private val adbTitles: Set<String> by lazy {
        val res = Resources.getSystem()
        arrayOf("adb_active_notification_title", "adb_wifi_active_notification_title")
            .mapNotNull { name ->
                val id = res.getIdentifier(name, "string", "android")
                if (id != 0) runCatching { res.getString(id) }.getOrNull() else null
            }
            .toSet()
    }
}
