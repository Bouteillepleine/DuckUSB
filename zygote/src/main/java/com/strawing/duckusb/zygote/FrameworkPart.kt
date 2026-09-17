package com.strawing.duckusb.zygote

import android.content.ContentProvider
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.SparseBooleanArray
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.util.CursorSpoof
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

object FrameworkPart {

    private const val SETTINGS_PROVIDER = "com.android.providers.settings.SettingsProvider"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var callSeen = false

    @Volatile
    private var querySeen = false

    private val lock = Any()
    private val targetCache = SparseBooleanArray()

    fun arm() {
        val provider = runCatching { localProvider() }.getOrNull() as? ContentProvider ?: run {
            Logx.e("settings provider not found, framework mode not armed")
            return
        }
        context = runCatching { provider.context }.getOrNull()
        if (context == null) {
            Logx.e("settings provider has no context, framework mode not armed")
            return
        }

        var count = 0
        for (m in XHook.methodsOf(provider.javaClass)) {
            when {
                m.name == "call" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onCall)) count++
                m.name == "query" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onQuery)) count++
            }
        }
        Logx.i("framework mode armed: $count methods on ${provider.javaClass.name}")
    }

    private fun localProvider(): Any? {
        val thread = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return null
        val field = thread.javaClass.getDeclaredField("mLocalProvidersByName")
            .apply { isAccessible = true }
        val map = field.get(thread) as? Map<*, *> ?: return null
        for (record in map.values) {
            if (record == null) continue
            val local = runCatching {
                record.javaClass.getDeclaredField("mLocalProvider")
                    .apply { isAccessible = true }
                    .get(record)
            }.getOrNull() ?: continue
            val names = runCatching {
                record.javaClass.getDeclaredField("mNames")
                    .apply { isAccessible = true }
                    .get(record) as? Array<*>
            }.getOrNull()
            if (names?.any { it == Config.SETTINGS_AUTHORITY } == true ||
                local.javaClass.name == SETTINGS_PROVIDER
            ) {
                return local
            }
        }
        return null
    }

    private fun spoofing(): Boolean {
        val config = ModuleConfig.config
        return !ModuleConfig.disabled && !config.paused &&
            config.spoofSettings && config.frameworkMode
    }

    private fun isTarget(uid: Int): Boolean {
        val config = ModuleConfig.config
        if (config.targets.isEmpty() && !config.frameworkAllApps) return false
        val appId = uid % Config.PER_USER_RANGE
        if (appId < Config.FIRST_APP_UID) return false
        synchronized(lock) {
            val i = targetCache.indexOfKey(uid)
            if (i >= 0) return targetCache.valueAt(i)
        }
        val token = Binder.clearCallingIdentity()
        val packages = try {
            context?.packageManager?.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        } ?: return false
        if (packages.any { it == Config.PKG }) return false
        if (packages.any { it in Config.SPARE_PACKAGES }) {
            synchronized(lock) { targetCache.put(uid, false) }
            return false
        }
        val target = if (config.frameworkAllApps) true else packages.any { config.isTarget(it) }
        synchronized(lock) { targetCache.put(uid, target) }
        return target
    }

    private fun callingUid(): Int? = try {
        Binder.getCallingUid()
    } catch (_: Throwable) {
        null
    }

    private fun onCall(f: Frame) {
        if (!callSeen) {
            callSeen = true
            Logx.i("framework call hook live")
        }
        f.proceed()
        try {
            if (!spoofing()) return
            val uid = callingUid() ?: return
            val args = f.args
            var key: String? = null
            for (i in 0 until args.size - 1) {
                val a = args[i]
                if (a is String && a in Config.GET_METHODS) {
                    key = args[i + 1] as? String
                    break
                }
            }
            if (key == null || key !in Config.SPOOF_KEYS) return
            if (!isTarget(uid)) return
            val bundle = f.result as? Bundle ?: return
            if (!bundle.containsKey(Config.CALL_VALUE)) return
            bundle.putString(Config.CALL_VALUE, "0")
            bundle.putInt(Config.CALL_GENERATION_INDEX, -1)
            Logx.v { "framework spoofed $key for uid $uid" }
        } catch (t: Throwable) {
            Logx.e("framework call spoof failed", t)
        }
    }

    private fun onQuery(f: Frame) {
        if (!querySeen) {
            querySeen = true
            Logx.i("framework query hook live")
        }
        f.proceed()
        try {
            if (!spoofing() || !ModuleConfig.config.coverQueryPath) return
            val uid = callingUid() ?: return
            if (!isTarget(uid)) return
            val cursor = f.result as? Cursor ?: return
            val uri = f.args.firstOrNull { it is Uri } as? Uri
            val replaced = CursorSpoof.rewrite(cursor, uri?.lastPathSegment) ?: return
            f.result = replaced
            Logx.v { "framework spoofed a cursor for uid $uid" }
        } catch (t: Throwable) {
            Logx.e("framework query spoof failed", t)
        }
    }
}
