#include <jni.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

#include <dobby.h>

#include "Logger.h"

namespace {

struct Entry {
    std::string name;
    std::string value;
    const void *info;
};

std::vector<Entry> g_entries;
bool g_installed = false;

using get_fn = int (*)(const char *, char *);
using find_fn = const void *(*)(const char *);
using read_cb_t = void (*)(void *, const char *, const char *, uint32_t);
using read_callback_fn = void (*)(const void *, read_cb_t, void *);
using read_fn = int (*)(const void *, char *, char *);

get_fn orig_get = nullptr;
read_callback_fn orig_read_callback = nullptr;
read_fn orig_read = nullptr;

const Entry *by_name(const char *name) {
    if (name == nullptr) return nullptr;
    for (const auto &e : g_entries) {
        if (e.name == name) return &e;
    }
    return nullptr;
}

const Entry *by_info(const void *info) {
    if (info == nullptr) return nullptr;
    for (const auto &e : g_entries) {
        if (e.info != nullptr && e.info == info) return &e;
    }
    return nullptr;
}

int my_get(const char *name, char *value) {
    const Entry *e = by_name(name);
    if (e != nullptr && value != nullptr) {
        auto len = e->value.size();
        if (len > 91) len = 91;
        memcpy(value, e->value.c_str(), len);
        value[len] = '\0';
        return static_cast<int>(len);
    }
    return orig_get(name, value);
}

void my_read_callback(const void *info, read_cb_t callback, void *cookie) {
    const Entry *e = by_info(info);
    if (e != nullptr && callback != nullptr) {
        callback(cookie, e->name.c_str(), e->value.c_str(), 1);
        return;
    }
    orig_read_callback(info, callback, cookie);
}

int my_read(const void *info, char *name, char *value) {
    const Entry *e = by_info(info);
    if (e != nullptr && value != nullptr) {
        auto len = e->value.size();
        if (len > 91) len = 91;
        memcpy(value, e->value.c_str(), len);
        value[len] = '\0';
        if (name != nullptr) {
            auto nlen = e->name.size();
            if (nlen > 31) nlen = 31;
            memcpy(name, e->name.c_str(), nlen);
            name[nlen] = '\0';
        }
        return static_cast<int>(len);
    }
    return orig_read(info, name, value);
}

std::string to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *raw = env->GetStringUTFChars(s, nullptr);
    std::string out = raw == nullptr ? std::string{} : std::string(raw);
    if (raw != nullptr) env->ReleaseStringUTFChars(s, raw);
    return out;
}

}

extern "C"
JNIEXPORT jint JNICALL
Java_com_strawing_duckusb_zygote_hook_Native_installPropHooks(
        JNIEnv *env, jobject, jobjectArray names, jobjectArray values) {
    if (g_installed) return static_cast<jint>(g_entries.size());
    if (names == nullptr || values == nullptr) return 0;

    jsize count = env->GetArrayLength(names);
    if (count == 0 || env->GetArrayLength(values) != count) return 0;

    auto real_get = reinterpret_cast<get_fn>(dlsym(RTLD_DEFAULT, "__system_property_get"));
    auto real_find = reinterpret_cast<find_fn>(dlsym(RTLD_DEFAULT, "__system_property_find"));
    auto real_read_callback = reinterpret_cast<read_callback_fn>(
            dlsym(RTLD_DEFAULT, "__system_property_read_callback"));
    auto real_read = reinterpret_cast<read_fn>(dlsym(RTLD_DEFAULT, "__system_property_read"));

    if (real_get == nullptr) {
        LOGE("__system_property_get not found, property spoof not armed");
        return 0;
    }

    g_entries.clear();
    g_entries.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto n = reinterpret_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto v = reinterpret_cast<jstring>(env->GetObjectArrayElement(values, i));
        Entry e{to_utf8(env, n), to_utf8(env, v), nullptr};
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (n != nullptr) env->DeleteLocalRef(n);
        if (v != nullptr) env->DeleteLocalRef(v);
        if (e.name.empty()) continue;
        if (real_find != nullptr) e.info = real_find(e.name.c_str());
        g_entries.push_back(std::move(e));
    }
    if (g_entries.empty()) return 0;

    int armed = 0;
    if (DobbyHook(reinterpret_cast<void *>(real_get),
                  reinterpret_cast<dobby_dummy_func_t>(my_get),
                  reinterpret_cast<dobby_dummy_func_t *>(&orig_get)) == 0) {
        armed++;
    } else {
        LOGE("failed to hook __system_property_get");
    }

    if (real_read_callback != nullptr &&
        DobbyHook(reinterpret_cast<void *>(real_read_callback),
                  reinterpret_cast<dobby_dummy_func_t>(my_read_callback),
                  reinterpret_cast<dobby_dummy_func_t *>(&orig_read_callback)) == 0) {
        armed++;
    }

    if (real_read != nullptr &&
        DobbyHook(reinterpret_cast<void *>(real_read),
                  reinterpret_cast<dobby_dummy_func_t>(my_read),
                  reinterpret_cast<dobby_dummy_func_t *>(&orig_read)) == 0) {
        armed++;
    }

    if (armed == 0) {
        g_entries.clear();
        return 0;
    }

    g_installed = true;
    LOGD("property spoof armed: %d hooks, %zu keys", armed, g_entries.size());
    return static_cast<jint>(g_entries.size());
}
