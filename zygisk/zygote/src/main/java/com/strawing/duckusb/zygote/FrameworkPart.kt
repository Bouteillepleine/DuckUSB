package com.strawing.duckusb.zygote

import android.content.ContentProvider
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.SparseBooleanArray
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.BridgeRegistry
import com.strawing.duckusb.common.SpoofRegistry
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.service.DuckService
import com.strawing.duckusb.common.CursorSpoof
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

object FrameworkPart {

    private const val SETTINGS_PROVIDER = "com.android.providers.settings.SettingsProvider"

    @Volatile
    var context: Context? = null
        private set

    @Volatile
    var service: DuckService? = null
        private set

    @Volatile
    var lent = false
        private set

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

        service = DuckService(context!!)
        var count = 0
        for (m in XHook.methodsOf(provider.javaClass)) {
            when {
                m.name == "call" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onCall)) count++
                m.name == "query" && m.parameterCount >= 3 -> if (XHook.hook(m, ::onQuery)) count++
            }
        }
        service?.hookCount = count
        service?.installedAtRealtimeMs = android.os.SystemClock.elapsedRealtime()
        if (count > 0) {
            BridgeRegistry.publish(Bridge.METHOD, ownBridge)
            SpoofRegistry.publish()
        } else {
            BridgeRegistry.registerWithOwner(Bridge.METHOD, ownBridge)
            lent = SpoofRegistry.registerWithOwner(Config.MODULE_ID, ownFilter)
        }
        Logx.i("framework mode armed: $count methods on ${provider.javaClass.name}, lent=$lent")
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

    private val ownBridge = java.lang.reflect.InvocationHandler { _, _, args ->
        val uid = args?.getOrNull(0) as? Int
        val svc = service
        if (uid == null || svc == null || svc.callerAppId < 0 ||
            uid % Config.PER_USER_RANGE != svc.callerAppId
        ) {
            null
        } else {
            Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) }
        }
    }

    private fun matchesBridge(args: List<Any?>): Boolean {
        for (i in 0 until args.size - 1) {
            if (args[i] == Bridge.METHOD && args[i + 1] == Bridge.ARG) return true
        }
        return false
    }

    private fun onCall(f: Frame) {
        if (!callSeen) {
            callSeen = true
            Logx.i("framework call hook live")
        }
        val svc = service
        val uidNow = callingUid()
        if (svc != null && uidNow != null && svc.callerAppId >= 0 &&
            uidNow % Config.PER_USER_RANGE == svc.callerAppId && matchesBridge(f.args)
        ) {
            f.result = Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) }
            return
        }
        if (uidNow != null) {
            val foreign = BridgeRegistry.serve(f.args, uidNow)
            if (foreign != null) {
                f.result = foreign
                return
            }
        }
        f.proceed()
        val uid = callingUid() ?: return
        runCatching { spoofCall(uid, f.args, f.result)?.let { f.result = it } }
            .onFailure { Logx.e("framework call spoof failed", it) }
        SpoofRegistry.apply(SpoofRegistry.CALL, uid, f.args, f.result)?.let { f.result = it }
    }

    fun spoofCall(uid: Int, args: List<Any?>, result: Any?): Any? {
        if (!spoofing()) return null
        var key: String? = null
        for (i in 0 until args.size - 1) {
            val a = args[i]
            if (a is String && a in Config.GET_METHODS) {
                key = args[i + 1] as? String
                break
            }
        }
        if (key == null || key !in Config.SPOOF_KEYS) return null
        if (!isTarget(uid)) return null
        val bundle = result as? Bundle ?: return null
        if (!bundle.containsKey(Config.CALL_VALUE)) return null
        bundle.putString(Config.CALL_VALUE, "0")
        bundle.putInt(Config.CALL_GENERATION_INDEX, -1)
        service?.note(uid, key)
        Logx.v { "framework spoofed $key for uid $uid" }
        return bundle
    }

    private fun onQuery(f: Frame) {
        if (!querySeen) {
            querySeen = true
            Logx.i("framework query hook live")
        }
        f.proceed()
        val uid = callingUid() ?: return
        runCatching { spoofQuery(uid, f.args, f.result)?.let { f.result = it } }
            .onFailure { Logx.e("framework query spoof failed", it) }
        SpoofRegistry.apply(SpoofRegistry.QUERY, uid, f.args, f.result)?.let { f.result = it }
    }

    fun spoofQuery(uid: Int, args: List<Any?>, result: Any?): Any? {
        if (!spoofing() || !ModuleConfig.config.coverQueryPath) return null
        if (!isTarget(uid)) return null
        val cursor = result as? Cursor ?: return null
        val uri = args.firstOrNull { it is Uri } as? Uri
        val replaced = CursorSpoof.rewrite(cursor, uri?.lastPathSegment) ?: return null
        service?.note(uid, "query")
        Logx.v { "framework spoofed a cursor for uid $uid" }
        return replaced
    }

    val ownFilter = java.lang.reflect.InvocationHandler { _, _, args ->
        val kind = args?.getOrNull(0) as? String
        val uid = args?.getOrNull(1) as? Int
        val callArgs = (args?.getOrNull(2) as? Array<*>)?.toList() ?: emptyList<Any?>()
        val result = args?.getOrNull(3)
        when {
            uid == null -> null
            kind == SpoofRegistry.CALL -> spoofCall(uid, callArgs, result)
            kind == SpoofRegistry.QUERY -> spoofQuery(uid, callArgs, result)
            else -> null
        }
    }
}
