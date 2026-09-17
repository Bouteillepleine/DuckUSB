package com.strawing.duckusb.zygote

import com.strawing.duckusb.zygote.util.Logx

object NativeProps {

    @Volatile
    private var loaded = false

    @Volatile
    private var hooked = false

    fun install(overrides: Map<String, String>): Boolean {
        try {
            if (!loaded) {
                System.loadLibrary("duckusb")
                loaded = true
            }
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
