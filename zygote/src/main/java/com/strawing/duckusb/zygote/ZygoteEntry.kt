package com.strawing.duckusb.zygote

import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import com.v7878.zygisk.ZygoteLoader

object ZygoteEntry {

    @JvmStatic
    fun premain() {
        try {
            val moduleDir = ZygoteLoader.getModuleDir()
            ModuleConfig.load(moduleDir)
            val pkg = ZygoteLoader.getPackageName()
            if (pkg == Config.SYSTEM_SERVER_PACKAGE) return
            AppPart.preSpecialize(pkg, moduleDir)
        } catch (t: Throwable) {
            Logx.e("premain failed", t)
        }
    }

    @JvmStatic
    fun main() {
        try {
            val pkg = ZygoteLoader.getPackageName()
            Logx.v { "injected into $pkg (${ZygoteLoader.getProcessName()})" }
            if (pkg == Config.SYSTEM_SERVER_PACKAGE) SystemServerPart.init() else AppPart.postSpecialize()
        } catch (t: Throwable) {
            Logx.e("main failed", t)
        }
    }
}
