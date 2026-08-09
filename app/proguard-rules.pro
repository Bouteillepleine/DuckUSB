# Xposed entry point (named in assets/xposed_init) must be kept.
-keep class com.strawing.duckusb.DuckUSBModule { *; }
-keepnames class com.strawing.duckusb.** { *; }

# JNI bridge to libduckusb.so — the native side binds setProps by its exact mangled
# name, so NativeProps and any native method must not be renamed/removed.
-keep class com.strawing.duckusb.NativeProps { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
