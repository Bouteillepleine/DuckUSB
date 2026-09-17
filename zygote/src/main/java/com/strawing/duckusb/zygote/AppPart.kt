package com.strawing.duckusb.zygote

import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

object AppPart {

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

        val ok = NativeProps.install(moduleDir, Config.PROP_OVERRIDES)
        Logx.v { "property spoof for $packageName installed=$ok" }
    }
}
