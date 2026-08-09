#include <jni.h>
#include <sys/system_properties.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <string>
#include <unordered_map>

#include "LSPosed.h"
#include "Logger.h"

// Prop overrides pushed from Kotlin (NativeProps.setProps), published LOCK-FREE.
//
// The hooks below intercept libc property reads. liblog (LOGD) and every JNI call
// ALSO read system properties internally — so if a hook took a lock while setProps
// (or a log line) held it, the same thread would re-enter and self-deadlock. That
// exact bug froze SystemUI. So: NO mutex. The map is an immutable snapshot published
// via an atomic pointer; readers load-acquire it and never block. Old snapshots are
// intentionally leaked (setProps runs at most a couple times per process), which keeps
// any pointer a concurrent hook may still be reading valid forever — no use-after-free.
using PropMap = std::unordered_map<std::string, std::string>;
static std::atomic<const PropMap *> gProps{nullptr};

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static const prop_info *(*orig_system_property_find)(const char *name) = nullptr;

static int hooked_system_property_get(const char *name, char *value) {
    const PropMap *m = gProps.load(std::memory_order_acquire);
    if (name && m) {
        auto it = m->find(name);
        if (it != m->end()) {
            // value is a PROP_VALUE_MAX buffer; bound the copy (never strcpy blindly in a libc hook).
            size_t n = it->second.size();
            if (n > PROP_VALUE_MAX - 1) n = PROP_VALUE_MAX - 1;
            memcpy(value, it->second.data(), n);
            value[n] = '\0';
            return static_cast<int>(n);
        }
    }
    return orig_system_property_get(name, value);
}

// Modern Android (SystemProperties.get) resolves prop_info via __system_property_find
// first; returning nullptr makes the caller treat the prop as absent (falls back to default).
static const prop_info *hooked_system_property_find(const char *name) {
    const PropMap *m = gProps.load(std::memory_order_acquire);
    if (name && m && m->find(name) != m->end()) {
        return nullptr;
    }
    return orig_system_property_find(name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_strawing_duckusb_NativeProps_setProps(JNIEnv *env, jobject /*thiz*/, jobject props) {
    // Build a fresh snapshot with NO lock held. The JNI calls here read properties
    // internally and re-enter the hooks above — that is fine now: the hooks are
    // lock-free and simply see the previous snapshot until we publish this one.
    auto *next = new PropMap();
    if (props != nullptr) {
        jclass mapClass = env->FindClass("java/util/Map");
        jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
        jobject entrySet = env->CallObjectMethod(props, entrySetMethod);

        jclass setClass = env->FindClass("java/util/Set");
        jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
        jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);

        jclass iteratorClass = env->FindClass("java/util/Iterator");
        jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
        jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

        jclass entryClass = env->FindClass("java/util/Map$Entry");
        jmethodID getKeyMethod = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
        jmethodID getValueMethod = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

        while (env->CallBooleanMethod(iterator, hasNextMethod)) {
            jobject entry = env->CallObjectMethod(iterator, nextMethod);
            auto keyString = static_cast<jstring>(env->CallObjectMethod(entry, getKeyMethod));
            auto valueString = static_cast<jstring>(env->CallObjectMethod(entry, getValueMethod));
            const char *key = env->GetStringUTFChars(keyString, nullptr);
            const char *value = env->GetStringUTFChars(valueString, nullptr);

            next->emplace(key, value);

            env->ReleaseStringUTFChars(keyString, key);
            env->ReleaseStringUTFChars(valueString, value);
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(keyString);
            env->DeleteLocalRef(valueString);
        }

        env->DeleteLocalRef(mapClass);
        env->DeleteLocalRef(entrySet);
        env->DeleteLocalRef(setClass);
        env->DeleteLocalRef(iterator);
        env->DeleteLocalRef(iteratorClass);
        env->DeleteLocalRef(entryClass);
    }

    // Publish. Any hook mid-read still holds the previous pointer, which we never free.
    gProps.store(next, std::memory_order_release);
    LOGD("setProps: %zu overrides published", next->size());  // safe: no lock held
}

// Defence-in-depth: never install the libc hooks in core OS processes (mirrors the
// Kotlin SKIP_SPOOF_PACKAGES). The lock-free hooks are already safe with an empty map,
// but not touching libc at all in system_server / SystemUI removes any footprint there.
// Fails safe: if the process name can't be read (very early load), we DON'T skip.
static bool is_core_os_process() {
    char cmd[128] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);  // raw syscall, no FILE lock
    if (fd < 0) return false;
    ssize_t n = read(fd, cmd, sizeof(cmd) - 1);
    close(fd);
    if (n <= 0) return false;
    cmd[n] = '\0';  // first NUL-separated token is the process name
    static const char *core[] = {
        "system_server", "android",
        "com.android.systemui", "com.android.settings",
        "com.android.shell", "com.android.phone",
    };
    for (const char *c : core) if (strcmp(cmd, c) == 0) return true;
    return false;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (is_core_os_process()) {
        LOGD("native_init: core OS process, skipping libc hooks");
        return [](const char *name, void *handle) {};
    }
    LOGD("native_init");
    HookFunType hook = entries->hook_func;
    hook((void *) __system_property_get, (void *) hooked_system_property_get,
         (void **) &orig_system_property_get);
    hook((void *) __system_property_find, (void *) hooked_system_property_find,
         (void **) &orig_system_property_find);
    return [](const char *name, void *handle) {};
}
