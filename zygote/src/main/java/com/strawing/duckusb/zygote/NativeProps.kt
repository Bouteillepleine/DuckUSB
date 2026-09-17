package com.strawing.duckusb.zygote

import com.strawing.duckusb.zygote.util.Logx
import java.io.File

object NativeProps {

    @Volatile
    private var loaded = false

    @Volatile
    private var hooked = false

    private fun abiFolder(): String =
        when (System.getProperty("os.arch")) {
            "aarch64", "arm64", "armv8", "armv8l" -> "arm64-v8a"
            else -> "armeabi-v7a"
        }

    private fun load(moduleDir: String?): Boolean {
        if (loaded) return true
        if (moduleDir == null) {
            Logx.e("module dir unavailable, cannot load the native library")
            return false
        }
        val dir = File(File(moduleDir, "lib"), abiFolder())
        val shadow = File(dir, "libshadowhook.so")
        val duck = File(dir, "libduckusb.so")
        if (!duck.exists()) {
            Logx.e("libduckusb.so missing at ${duck.path}")
            return false
        }
        runCatching { System.load(shadow.path) }
            .onFailure { Logx.e("libshadowhook.so did not load", it) }
        System.load(duck.path)
        loaded = true
        return true
    }

    fun install(moduleDir: String?, overrides: Map<String, String>): Boolean {
        try {
            if (!load(moduleDir)) return false
            if (!hooked) {
                hooked = installHooks()
                if (!hooked) return false
            }
            setProps(overrides)
            return true
        } catch (t: Throwable) {
            Logx.e("native prop spoof failed", t)
            return false
        }
    }

    private external fun installHooks(): Boolean

    private external fun setProps(props: Map<String, String>)
}
