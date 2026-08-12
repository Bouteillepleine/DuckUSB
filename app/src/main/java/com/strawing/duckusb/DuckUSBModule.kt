package com.strawing.duckusb

import android.app.Notification
import android.content.res.Resources
import android.os.Binder
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * DuckUSB — two independent tricks:
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
class DuckUSBModule : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DuckUSB"

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

        /** First application UID; anything below (root/system/shell) is never lied to. */
        private const val FIRST_APP_UID = 10000

        /** SettingsProvider.call methods that fetch a value we may want to spoof. */
        private val GET_METHODS = setOf("GET_global", "GET_secure")

        /** Bundle keys used by the settings-provider call protocol. */
        private const val CALL_VALUE = "value"                 // Settings.NameValueTable.VALUE
        private const val CALL_GENERATION_INDEX = "_generation_index" // CALL_METHOD_GENERATION_INDEX_KEY

        /** Notification channels the ADB notifications live on (AOSP). */
        private val ADB_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")

        private const val MAIN_ACTIVITY = "com.strawing.duckusb.MainActivity"
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

    private fun spoofPropsOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_SPOOF_PROPS, true)
    }

    /** Framework mode defaults ON; the client-side per-app fallback defaults OFF. */
    private fun frameworkModeOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, true)
    }

    private fun clientFallbackOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, false)
    }

    /** Carries the binder caller UID from before→after of Transport.call (per thread). */
    private val txnCallerUid = ThreadLocal<Int>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val cl = lpparam.classLoader

        // B) Notification suppressor — only where the ADB notification can originate.
        if (pkg == "android" || pkg == "com.android.systemui") {
            installNotificationSuppressor(cl)
            if (pkg == "android") {
                installSystemServerSuppressor(cl)
                // A0) Framework mode (default): one server-side hook in system_server covers
                //     the Settings spoof for EVERY app — no per-app scope. Just scope System
                //     Framework. Gated per-caller by UID so shell/system still see the truth.
                if (frameworkModeOn()) installFrameworkSettingsSpoof(cl)
            }
        }

        // A) Settings spoof (per-app, client side) — now an OFF-by-default fallback for apps
        //    that dodge framework mode. Never in the core OS packages.
        if (pkg !in SKIP_SPOOF_PACKAGES) {
            if (clientFallbackOn()) installSettingsSpoof(cl)
            // A2) Property spoof — same scope. Closes the gap where a detector reads the
            //     raw sys.usb.* / init.svc.adbd props instead of the Settings provider.
            //     This is inherently per-app: property reads happen inside the target process.
            installSystemPropertiesSpoof(cl)
            installNativePropSpoof()
        }

        // Self-status: when injected into our own app, make isModuleActive() report true
        // so the UI can show a truthful "active" state (requires DuckUSB scoped to itself).
        if (pkg == Config.PKG) {
            try {
                XposedHelpers.findAndHookMethod(
                    "$MAIN_ACTIVITY", cl, "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: self-status hook failed: $t")
            }
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
            // Cheap key check FIRST — this runs on every Settings getter, so only touch
            // prefs (file I/O) for the few keys we actually spoof.
            val key = param.args.firstOrNull { it is String } as? String ?: return
            if (key !in SPOOF_KEYS) return
            if (!spoofOn()) return

            param.result = when ((param.method as? Method)?.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Integer.TYPE -> 0
                String::class.java -> "0"
                else -> 0
            }
        }
    }

    // ================= A0) FRAMEWORK-MODE SETTINGS SPOOF (system_server) =================

    /**
     * Server-side settings spoof: hook the provider transport that every ContentResolver.call
     * funnels through in system_server, so a single System-Framework hook lies to all apps at
     * once — catching even callers that use ContentResolver.call/reflection directly (which the
     * client-side getter hook misses). We deliberately run this ONLY in system_server and gate
     * per-caller by UID, so shell/system keep reading the real state and adb stays functional.
     */
    private fun installFrameworkSettingsSpoof(cl: ClassLoader) {
        val transport = XposedHelpers.findClassIfExists("android.content.ContentProvider\$Transport", cl)
        if (transport == null) {
            XposedBridge.log("$TAG: ContentProvider\$Transport not found; framework mode disabled")
            return
        }
        try {
            // hookAllMethods handles the per-Android-version signature drift of Transport.call
            // (AttributionSource form on S+, callingPkg forms on Q/R).
            XposedBridge.hookAllMethods(transport, "call", frameworkCallHook)
            XposedBridge.log("$TAG: framework settings spoof installed on Transport.call")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: framework settings spoof hook failed: $t")
        }
    }

    private val frameworkCallHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Capture the remote caller's UID while the binder identity is still set on entry.
            try { txnCallerUid.set(Binder.getCallingUid()) } catch (_: Throwable) {}
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val uid = txnCallerUid.get() ?: return
                // Only real apps get lied to; root/system/shell (uid<10000 in any user) see truth.
                if (uid % 100000 < FIRST_APP_UID) return

                // Cheap match FIRST (this fires on every provider call in system_server): the
                // setting key always immediately follows the GET_* method arg, whatever the
                // Transport.call signature is on this Android version.
                val args = param.args ?: return
                var key: String? = null
                for (i in 0 until args.size - 1) {
                    val a = args[i]
                    if (a is String && a in GET_METHODS) {
                        key = args[i + 1] as? String
                        break
                    }
                }
                if (key == null || key !in SPOOF_KEYS) return
                if (!spoofOn()) return

                val bundle = param.result as? Bundle ?: return
                if (bundle.containsKey(CALL_VALUE)) {
                    bundle.putString(CALL_VALUE, "0")
                    // Make the client NameValueCache treat this as uncacheable (-1) so our hook
                    // runs on every read instead of a stale real value being served from cache.
                    bundle.putInt(CALL_GENERATION_INDEX, -1)
                }
            } catch (_: Throwable) {
            } finally {
                txnCallerUid.remove()
            }
        }
    }

    // ===================== A2) PROPERTY SPOOF (Java layer) =====================

    /**
     * Hook android.os.SystemProperties.native_get* so apps using SystemProperties.get()/
     * getInt()/getBoolean() see our overrides. Checked live against the toggle.
     */
    private fun installSystemPropertiesSpoof(cl: ClassLoader) {
        val sp = XposedHelpers.findClassIfExists("android.os.SystemProperties", cl) ?: return
        for (m in arrayOf("native_get", "native_get_int", "native_get_long", "native_get_boolean")) {
            try {
                XposedBridge.hookAllMethods(sp, m, systemPropertiesHook)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: SystemProperties.$m hook failed: $t")
            }
        }
    }

    private val systemPropertiesHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Cheap key check FIRST — SystemProperties.get() is extremely hot, so only touch
            // prefs (file I/O) when the key is one of ours.
            val key = param.args.firstOrNull() as? String ?: return
            val value = Config.PROP_OVERRIDES[key] ?: return
            if (!spoofPropsOn()) return
            // native_get returns String; the int/long/boolean variants need a parseable value.
            // Our USB props ("mtp") aren't numeric, so only substitute when it fits the type.
            param.result = when ((param.method as? Method)?.returnType) {
                String::class.java -> value
                java.lang.Integer.TYPE -> value.toIntOrNull() ?: return
                java.lang.Long.TYPE -> value.toLongOrNull() ?: return
                java.lang.Boolean.TYPE -> when (value) { "1", "true" -> true; "0", "false" -> false; else -> return }
                else -> return
            }
        }
    }

    // ===================== A2) PROPERTY SPOOF (native libc layer) =====================

    /**
     * Install the libc __system_property_get / __system_property_find hooks (libduckusb.so).
     * Installed once per process; an empty map (spoof off) makes the hooks pass through.
     */
    private fun installNativePropSpoof() {
        try {
            val overrides = if (spoofPropsOn()) Config.PROP_OVERRIDES else emptyMap()
            NativeProps.install(overrides)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: native prop hook install failed: $t")
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
