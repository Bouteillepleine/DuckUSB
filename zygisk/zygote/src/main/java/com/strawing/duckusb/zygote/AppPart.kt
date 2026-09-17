package com.strawing.duckusb.zygote

import com.strawing.duckusb.common.Config

object AppPart {

    fun preSpecialize(packageName: String?) {
        if (packageName != Config.PKG) return
        runCatching { System.setProperty(Config.LIVE_PROPERTY, Config.MODULE_VERSION) }
    }
}
