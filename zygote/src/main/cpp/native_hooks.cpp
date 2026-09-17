#include <jni.h>
#include <sys/system_properties.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include "shadowhook.h"
#include "Logger.h"

using PropMap = std::unordered_map<std::string, std::string>;

struct Override {
    const prop_info *pi;
    std::string value;
};

struct Snapshot {
    PropMap byName;
    std::vector<Override> byInfo;
};

static std::atomic<const Snapshot *> gSnap{nullptr};
static std::atomic<bool> gHooksInstalled{false};

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static void (*orig_system_property_read_callback)(
        const prop_info *pi,
        void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
        void *cookie) = nullptr;
static int (*orig_system_property_read)(const prop_info *pi, char *name, char *value) = nullptr;

static const std::string *spoofed_for(const Snapshot *s, const prop_info *pi) {
    if (!s || !pi) return nullptr;
    for (const Override &o: s->byInfo) {
        if (o.pi == pi) return &o.value;
    }
    return nullptr;
}

static int copy_bounded(char *dst, const std::string &src) {
    size_t n = src.size();
    if (n > PROP_VALUE_MAX - 1) n = PROP_VALUE_MAX - 1;
    memcpy(dst, src.data(), n);
    dst[n] = '\0';
    return static_cast<int>(n);
}

static int hooked_system_property_get(const char *name, char *value) {
    const Snapshot *s = gSnap.load(std::memory_order_acquire);
    if (name && value && s) {
        auto it = s->byName.find(name);
        if (it != s->byName.end()) return copy_bounded(value, it->second);
    }
    if (!orig_system_property_get) {
        if (value) value[0] = '\0';
        return 0;
    }
    return orig_system_property_get(name, value);
}

struct CbCtx {
    void (*cb)(void *cookie, const char *name, const char *value, uint32_t serial);
    void *cookie;
    const char *value;
};

static void spoof_trampoline(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void) value;
    auto *ctx = static_cast<CbCtx *>(cookie);
    ctx->cb(ctx->cookie, name, ctx->value, serial);
}

static void hooked_system_property_read_callback(
        const prop_info *pi,
        void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
        void *cookie) {
    if (!orig_system_property_read_callback) return;
    const std::string *spoof = spoofed_for(gSnap.load(std::memory_order_acquire), pi);
    if (spoof && callback) {
        CbCtx ctx{callback, cookie, spoof->c_str()};
        orig_system_property_read_callback(pi, spoof_trampoline, &ctx);
        return;
    }
    orig_system_property_read_callback(pi, callback, cookie);
}

static int hooked_system_property_read(const prop_info *pi, char *name, char *value) {
    if (!orig_system_property_read) return 0;
    int n = orig_system_property_read(pi, name, value);
    const std::string *spoof = spoofed_for(gSnap.load(std::memory_order_acquire), pi);
    if (spoof && value) return copy_bounded(value, *spoof);
    return n;
}

static const char kManagerPackage[] = "com.strawing.duckusb.zygisk";

static bool should_skip_hooks(const char **reason) {
    char cmd[128] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, cmd, sizeof(cmd) - 1);
    close(fd);
    if (n <= 0) return false;
    cmd[n] = '\0';

    char *colon = strchr(cmd, ':');
    if (colon) *colon = '\0';

    if (strcmp(cmd, kManagerPackage) == 0) {
        *reason = "own process";
        return true;
    }
    static const char *core[] = {
            "zygote", "zygote64", "usap32", "usap64",
            "system_server", "android",
            "com.android.systemui", "com.android.settings",
            "com.android.shell", "com.android.phone",
    };
    for (const char *c: core) {
        if (strcmp(cmd, c) == 0) {
            *reason = "core OS process";
            return true;
        }
    }
    return false;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_strawing_duckusb_zygote_NativeProps_installHooks(JNIEnv *, jobject) {
    if (gHooksInstalled.load(std::memory_order_acquire)) return JNI_TRUE;

    const char *reason = "";
    if (should_skip_hooks(&reason)) {
        LOGD("installHooks: %s, skipped", reason);
        return JNI_FALSE;
    }

    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
        LOGD("installHooks: shadowhook_init failed errno=%d", shadowhook_get_errno());
        return JNI_FALSE;
    }

    void *s_get = shadowhook_hook_sym_name(
            "libc.so", "__system_property_get",
            (void *) hooked_system_property_get, (void **) &orig_system_property_get);
    void *s_cb = shadowhook_hook_sym_name(
            "libc.so", "__system_property_read_callback",
            (void *) hooked_system_property_read_callback,
            (void **) &orig_system_property_read_callback);
    void *s_read = shadowhook_hook_sym_name(
            "libc.so", "__system_property_read",
            (void *) hooked_system_property_read, (void **) &orig_system_property_read);

    bool ok = s_get != nullptr && s_cb != nullptr;
    LOGD("installHooks: get=%p read_callback=%p read=%p", s_get, s_cb, s_read);
    gHooksInstalled.store(ok, std::memory_order_release);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_strawing_duckusb_zygote_NativeProps_setProps(JNIEnv *env, jobject, jobject props) {
    auto *next = new Snapshot();
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

            next->byName.emplace(key, value);

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

    for (const auto &kv: next->byName) {
        if (const prop_info *pi = __system_property_find(kv.first.c_str())) {
            next->byInfo.push_back({pi, kv.second});
        }
    }

    gSnap.store(next, std::memory_order_release);
    LOGD("setProps: %zu overrides published, %zu handles resolved",
         next->byName.size(), next->byInfo.size());
}
