package com.strawing.duckusb

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.strawing.duckusb.service.Bridge
import com.strawing.duckusb.service.DuckService
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
 *  A2) PROPERTY SPOOF (automatic). In the same scoped app processes, sys.usb.* and
 *     init.svc.adbd are spoofed too, via SystemProperties and a native libc hook. Not
 *     user-switchable: scoping an app already states the intent, and it must never reach a
 *     system process — see the UID guard in handleLoadPackage.
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

        /**
         * Core *processes* we never spoof inside. Guarding on package name alone is not enough:
         * handleLoadPackage fires once per package HOSTED in a process, so system_server reports
         * android, com.android.providers.settings, com.android.location.fused,
         * com.android.server.telecom, com.oplus.appplatform, com.oplus.athena — all uid 1000 —
         * and OPlus keyguard plugins load into com.android.systemui under their own names at an
         * app uid (10178). Only the process name catches both families.
         */
        private val SKIP_SPOOF_PROCESSES = setOf(
            "android",
            "system",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.phone",
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

        /** The concrete settings provider we hook (never the generic ContentProvider$Transport). */
        private const val SETTINGS_PROVIDER = "com.android.providers.settings.SettingsProvider"

        /** Authority is the portable identity; the class name is only a fallback. */
        private const val SETTINGS_AUTHORITY = "settings"

        /** Bundle keys used by the settings-provider call protocol. */
        private const val CALL_VALUE = "value"                 // Settings.NameValueTable.VALUE
        private const val CALL_GENERATION_INDEX = "_generation_index" // CALL_METHOD_GENERATION_INDEX_KEY

        /** Notification channels the ADB notifications live on (AOSP). */
        private val ADB_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")

        private const val MAIN_ACTIVITY = "com.strawing.duckusb.MainActivity"
    }

    /** World-readable prefs written by the UI; re-read live so toggles apply without reboot. */
    private val prefs = XSharedPreferences(Config.PKG, Config.PREFS_NAME).apply { makeWorldReadable() }

    /** Verbose per-injection logging; off unless troubleshooting. */
    private fun verboseOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_VERBOSE_LOG, false)
    }

    /** Master pause. Checked by every hook body; the service copy wins when it is live. */
    private fun pausedOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_PAUSED, false)
    }

    private fun spoofOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_SPOOF, true)
    }

    private fun hideNotifOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_HIDE_NOTIF, true)
    }


    /**
     * Framework mode is EXPERIMENTAL and OFF by default: on some ROMs (verified OP15 /
     * Android 16) hooking ContentProvider$Transport.call in system_server bootloops the
     * device. The per-app client hook is the safe default.
     */
    private fun frameworkModeOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false)
    }

    private fun clientFallbackOn(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, true)
    }

    /** Carries the binder caller UID from before→after of SettingsProvider.call (per thread). */
    private val txnCallerUid = ThreadLocal<Int>()

    /** One-shot guard so we hook SettingsProvider.call only once. */
    private var sSettingsCallHooked = false

    /** The system_server-side service; null until SettingsProvider attaches (or not in system_server). */
    @Volatile private var service: DuckService? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val cl = lpparam.classLoader

        // DIAGNOSTIC: the exact package/uid each injection reports. This is what decides whether
        // SKIP_SPOOF_PACKAGES (a package-name list) actually covers system_server — the open
        // question behind "were properties ever installing there?".
        val myUid = android.os.Process.myUid()
        if (verboseOn()) {
            XposedBridge.log("$TAG: loaded pkg=$pkg proc=${lpparam.processName} uid=$myUid " +
                "skipList=${pkg in SKIP_SPOOF_PACKAGES} systemUid=${myUid % 100000 < FIRST_APP_UID}")
        }

        // B) Notification suppressor — only where the ADB notification can originate.
        // Installation follows the toggle: previously the hooks were planted unconditionally and
        // only their bodies checked hideNotifOn(), so "framework mode only" still left two hooks
        // on NotificationManagerService inside system_server. Enabling one feature should place
        // exactly that feature's hooks and nothing else.
        if (pkg == "android" || pkg == "com.android.systemui") {
            if (hideNotifOn()) installNotificationSuppressor(cl)
            if (pkg == "android") {
                if (hideNotifOn()) installSystemServerSuppressor(cl)
                // A0) Framework mode (default): one server-side hook in system_server covers
                //     the Settings spoof for EVERY app — no per-app scope. Just scope System
                //     Framework. Gated per-caller by UID so shell/system still see the truth.
                if (frameworkModeOn()) installFrameworkSettingsSpoof(cl)
            }
        }

        // A) Settings spoof (per-app, client side) — now an OFF-by-default fallback for apps
        //    that dodge framework mode. Never in the core OS packages.
        //
        // Guard on UID, not just package name. SKIP_SPOOF_PACKAGES predates the "system" scope
        // and only matches what LSPosed reports as the package, which is not dependable for
        // system_server. That mattered: the property spoof claims sys.usb.ffs.ready=0,
        // sys.usb.config=mtp and init.svc.adbd=stopped — the USB stack's actual control surface,
        // not detection cosmetics — so reaching a system process with it kills the gadget
        // outright (no MTP *and* no adb, charge-only). Any uid < 10000 is OS, never spoof it.
        // Three independent guards, because each alone has a proven gap:
        //   uid       — catches every system_server injection (all report uid 1000)
        //   process   — catches OPlus keyguard plugins riding com.android.systemui at uid 10178
        //   package   — the original list, kept for the plain per-app cases
        val isSystemProcess = android.os.Process.myUid() % 100000 < FIRST_APP_UID
        val inCoreProcess = (lpparam.processName ?: pkg) in SKIP_SPOOF_PROCESSES
        // ...and never in DuckUSB itself. LSPosed loads a module into its own process whether
        // or not you scope it, so without this the UI spoofs itself: the readings card, which
        // exists to report the REAL device state, reads its own lie and shows adb_enabled=0 /
        // sys.usb.state=mtp on every device regardless of the truth. The native half is worse
        // than dishonest — the libc inline hook is what SIGILLs the UI on some ROMs (issue #2).
        val isSelf = pkg == Config.PKG || (lpparam.processName ?: pkg).substringBefore(':') == Config.PKG
        if (pkg !in SKIP_SPOOF_PACKAGES && !isSystemProcess && !inCoreProcess && !isSelf) {
            if (clientFallbackOn()) installSettingsSpoof(cl)
            // A2) Property spoof — same scope. Closes the gap where a detector reads the
            //     raw sys.usb.* / init.svc.adbd props instead of the Settings provider.
            //     This is inherently per-app: property reads happen inside the target process.
            // Never spoof properties to the OS's own file-transfer plumbing, even if the user
            // scopes com.android.mtp directly. The uid guard above cannot catch it: mtp runs at
            // an app uid (10091 on OP15).
            if (pkg in Config.SPARE_PACKAGES) {
                if (verboseOn()) {
                    XposedBridge.log("$TAG: property spoof SPARED for $pkg (OS file-transfer plumbing)")
                }
            } else {
                installSystemPropertiesSpoof(cl)
                installNativePropSpoof()
            }
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
            if (pausedOn() || !spoofOn()) return

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
     * Server-side settings spoof, done the SAFE way (AdbHide-style): hook
     * ContentProvider.attachInfo, wait for the concrete SettingsProvider to attach, then hook
     * ONLY its call(). We deliberately do NOT hook the generic ContentProvider$Transport.call —
     * that fires on every provider IPC in system_server on the hottest boot path and BOOTLOOPS
     * some ROMs (verified OP15 / Android 16). Gated per-caller by UID so shell/system keep the
     * real state and adb stays functional.
     */
    private fun installFrameworkSettingsSpoof(cl: ClassLoader) {
        val cp = XposedHelpers.findClassIfExists("android.content.ContentProvider", cl)
        if (cp == null) {
            XposedBridge.log("$TAG: ContentProvider not found; framework mode disabled")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(cp, "attachInfo",
                "android.content.Context", "android.content.pm.ProviderInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val provider = param.thisObject ?: return
                            // Match on the declared authority, not the class name. A ROM that
                            // subclasses or renames SettingsProvider would fail an equality check
                            // and the hook would silently never install — the same look-enabled /
                            // do-nothing failure that is so hard to diagnose from the UI.
                            // Authority can be a ";"-separated list.
                            val info = param.args.getOrNull(1) as? android.content.pm.ProviderInfo
                            val isSettings =
                                info?.authority?.split(";")?.any { it.trim() == SETTINGS_AUTHORITY } == true ||
                                provider.javaClass.name == SETTINGS_PROVIDER
                            if (isSettings) {
                                // attachInfo(Context, ProviderInfo) hands us the system Context
                                // directly — no reflection on a private mContext field needed.
                                (param.args.getOrNull(0) as? Context)?.let { ctx ->
                                    if (service == null) service = DuckService(ctx).apply {
                                        spoofSettings = spoofOn()
                                        hideNotif = hideNotifOn()
                                    }
                                }
                                hookSettingsProviderCall(provider.javaClass)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG: framework mode armed — hooked ContentProvider.attachInfo (awaiting SettingsProvider)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: framework settings spoof (attachInfo) failed: $t")
        }
    }

    /** Reflectively hook only SettingsProvider.call (>=3 params). One-shot. */
    private fun hookSettingsProviderCall(spClass: Class<*>) {
        if (sSettingsCallHooked) return
        var count = 0
        for (m in spClass.declaredMethods) {
            try {
                if (m.name == "call" && m.parameterTypes.size >= 3) {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, frameworkCallHook)
                    count++
                }
            } catch (_: Throwable) {}
        }
        if (count > 0) sSettingsCallHooked = true
        service?.hookCount = count
        service?.installedAtRealtimeMs = SystemClock.elapsedRealtime()
        XposedBridge.log("$TAG: framework settings spoof installed: SettingsProvider.call hooks=$count")
    }

    private val frameworkCallHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Capture the remote caller's UID while the binder identity is still set on entry.
            val uid = try { Binder.getCallingUid() } catch (_: Throwable) { return }
            txnCallerUid.set(uid)

            // Binder bridge: hand the service to our own app, riding this same hook so the
            // framework half adds no extra hook surface. On any mismatch we fall through
            // untouched, so the call is indistinguishable from stock (unknown method -> null).
            try {
                val svc = service ?: return
                val args = param.args ?: return
                if (args.size < 2) return
                if (args[0] != Bridge.METHOD || args[1] != Bridge.ARG) return
                if (uid % 100000 != svc.callerAppId) return
                param.result = Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) }
            } catch (_: Throwable) {}
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val uid = txnCallerUid.get() ?: return
                // Only real apps get lied to; root/system/shell (uid<10000 in any user) see truth.
                if (uid % 100000 < FIRST_APP_UID) return

                // The setting key always immediately follows the GET_* method arg, whatever the
                // SettingsProvider.call signature is on this Android version.
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
                // Never lie to the OS's own file-transfer plumbing: com.android.mtp and friends
                // sit at app uids, so the uid<10000 rule above does not cover them, and spoofing
                // them at boot leaves USB stuck on charge-only.
                if (service?.isSpared(uid) == true) return
                if (service?.paused ?: pausedOn()) return
                // Live config from the service when it's up (a volatile read), else the
                // XSharedPreferences cold-start path. Avoids prefs file I/O per read.
                if (!(service?.spoofSettings ?: spoofOn())) return

                val bundle = param.result as? Bundle ?: return
                if (bundle.containsKey(CALL_VALUE)) {
                    bundle.putString(CALL_VALUE, "0")
                    service?.note(uid, key)
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
            if (pausedOn()) return
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
            // The native hook installs once per process and cannot be re-gated live, so pause
            // is applied at load: an empty map makes the libc hooks pass through.
            NativeProps.install(if (pausedOn()) emptyMap() else Config.PROP_OVERRIDES)
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
            if (pausedOn() || !hideNotifOn()) return
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
