package com.strawing.duckusb

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.strawing.duckusb.common.CursorSpoof
import com.strawing.duckusb.service.Bridge
import com.strawing.duckusb.service.DuckService
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
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
 *     system process — see the UID guard in [onPackageReady].
 *
 *  B) NOTIFICATION SUPPRESSOR (System Framework / System UI). Hides the persistent
 *     "USB debugging enabled / Débogage USB activé" notification. That notification
 *     is posted by system_server, so this half deliberately DOES run in system_server
 *     and com.android.systemui — the ones the spoof half skips.
 *
 * Scope both halves in LSPosed:
 *   - tick your detector apps (banking, Intune, games…) for the spoof, and
 *   - tick "System Framework" + "System UI" to kill the notification.
 *
 * ── libxposed (modern API 101) ─────────────────────────────────────────────────────────
 * The legacy `handleLoadPackage` fired once per package hosted in a process, which meant this
 * class had to work out from a package name whether it was standing in system_server. The
 * modern API splits that apart: [onSystemServerStarting] IS the system_server entry and
 * [onPackageReady] is the per-app one, so the "is this really system_server?" guesswork is
 * gone. The UID and process guards are kept anyway — they defend against a different thing
 * (OEM plugins riding an app uid), which the split does not address.
 */
class DuckUSBModule : XposedModule() {

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
         * OEM plugins load into com.android.systemui under their own package names at an app
         * uid (10178 on OPlus), which no package-name list catches.
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
    }

    // ------------------------------------------------------------------ framework plumbing

    /**
     * Remote preferences, written by the UI through the framework service.
     *
     * This replaces XSharedPreferences and its world-readable file. It is not merely a
     * different spelling: the old file had to be readable by every hooked process including
     * system_server, and a module that "looks enabled but does nothing" is exactly what an
     * unreadable prefs file produces. The framework now brokers the value, and it pushes
     * updates rather than making every hook body re-read a file — which is why there is no
     * `reload()` anywhere below.
     *
     * Lazy because [getRemotePreferences] requires the framework to be attached, which happens
     * after construction.
     */
    private val prefs: SharedPreferences by lazy { getRemotePreferences(Config.PREFS_NAME) }

    /** Process name of the process this module instance lives in; set in [onModuleLoaded]. */
    private var processName: String = ""

    /** Verbose per-injection logging; off unless troubleshooting. */
    private fun verboseOn() = prefs.getBoolean(Config.KEY_VERBOSE_LOG, false)

    /** Master pause. Checked by every hook body; the service copy wins when it is live. */
    private fun pausedOn() = prefs.getBoolean(Config.KEY_PAUSED, false)

    private fun spoofOn() = prefs.getBoolean(Config.KEY_SPOOF, true)

    private fun hideNotifOn() = prefs.getBoolean(Config.KEY_HIDE_NOTIF, true)

    /**
     * Framework mode is EXPERIMENTAL and OFF by default: on some ROMs (verified OP15 /
     * Android 16) hooking ContentProvider$Transport.call in system_server bootloops the
     * device. The per-app client hook is the safe default.
     */


    private fun logI(msg: String) = log(Log.INFO, TAG, msg)

    private fun logE(msg: String, t: Throwable? = null) =
        if (t == null) log(Log.ERROR, TAG, msg) else log(Log.ERROR, TAG, msg, t)

    /** `XposedHelpers.findClassIfExists` has no modern counterpart; this is what it did. */
    private fun findClass(name: String, cl: ClassLoader): Class<*>? =
        try { Class.forName(name, false, cl) } catch (_: Throwable) { null }

    /**
     * Stand-in for `XposedBridge.hookAllMethods`: hook every declared overload of [name].
     * Returns how many were hooked, which is the only honest way to tell "the class was there
     * but the method was not" from "it worked".
     */
    private fun hookAll(clazz: Class<*>, name: String, hooker: XposedInterface.Hooker): Int {
        var count = 0
        for (m in clazz.declaredMethods) {
            if (m.name != name) continue
            try {
                hook(m).intercept(hooker)
                count++
            } catch (t: Throwable) {
                logE("hook failed ${clazz.name}.$name", t)
            }
        }
        return count
    }

    // ------------------------------------------------------------------ entry points

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (verboseOn()) {
            logI("loaded proc=${param.processName} systemServer=${param.isSystemServer} " +
                "uid=${android.os.Process.myUid()} framework=$frameworkName($frameworkVersionCode) API $apiVersion")
        }
    }

    /**
     * system_server. Under the legacy API this was `pkg == "android"`, reached through a
     * package-name comparison that was never dependable; the framework states it outright now.
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val cl = param.classLoader
        // Installation follows the toggle: hooks are placed only for the feature that asked
        // for them, so "framework mode only" does not also leave two hooks on
        // NotificationManagerService.
        if (hideNotifOn()) {
            installNotificationSuppressor(cl)
            installSystemServerSuppressor(cl)
        }
        // A0) Framework mode (default off): one server-side hook covers the Settings spoof for
        //     EVERY app — no per-app scope. Gated per-caller by UID so shell/system still see
        //     the truth.
        installFrameworkSettingsSpoof(cl)
    }

    /**
     * Every non-system_server package. [PackageReadyParam] rather than `onPackageLoaded` on
     * purpose: it hands over the app's real classloader (the one a custom AppComponentFactory
     * may have swapped in), and it is not gated on API 29 the way `onPackageLoaded` is, so
     * this keeps working down to the module's minSdk.
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        val cl = param.classLoader
        val myUid = android.os.Process.myUid()

        if (verboseOn()) {
            logI("package pkg=$pkg proc=$processName uid=$myUid " +
                "skipList=${pkg in SKIP_SPOOF_PACKAGES} systemUid=${myUid % 100000 < FIRST_APP_UID}")
        }

        // B) Notification suppressor — System UI is the other place the ADB notification can
        //    surface. (system_server is handled in onSystemServerStarting.)
        if (pkg == "com.android.systemui" && hideNotifOn()) installNotificationSuppressor(cl)

    }

    // ============================ A) SETTINGS SPOOF ============================

    // ================= FRAMEWORK-MODE SETTINGS SPOOF (system_server) =================

    /** The system_server-side service; null until SettingsProvider attaches. */
    @Volatile
    private var service: DuckService? = null

    /** One-shot guard so we hook the provider only once. */
    private var settingsCallHooked = false

    private fun installFrameworkSettingsSpoof(cl: ClassLoader) {
        val cp = findClass("android.content.ContentProvider", cl)
        if (cp == null) {
            logE("ContentProvider not found; framework mode disabled")
            return
        }
        val attachInfo = try {
            cp.getDeclaredMethod("attachInfo",
                Context::class.java, android.content.pm.ProviderInfo::class.java)
        } catch (t: Throwable) {
            logE("ContentProvider.attachInfo not found; framework mode disabled", t)
            return
        }
        try {
            hook(attachInfo).intercept { chain ->
                val result = chain.proceed()
                try {
                    val provider = chain.thisObject
                    // Match on the declared authority, not the class name. A ROM that
                    // subclasses or renames SettingsProvider would fail an equality check and
                    // the hook would silently never install — the same look-enabled /
                    // do-nothing failure that is so hard to diagnose from the UI.
                    // Authority can be a ";"-separated list.
                    val info = chain.args.getOrNull(1) as? android.content.pm.ProviderInfo
                    val isSettings =
                        info?.authority?.split(";")?.any { it.trim() == SETTINGS_AUTHORITY } == true ||
                        provider?.javaClass?.name == SETTINGS_PROVIDER
                    if (isSettings && provider != null) {
                        // attachInfo(Context, ProviderInfo) hands us the system Context
                        // directly — no reflection on a private mContext field needed.
                        (chain.args.getOrNull(0) as? Context)?.let { ctx ->
                            if (service == null) service = DuckService(ctx).apply {
                                spoofSettings = spoofOn()
                                hideNotif = hideNotifOn()
                            }
                        }
                        hookSettingsProviderCall(provider.javaClass)
                    }
                } catch (_: Throwable) {}
                result
            }
            logI("framework mode armed — hooked ContentProvider.attachInfo (awaiting SettingsProvider)")
        } catch (t: Throwable) {
            logE("framework settings spoof (attachInfo) failed", t)
        }
    }

    /** Reflectively hook SettingsProvider.call and .query (>=3 params). One-shot. */
    private fun hookSettingsProviderCall(spClass: Class<*>) {
        if (settingsCallHooked) return
        var count = 0
        for (m in spClass.declaredMethods) {
            try {
                if (m.name == "call" && m.parameterTypes.size >= 3) {
                    hook(m).intercept(frameworkCallHooker)
                    count++
                } else if (m.name == "query" && m.parameterTypes.size >= 3) {
                    hook(m).intercept(frameworkQueryHooker)
                    count++
                }
            } catch (_: Throwable) {}
        }
        if (count > 0) settingsCallHooked = true
        service?.hookCount = count
        service?.installedAtRealtimeMs = SystemClock.elapsedRealtime()
        logI("framework settings spoof installed: SettingsProvider.call hooks=$count")
    }

    private val frameworkCallHooker = XposedInterface.Hooker { chain ->
        // Capture the remote caller's UID while the binder identity is still set on entry.
        // The legacy version needed a ThreadLocal to carry this from beforeHookedMethod to
        // afterHookedMethod; one interceptor spans both, so the ThreadLocal is gone.
        val uid = try { Binder.getCallingUid() } catch (_: Throwable) { null }

        // Binder bridge: hand the service to our own app, riding this same hook so the
        // framework half adds no extra hook surface. On any mismatch we fall through
        // untouched, so the call is indistinguishable from stock (unknown method -> null).
        val bridged: Bundle? = try {
            val svc = service
            val args = chain.args
            if (svc != null && uid != null && args.size >= 2 &&
                args[0] == Bridge.METHOD && args[1] == Bridge.ARG &&
                uid % 100000 == svc.callerAppId
            ) Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) } else null
        } catch (_: Throwable) { null }

        if (bridged != null) {
            bridged
        } else {
            val result = chain.proceed()
            try {
                spoofSettingsCall(uid, chain.args, result)
            } catch (_: Throwable) {}
            result
        }
    }

    /**
     * The cursor path. A detector that queries the settings table directly instead of calling
     * the getter would otherwise read the truth and catch the lie told on the call path.
     */
    private val frameworkQueryHooker = XposedInterface.Hooker { chain ->
        val uid = try { Binder.getCallingUid() } catch (_: Throwable) { null }
        val result = chain.proceed()
        var replaced: Any? = null
        try {
            if (uid != null && coverQueryOn() && spoofingForCaller(uid)) {
                val cursor = result as? Cursor
                if (cursor != null) {
                    val uri = chain.args.firstOrNull { it is Uri } as? Uri
                    replaced = CursorSpoof.rewrite(cursor, uri?.lastPathSegment)
                    if (replaced != null) service?.note(uid, "query")
                }
            }
        } catch (_: Throwable) {}
        replaced ?: result
    }

    private fun coverQueryOn() = prefs.getBoolean(Config.KEY_COVER_QUERY, true)

    /** Shared gate for both framework paths: real apps only, never the OS, never our own UI. */
    private fun spoofingForCaller(uid: Int): Boolean {
        if (uid % 100000 < FIRST_APP_UID) return false
        service?.callerAppId?.let { if (it >= 0 && uid % 100000 == it) return false }
        if (service?.isSpared(uid) == true) return false
        if (service?.paused ?: pausedOn()) return false
        return service?.spoofSettings ?: spoofOn()
    }

    /** The "after" half of the framework call hook: rewrite the value the provider returned. */
    private fun spoofSettingsCall(uid: Int?, args: List<Any?>, result: Any?) {
        if (uid == null) return
        // Only real apps get lied to; root/system/shell (uid<10000 in any user) see truth.
        if (uid % 100000 < FIRST_APP_UID) return

        // ...and never our own UI. The readings card exists to report the REAL device state,
        // and the client-side self-guard cannot stop a lie told inside system_server: with
        // framework mode on, DuckUSB read its own adb_enabled as 0 on a device where it is 1.
        // Spoofing ourselves buys nothing — detectors are other apps — and costs the one screen
        // meant to tell the user the truth. appId -1 means unknown, so spoof.
        service?.callerAppId?.let { if (it >= 0 && uid % 100000 == it) return }

        // The setting key always immediately follows the GET_* method arg, whatever the
        // SettingsProvider.call signature is on this Android version.
        var key: String? = null
        for (i in 0 until args.size - 1) {
            val a = args[i]
            if (a is String && a in GET_METHODS) {
                key = args[i + 1] as? String
                break
            }
        }
        if (key == null || key !in SPOOF_KEYS) return
        // Never lie to the OS's own file-transfer plumbing: com.android.mtp and friends sit at
        // app uids, so the uid<10000 rule above does not cover them, and spoofing them at boot
        // leaves USB stuck on charge-only.
        if (service?.isSpared(uid) == true) return
        if (service?.paused ?: pausedOn()) return
        // Live config from the service when it's up (a volatile read), else the prefs path.
        if (!(service?.spoofSettings ?: spoofOn())) return

        val bundle = result as? Bundle ?: return
        if (bundle.containsKey(CALL_VALUE)) {
            bundle.putString(CALL_VALUE, "0")
            service?.note(uid, key)
            // Make the client NameValueCache treat this as uncacheable (-1) so our hook runs on
            // every read instead of a stale real value being served from cache.
            bundle.putInt(CALL_GENERATION_INDEX, -1)
        }
    }

    // ========================= B) NOTIFICATION SUPPRESSOR =========================

    /** Hook the public wrapper UsbDeviceManager uses: NotificationManager.notify* . */
    private fun installNotificationSuppressor(cl: ClassLoader) {
        val nm = findClass("android.app.NotificationManager", cl) ?: return
        for (m in arrayOf("notify", "notifyAsUser")) hookAll(nm, m, notifHooker)
    }

    /** Deeper chokepoint inside system_server so we catch it whatever path posts it. */
    private fun installSystemServerSuppressor(cl: ClassLoader) {
        val nms = findClass("com.android.server.notification.NotificationManagerService", cl) ?: return
        hookAll(nms, "enqueueNotificationInternal", notifHooker)
    }

    private val notifHooker = XposedInterface.Hooker { chain ->
        val n = chain.args.firstOrNull { it is Notification } as? Notification
        if (n != null && !pausedOn() && hideNotifOn() && isAdbNotification(n)) {
            // Swallow the post: the original never runs, nothing is shown. These are void
            // methods, so the null we return here is discarded.
            null
        } else {
            chain.proceed()
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
