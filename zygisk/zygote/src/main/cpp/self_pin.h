#pragma once

#include <dlfcn.h>

// Hooks are patched into libc and libart and outlive this library's classloader,
// so the image must stay mapped once System.load()'s owner is collected.
inline bool pin_self() {
    static const bool pinned = [] {
        Dl_info info{};
        if (dladdr(reinterpret_cast<const void *>(&pin_self), &info) == 0 ||
            info.dli_fname == nullptr) {
            return false;
        }
        return dlopen(info.dli_fname, RTLD_NOW | RTLD_NODELETE) != nullptr;
    }();
    return pinned;
}
