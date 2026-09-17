package com.strawing.duckusb.zygote.util

import java.io.File

object NativeLib {

    @Volatile
    private var loaded = false

    private fun abiFolder(): String =
        when (System.getProperty("os.arch")) {
            "aarch64", "arm64", "armv8", "armv8l" -> "arm64-v8a"
            else -> "armeabi-v7a"
        }

    fun load(moduleDir: String?): Boolean {
        if (loaded) return true
        if (moduleDir == null) {
            Logx.e("module dir unavailable, cannot load the native library")
            return false
        }
        return try {
            val dir = File(File(moduleDir, "lib"), abiFolder())
            val lib = File(dir, "libduckusb.so")
            if (!lib.exists()) {
                Logx.e("libduckusb.so missing at ${lib.path}")
                return false
            }
            System.load(lib.path)
            loaded = true
            true
        } catch (t: Throwable) {
            Logx.e("libduckusb.so did not load", t)
            false
        }
    }

    fun isLoaded(): Boolean = loaded
}
