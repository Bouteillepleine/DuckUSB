package com.strawing.duckadb

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * DuckADB — makes scoped apps read USB debugging (and, optionally, Developer Options)
 * as OFF while it stays really ON on the device.
 *
 * How detection works: apps read the settings provider, not any real "adb" state.
 *   Settings.Global.getInt(cr, "adb_enabled")            -> 1 when USB debugging is on
 *   Settings.Global.getInt(cr, "adb_wifi_enabled")       -> 1 when wireless debugging is on
 *   Settings.Global.getInt(cr, "development_settings_enabled") -> 1 when Dev Options is on
 *   Settings.Secure.getInt(cr, "adb_enabled")            -> legacy (pre-4.2) location
 *
 * We hook the static getters on android.provider.Settings.Global / Settings.Secure and,
 * when the requested key is one of ours, force the answer to the "off" value. Everything
 * else passes through untouched.
 *
 * Scope is chosen in the LSPosed manager (assign DuckADB to the detector apps you care
 * about — banking, Intune, games…). We still hard-skip core OS packages below so that
 * accidentally scoping the framework never lies to adbd / the Settings toggle itself.
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

        /** Core packages we never touch, even if the user scopes them by mistake. */
        private val SKIP_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.shell",
            "com.android.phone"
        )

        /** Static getters that take a String key we can inspect. */
        private val GETTERS = arrayOf("getInt", "getString", "getLong", "getFloat")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName in SKIP_PACKAGES) return

        val cl = lpparam.classLoader
        for (clazz in arrayOf("android.provider.Settings\$Global", "android.provider.Settings\$Secure")) {
            val settings = XposedHelpers.findClassIfExists(clazz, cl) ?: continue
            for (getter in GETTERS) {
                try {
                    XposedBridge.hookAllMethods(settings, getter, hook)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: hook failed $clazz.$getter: $t")
                }
            }
        }
        XposedBridge.log("$TAG: armed in ${lpparam.packageName}")
    }

    private val hook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // The setting key is the first String argument (getInt/getString/… (cr, key[, def])).
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
}
