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
        if (ModuleConfig.disabled || config.paused || !config.spoofProps) return

        val overrides = LinkedHashMap<String, String>()
        overrides[Config.PERSIST_USB_PROP] = Config.PERSIST_USB_SAFE
        if (config.spoofUsbState) overrides.putAll(Config.PROP_OVERRIDES)
        else overrides["init.svc.adbd"] = Config.PROP_OVERRIDES["init.svc.adbd"] ?: "stopped"
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
