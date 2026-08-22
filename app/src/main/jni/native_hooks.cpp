#include <jni.h>
#include <sys/system_properties.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include "LSPosed.h"
#include "Logger.h"

// Prop overrides pushed from Kotlin (NativeProps.setProps), published LOCK-FREE.
//
// The hooks below intercept libc property reads. liblog (LOGD) and every JNI call
// ALSO read system properties internally — so if a hook took a lock while setProps
// (or a log line) held it, the same thread would re-enter and self-deadlock. That
// exact bug froze SystemUI. So: NO mutex. The snapshot is immutable and published
// via an atomic pointer; readers load-acquire it and never block. Old snapshots are
// intentionally leaked (setProps runs at most a couple times per process), which keeps
// any pointer a concurrent hook may still be reading valid forever — no use-after-free.
using PropMap = std::unordered_map<std::string, std::string>;

// bionic exposes a property two ways: by NAME (__system_property_get) and by HANDLE —
// __system_property_find gives you an opaque prop_info*, which you then read through
// __system_property_read_callback (or the legacy __system_property_read). Both routes
// need covering, so the snapshot carries both indexes: by name, and by the prop_info*
// each name resolves to. Handles are stable for the life of a property, so resolving
// them once at publish time is sound; a property that does not exist yet simply has no
// handle entry and is still covered by the name route.
struct Override {
    const prop_info *pi;
    std::string value;
};
struct Snapshot {
    PropMap byName;
    std::vector<Override> byInfo;  // at most a handful; linear scan beats hashing here
};
static std::atomic<const Snapshot *> gSnap{nullptr};

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static void (*orig_system_property_read_callback)(
        const prop_info *pi,
        void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
        void *cookie) = nullptr;
static int (*orig_system_property_read)(const prop_info *pi, char *name, char *value) = nullptr;

/** Our value for this handle, or nullptr if we don't spoof it. */
static const std::string *spoofed_for(const Snapshot *s, const prop_info *pi) {
    if (!s || !pi) return nullptr;
    for (const Override &o : s->byInfo) {
        if (o.pi == pi) return &o.value;
    }
    return nullptr;
}

/** Bounded copy into a caller-supplied PROP_VALUE_MAX buffer. Never strcpy in a libc hook. */
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
    // A hook whose backup never got installed must not jump through a null pointer.
    if (!orig_system_property_get) {
        if (value) value[0] = '\0';
        return 0;
    }
    return orig_system_property_get(name, value);
}

// Re-invoke the caller's callback with our value, keeping the REAL name and serial that
// bionic hands us. Substituting the value alone keeps the property looking entirely normal:
// it still exists, still has a plausible serial, and only reads differently.
struct CbCtx {
    void (*cb)(void *cookie, const char *name, const char *value, uint32_t serial);
    void *cookie;
    const char *value;
};
static void spoof_trampoline(void *cookie, const char *name, const char *value, uint32_t serial) {
    (void) value;  // the real value, deliberately dropped
    auto *ctx = static_cast<CbCtx *>(cookie);
    ctx->cb(ctx->cookie, name, ctx->value, serial);
}

// THE path that matters on modern Android: SystemProperties.get() and libbase's
// GetProperty() both resolve a handle and read it through this. The previous build hooked
// __system_property_find and returned nullptr for our keys, which made every caller on this
// route treat the property as ABSENT — so a native detector read sys.usb.state as "" rather
// than "mtp". An empty sys.usb.state is not a smaller lie than the truth, it is a stranger
// one. Now the handle resolves normally and only the value is rewritten.
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

