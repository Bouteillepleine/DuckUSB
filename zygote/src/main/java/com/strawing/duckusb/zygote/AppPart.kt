package com.strawing.duckusb.zygote

import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

object AppPart {

    @Volatile
    private var pendingModuleDir: String? = null

    fun preSpecialize(packageName: String?, moduleDir: String?) {
        if (packageName == null) return
        if (packageName == Config.PKG) return
        if (packageName in Config.SKIP_SPOOF_PROCESSES) return
        if (packageName in Config.SPARE_PACKAGES) return

        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused || !config.spoofProps) return
        if (!config.isTarget(packageName)) {
            Logx.v { "$packageName is not a target, nothing installed" }
            return
        }

        pendingModuleDir = moduleDir
        val ok = NativeProps.preload(moduleDir)
        Logx.v { "native library preloaded for $packageName: $ok" }
    }

    fun postSpecialize() {
        val dir = pendingModuleDir ?: return
        pendingModuleDir = null
        val ok = NativeProps.install(dir, Config.PROP_OVERRIDES)
        Logx.v { "property spoof installed=$ok" }
    }
}
