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

    fun preload(moduleDir: String?): Boolean = try {
        load(moduleDir)
    } catch (t: Throwable) {
        Logx.e("native preload failed", t)
        false
    }

    fun install(moduleDir: String?, overrides: Map<String, String>): Boolean {
        try {
            if (!load(moduleDir)) return false
            if (!hooked) {
                initShadowHook()
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

    private fun initShadowHook() {
        runCatching {
            val config = Class.forName("com.bytedance.shadowhook.ShadowHook\$ConfigBuilder")
                .getDeclaredConstructor().newInstance()
            val builder = config.javaClass
            val setMode = builder.getDeclaredMethod("setMode", Class.forName("com.bytedance.shadowhook.ShadowHook\$Mode"))
            val mode = Class.forName("com.bytedance.shadowhook.ShadowHook\$Mode")
                .getDeclaredField("UNIQUE").get(null)
            setMode.invoke(config, mode)
            val built = builder.getDeclaredMethod("build").invoke(config)
            val init = Class.forName("com.bytedance.shadowhook.ShadowHook")
                .getDeclaredMethod("init", built.javaClass)
            val rc = init.invoke(null, built)
            Logx.i("shadowhook java init rc=$rc")
        }.onFailure { Logx.e("shadowhook java init failed", it) }
    }

    private external fun installHooks(): Boolean

    private external fun setProps(props: Map<String, String>)
}