// Legacy handle read. Rarely used now, but leaving it unhooked would make it the one route
// that still reports the truth — a partial spoof is its own tell.
static int hooked_system_property_read(const prop_info *pi, char *name, char *value) {
    if (!orig_system_property_read) return 0;
    int n = orig_system_property_read(pi, name, value);
    const std::string *spoof = spoofed_for(gSnap.load(std::memory_order_acquire), pi);
    if (spoof && value) return copy_bounded(value, *spoof);
    return n;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_strawing_duckusb_NativeProps_setProps(JNIEnv *env, jobject /*thiz*/, jobject props) {
    // Build a fresh snapshot with NO lock held. The JNI calls here read properties
    // internally and re-enter the hooks above — that is fine now: the hooks are
    // lock-free and simply see the previous snapshot until we publish this one.
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

    // Resolve each name to its handle, so the prop_info* routes can be spoofed too.
    // __system_property_find is NOT hooked (only the reads are), so this calls straight
    // into libc — no re-entry. A name with no handle yet stays covered by the name route.
    for (const auto &kv : next->byName) {
        if (const prop_info *pi = __system_property_find(kv.first.c_str())) {
            next->byInfo.push_back({pi, kv.second});
        }
    }

    // Publish. Any hook mid-read still holds the previous pointer, which we never free.
    gSnap.store(next, std::memory_order_release);
    LOGD("setProps: %zu overrides published, %zu handles resolved",  // safe: no lock held
         next->byName.size(), next->byInfo.size());
}

// Our own package. LSPosed loads a module into its own app process unconditionally — that
// is not something the scope list can turn off — so DuckUSB's UI always gets native_init.
// Hooking libc there is pure downside: nothing in our own process should be lied to (the
// readings card exists to show the REAL device state), and an inline hook on a hot libc
// entry point is the one thing in this process that can kill it. That is issue #2: on a
// Nothing A065 / Android 16 build the very first property read off the EmojiCompatInit
// thread landed in the trampoline and took SIGILL (ILL_ILLOPC), so the UI died seconds
// after launch — in a process that never needed the hook in the first place.
static const char kOwnPackage[] = "com.strawing.duckusb";

// Defence-in-depth: never install the libc hooks in our own UI, nor in core OS processes
// (mirrors the Kotlin SKIP_SPOOF_PACKAGES). The lock-free hooks are already safe with an
// empty map, but not touching libc at all removes both the crash surface here and any
// footprint in system_server / SystemUI.
// Fails safe: if the process name can't be read (very early load), we DON'T skip.
static bool should_skip_hooks(const char **reason) {
    char cmd[128] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);  // raw syscall, no FILE lock
    if (fd < 0) return false;
    ssize_t n = read(fd, cmd, sizeof(cmd) - 1);
    close(fd);
    if (n <= 0) return false;
    cmd[n] = '\0';  // first NUL-separated token is the process name

    // A private sub-process reports "<package>:<name>"; compare on the package half so
    // com.strawing.duckusb:anything is covered too. strchr stops at the token's NUL.
    char *colon = strchr(cmd, ':');
    if (colon) *colon = '\0';

    if (strcmp(cmd, kOwnPackage) == 0) {
        *reason = "own process";
        return true;
    }
    static const char *core[] = {
        "system_server", "android",
        "com.android.systemui", "com.android.settings",
        "com.android.shell", "com.android.phone",
    };
    for (const char *c : core) {
        if (strcmp(cmd, c) == 0) {
            *reason = "core OS process";
            return true;
        }
    }
    return false;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    const char *reason = "";
    if (should_skip_hooks(&reason)) {
        LOGD("native_init: %s, skipping libc hooks", reason);
        return [](const char *name, void *handle) {};
    }
    LOGD("native_init");
    HookFunType hook = entries->hook_func;
    // Three read routes, one hook each. __system_property_find is deliberately NOT hooked:
    // handles must resolve normally, and the reads are where the value gets rewritten.
    // Log the hooker's verdict — a failed inline hook is otherwise indistinguishable from a
    // working one until a property read misbehaves, which is a miserable thing to debug from
    // a crash report (see issue #2).
    int rc_get = hook((void *) __system_property_get, (void *) hooked_system_property_get,
                      (void **) &orig_system_property_get);
    int rc_cb = hook((void *) __system_property_read_callback,
                     (void *) hooked_system_property_read_callback,
                     (void **) &orig_system_property_read_callback);
    int rc_read = hook((void *) __system_property_read, (void *) hooked_system_property_read,
                       (void **) &orig_system_property_read);
    LOGD("native_init: hook rc get=%d read_callback=%d read=%d", rc_get, rc_cb, rc_read);
    return [](const char *name, void *handle) {};
}
