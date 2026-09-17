package com.strawing.duckusb.zygote

import android.provider.Settings
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

object AppPart {

    @Volatile
    private var pendingModuleDir: String? = null

    @Volatile
    private var settingsSpoofLive = false

    private val GETTERS = arrayOf("getInt", "getString", "getLong", "getFloat")

    private fun active(): Boolean {
        val config = ModuleConfig.config
        return !ModuleConfig.disabled && !config.paused
    }

    fun preSpecialize(packageName: String?, moduleDir: String?) {
        if (packageName == null) return
        if (packageName == Config.PKG) {
            announceToManager()
            return
        }
        if (packageName in Config.SKIP_SPOOF_PROCESSES) return
        if (packageName in Config.SPARE_PACKAGES) return

        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused) return
        if (!config.isTarget(packageName)) {
            Logx.v { "$packageName is not a target, nothing installed" }
            return
        }

        pendingModuleDir = moduleDir
        if (config.spoofProps) {
            val ok = NativeProps.preload(moduleDir)
            Logx.v { "native library preloaded for $packageName: $ok" }
        }
    }

    fun postSpecialize() {
        val dir = pendingModuleDir ?: return
        pendingModuleDir = null
        val config = ModuleConfig.config
        if (config.spoofProps) {
            val ok = NativeProps.install(dir, Config.PROP_OVERRIDES)
            Logx.v { "property spoof installed=$ok" }
        }
        if (config.spoofSettings) installSettingsSpoof()
    }

    private fun installSettingsSpoof() {
        var count = 0
        for (name in arrayOf("android.provider.Settings\$Global", "android.provider.Settings\$Secure")) {
            val clazz = XHook.findClass(name) ?: continue
            for (getter in GETTERS) count += XHook.hookAll(clazz, getter, 0, ::onSettingsGetter)
        }
        settingsSpoofLive = selfTest()
        Logx.i("client settings spoof: $count getters hooked, ${if (settingsSpoofLive) "live" else "DEAD"}")
    }

    private fun onSettingsGetter(f: Frame) {
        val key = f.args.firstOrNull { it is String } as? String
        if (key == null || key !in Config.SPOOF_KEYS || !active()) {
            f.proceed()
            return
        }
        when (f.returnType) {
            java.lang.Long.TYPE -> f.result = 0L
            java.lang.Float.TYPE -> f.result = 0f
            java.lang.Integer.TYPE -> f.result = 0
            String::class.java -> f.result = "0"
            else -> f.proceed()
        }
    }

    private fun announceToManager() {
        runCatching {
            System.setProperty(Config.LIVE_PROPERTY, Config.MODULE_VERSION)
        }
    }

    private fun selfTest(): Boolean = try {
        Settings.Global.getString(null, Config.SPOOF_KEYS.first()) == "0"
    } catch (_: Throwable) {
        false
    }
}
