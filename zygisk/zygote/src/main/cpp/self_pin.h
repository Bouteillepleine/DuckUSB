#pragma once

#include <dlfcn.h>

// Hooks are patched into libc and libart and outlive this library's classloader,
// so the image must stay mapped once System.load()'s owner is collected.
inline const char *&pin_self_error() {
    static const char *err = nullptr;
    return err;
}

inline bool pin_self() {
    static const bool pinned = [] {
        Dl_info info{};
        if (dladdr(reinterpret_cast<const void *>(&pin_self), &info) == 0 ||
            info.dli_fname == nullptr) {
            pin_self_error() = "dladdr gave no path for this image";
            return false;
        }
        if (dlopen(info.dli_fname, RTLD_NOW | RTLD_NODELETE) != nullptr) return true;
        const char *e = dlerror();
        pin_self_error() = e != nullptr ? e : "dlopen failed";
        return false;
    }();
    return pinned;
}
