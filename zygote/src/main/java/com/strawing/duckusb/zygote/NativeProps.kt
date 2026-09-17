package com.strawing.duckusb.zygote

import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.NativeLib

object NativeProps {

    @Volatile
    private var hooked = false

    fun install(overrides: Map<String, String>): Boolean {
        if (!NativeLib.isLoaded()) return false
        return try {
            if (!hooked) {
                hooked = installHooks()
                if (!hooked) return false
            }
            setProps(overrides)
            true
        } catch (t: Throwable) {
            Logx.e("native prop spoof failed", t)
            false
        }
    }

    private external fun installHooks(): Boolean

    private external fun setProps(props: Map<String, String>)
}
