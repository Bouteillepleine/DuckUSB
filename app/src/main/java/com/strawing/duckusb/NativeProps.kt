package com.strawing.duckusb

/** JNI bridge to libduckusb.so. Pushes the prop overrides the native libc hooks apply. */
object NativeProps {
    @Volatile private var loaded = false

    /** Load once per process and hand the override map to the native side. Safe to call again. */
    fun install(overrides: Map<String, String>) {
        if (!loaded) {
            System.loadLibrary("duckusb")
            loaded = true
        }
        setProps(overrides)
    }

    private external fun setProps(props: Map<String, String>)
}
