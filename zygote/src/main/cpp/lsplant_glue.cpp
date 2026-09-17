#include <jni.h>

#include <atomic>
#include <string>

#include <dobby.h>
#include <lsplant.hpp>

#include "elf_img.h"
#include "Logger.h"

static ElfImg *gArt = nullptr;
static std::atomic<bool> gReady{false};

static void *inline_hook(void *target, void *hooker) {
    void *backup = nullptr;
    if (DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(hooker),
                  reinterpret_cast<dobby_dummy_func_t *>(&backup)) == 0) {
        return backup;
    }
    return nullptr;
}

static bool inline_unhook(void *func) {
    return DobbyDestroy(func) == 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_strawing_duckusb_zygote_hook_Native_initHooking(JNIEnv *env, jobject) {
    if (gReady.load(std::memory_order_acquire)) return JNI_TRUE;

    if (gArt == nullptr) gArt = new ElfImg("libart.so");
    if (!gArt->valid()) {
        LOGE("libart.so could not be parsed");
        return JNI_FALSE;
    }

    lsplant::InitInfo info{
            .inline_hooker = inline_hook,
            .inline_unhooker = inline_unhook,
            .art_symbol_resolver = [](std::string_view name) -> void * {
                return gArt->symbol(name);
            },
            .art_symbol_prefix_resolver = [](std::string_view prefix) -> void * {
                return gArt->symbolWithPrefix(prefix);
            },
    };

    bool ok = lsplant::Init(env, info);
    LOGD("lsplant init: %d", ok);
    gReady.store(ok, std::memory_order_release);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_strawing_duckusb_zygote_hook_Native_hookMethod(
        JNIEnv *env, jobject, jobject target, jobject hooker, jobject callback) {
    if (!gReady.load(std::memory_order_acquire)) return nullptr;
    return lsplant::Hook(env, target, hooker, callback);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_strawing_duckusb_zygote_hook_Native_deoptimizeMethod(
        JNIEnv *env, jobject, jobject target) {
    if (!gReady.load(std::memory_order_acquire)) return JNI_FALSE;
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}
