package com.strawing.duckusb.zygote.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method

object Native {
    external fun initHooking(): Boolean

    external fun hookMethod(target: Executable, hooker: Any, callback: Method): Method?

    external fun deoptimizeMethod(target: Executable): Boolean
}
