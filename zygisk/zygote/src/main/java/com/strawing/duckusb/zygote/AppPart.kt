package com.strawing.duckusb.zygote

import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Native
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import com.strawing.duckusb.zygote.util.NativeLib

object AppPart {

    fun preSpecialize(packageName: String?) {
        if (packageName == Config.PKG) {
            runCatching { System.setProperty(Config.LIVE_PROPERTY, Config.MODULE_VERSION) }
            return
        }
        armPropSpoof(packageName)
    }

    private fun armPropSpoof(packageName: String?) {
        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused || !config.spoofUsbState) return

        val overrides = LinkedHashMap(Config.PROP_OVERRIDES)
        if (overrides.isEmpty()) return

        if (!NativeLib.load(ModuleConfig.moduleDir)) return

        val armed = runCatching {
            Native.installPropHooks(overrides.keys.toTypedArray(), overrides.values.toTypedArray())
        }.getOrElse {
            Logx.e("property spoof failed to arm for $packageName", it)
            0
        }
        if (armed > 0) Logx.i("property spoof armed for $packageName: $armed keys")
    }
}
