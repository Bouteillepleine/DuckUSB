package com.strawing.duckusb.zygote

import android.app.Notification
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.content.res.Resources
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.service.DuckService
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import kotlin.concurrent.thread

object SystemServerPart {

    private const val SETTINGS_PROVIDER = "com.android.providers.settings.SettingsProvider"
    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    private val SETTING_GETTERS = setOf("getGlobalSetting", "getSecureSetting", "getSystemSetting")

    @Volatile
    var service: DuckService? = null
        private set

    private var settingsHooked = false

    @Volatile
    private var callHookSeen = false

    @Volatile
    private var queryHookSeen = false

    private val spoofValues: Map<String, String> =
        Config.SPOOF_KEYS.associateWith { "0" }

    fun init() {
        if (ModuleConfig.disabled) {
            Logx.i("kill switch present, no hooks installed")
            return
        }
        hookContentProviderAttach()
        hookNotificationManager()
        thread(name = "duckusb-nms", isDaemon = true) { hookNotificationManagerService() }
        thread(name = "duckusb-settings", isDaemon = true) { pollForSettingsProvider() }
    }

    private fun hookContentProviderAttach() {
        val cp = XHook.findClass("android.content.ContentProvider") ?: run {
            Logx.e("ContentProvider not found")
            return
        }
        val attachInfo = try {
            cp.getDeclaredMethod("attachInfo", Context::class.java, ProviderInfo::class.java)
        } catch (t: Throwable) {
            Logx.e("ContentProvider.attachInfo not found", t)
            return
        }
        XHook.hook(attachInfo) { f ->
            f.proceed()
            try {
                val provider = f.thisObject
                val info = f.arg(1) as? ProviderInfo
                Logx.v { "provider attach: ${info?.authority} ${provider?.javaClass?.name}" }
                val isSettings =
                    info?.authority?.split(";")?.any { it.trim() == Config.SETTINGS_AUTHORITY } == true ||
                        provider?.javaClass?.name == SETTINGS_PROVIDER
                if (isSettings && provider != null) {
                    ensureService(provider as? ContentProvider, f.arg(0) as? Context)
                    hookSettingsProvider(provider.javaClass)
                }
            } catch (t: Throwable) {
                Logx.e("attachInfo hook failed", t)
            }
        }
        Logx.i("armed: waiting for the settings provider")
    }

    private fun hookSettingsProvider(clazz: Class<*>) {
        if (settingsHooked) return
        settingsHooked = true
        var count = 0
        for (m in clazz.declaredMethods) {
            val hooked = when {
                m.name == "call" && m.parameterCount >= 3 -> XHook.hook(m, ::onProviderCall)
                m.name == "query" && m.parameterCount >= 3 -> XHook.hook(m, ::onProviderQuery)
                m.name in SETTING_GETTERS -> XHook.hook(m, ::onGetSetting)
                else -> false
            }
            if (hooked) {
                count++
                Logx.i("hooked ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            }
        }
        service?.hookCount = count
        service?.installedAtRealtimeMs = SystemClock.elapsedRealtime()
        Logx.i("settings provider hooked: $count methods on ${clazz.name}")
    }

    private fun callingUid(): Int? = try {
        Binder.getCallingUid()
    } catch (_: Throwable) {
        null
    }

    private fun onProviderCall(f: Frame) {
        if (!callHookSeen) {
            callHookSeen = true
            Logx.i("call hook live (service=${service != null})")
        }
        val uid = callingUid()
        val args = f.args
        val svc = service

        val bridge = svc != null && uid != null && svc.callerAppId >= 0 &&
            uid % Config.PER_USER_RANGE == svc.callerAppId && matchesBridge(args)
        if (bridge) {
            f.result = Bundle().apply { putBinder(Bridge.KEY_BINDER, svc) }
            return
        }

        f.proceed()
        try {
            spoofCallResult(uid, args, f.result)
        } catch (t: Throwable) {
            Logx.e("call spoof failed", t)
        }
    }

    private fun matchesBridge(args: List<Any?>): Boolean {
        for (i in 0 until args.size - 1) {
            if (args[i] == Bridge.METHOD && args[i + 1] == Bridge.ARG) return true
        }
        return false
    }

    private fun keyAfter(args: List<Any?>, methods: Set<String>): String? {
        for (i in 0 until args.size - 1) {
            val a = args[i]
            if (a is String && a in methods) return args[i + 1] as? String
        }
        return null
    }

    private fun spoofCallResult(uid: Int?, args: List<Any?>, result: Any?) {
        if (uid == null) return
        val svc = service ?: return
        if (!svc.spoofingEnabled()) return
        val key = keyAfter(args, Config.GET_METHODS) ?: return
        if (key !in Config.SPOOF_KEYS) return
        if (!svc.isTarget(uid)) return
        val bundle = result as? Bundle ?: return
        if (!bundle.containsKey(Config.CALL_VALUE)) return
        bundle.putString(Config.CALL_VALUE, "0")
        bundle.putInt(Config.CALL_GENERATION_INDEX, -1)
        svc.note(uid, key)
        Logx.v { "spoofed $key for uid $uid" }
    }

    private fun onGetSetting(f: Frame) {
        val uid = callingUid()
        f.proceed()
        try {
            val svc = service ?: return
            if (uid == null || !svc.spoofingEnabled()) return
            val key = f.args.firstOrNull { it is String } as? String ?: return
            if (key !in Config.SPOOF_KEYS) return
            if (!svc.isTarget(uid)) return
            val bundle = f.result as? Bundle ?: return
            if (!bundle.containsKey(Config.CALL_VALUE)) return
            bundle.putString(Config.CALL_VALUE, "0")
            bundle.putInt(Config.CALL_GENERATION_INDEX, -1)
            svc.note(uid, key)
            Logx.v { "spoofed $key for uid $uid via ${f.member.name}" }
        } catch (t: Throwable) {
            Logx.e("setting getter spoof failed", t)
        }
    }

    private fun onProviderQuery(f: Frame) {
        if (!queryHookSeen) {
            queryHookSeen = true
            Logx.i("query hook live (service=${service != null})")
        }
        f.proceed()
        try {
            val svc = service ?: return
            if (!svc.spoofingEnabled() || !svc.config.coverQueryPath) return
            val uid = callingUid() ?: return
            if (!svc.isTarget(uid)) return
            val cursor = f.result as? Cursor ?: return
            val replaced = rewriteCursor(cursor) ?: return
            f.result = replaced
            svc.note(uid, "query")
            Logx.v { "spoofed query cursor for uid $uid" }
        } catch (t: Throwable) {
            Logx.e("query spoof failed", t)
        }
    }

    private fun rewriteCursor(cursor: Cursor): Cursor? {
        if (cursor.count <= 0) return null
        val columns = cursor.columnNames ?: return null
        val nameIdx = cursor.getColumnIndex("name")
        val valueIdx = cursor.getColumnIndex("value")
        if (nameIdx < 0 || valueIdx < 0) return null

        val position = cursor.position
        val rows = ArrayList<Array<Any?>>(cursor.count)
        var hit = false
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            val row = arrayOfNulls<Any?>(columns.size)
            for (i in columns.indices) {
                row[i] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> cursor.getString(i)
                }
            }
            val override = spoofValues[row[nameIdx] as? String]
            if (override != null) {
                row[valueIdx] = override
                hit = true
            }
            rows.add(row)
        }
        cursor.moveToPosition(position)
        if (!hit) return null

        val matrix = MatrixCursor(columns, rows.size)
        for (row in rows) matrix.addRow(row)
        runCatching { cursor.extras?.let { matrix.extras = it } }
        runCatching { cursor.close() }
        return matrix
    }

    private fun hookNotificationManager() {
        val nm = XHook.findClass("android.app.NotificationManager") ?: return
        var count = 0
        for (name in arrayOf("notify", "notifyAsUser")) {
            count += XHook.hookAll(nm, name, 0, ::onNotify)
        }
        Logx.i("notification wrapper hooked: $count methods")
    }

    private fun hookNotificationManagerService() {
        val loader = systemServerClassLoader()
        if (!waitForService("notification")) {
            Logx.e("notification service never appeared")
            return
        }
        val nms = XHook.findClass(NMS_CLASS, loader) ?: run {
            Logx.e("NotificationManagerService not found")
            return
        }
        val count = XHook.hookAll(nms, "enqueueNotificationInternal", 0, ::onNotify)
        Logx.i("notification service hooked: $count methods")
    }

    private fun ensureService(provider: ContentProvider?, fallback: Context? = null) {
        if (service != null) return
        val context = fallback
            ?: runCatching { provider?.context }.getOrNull()
            ?: systemContext()
        if (context == null) {
            Logx.e("no context available, the service cannot start")
            return
        }
        val created = DuckService(context)
        service = created
        Logx.i("service ready: manager appId=${created.callerAppId} targets=${created.config.targets.size}")
    }

    private fun pollForSettingsProvider() {
        repeat(240) {
            if (settingsHooked) return
            val provider = runCatching { localProvider(Config.SETTINGS_AUTHORITY) }.getOrNull()
            if (provider != null) {
                runCatching {
                    ensureService(provider as? ContentProvider)
                    hookSettingsProvider(provider.javaClass)
                }.onFailure { Logx.e("late settings provider hook failed", it) }
                return
            }
            Thread.sleep(500)
        }
        if (!settingsHooked) Logx.e("settings provider never appeared")
    }

    private fun currentActivityThread(): Any? =
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)

    private fun systemContext(): Context? = runCatching {
        val at = currentActivityThread() ?: return null
        at.javaClass.getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(at) as? Context
    }.getOrNull()

    private fun localProvider(authority: String): Any? {
        val at = currentActivityThread() ?: return null
        val field = at.javaClass.getDeclaredField("mLocalProvidersByName").apply { isAccessible = true }
        val map = field.get(at) as? Map<*, *> ?: return null
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
            Logx.v { "local provider: ${names?.joinToString(",")} ${local.javaClass.name}" }
            if (names?.any { it == authority } == true || local.javaClass.name == SETTINGS_PROVIDER) {
                return local
            }
        }
        return null
    }

    private fun systemServerClassLoader(): ClassLoader? = try {
        Class.forName("com.android.internal.os.ZygoteInit")
            .getDeclaredMethod("getOrCreateSystemServerClassLoader")
            .apply { isAccessible = true }
            .invoke(null) as? ClassLoader
    } catch (t: Throwable) {
        Logx.e("system server class loader unavailable", t)
        null
    }

    private fun waitForService(name: String): Boolean {
        return try {
            val getService = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
            repeat(600) {
                if (getService.invoke(null, name) != null) return true
                Thread.sleep(250)
            }
            false
        } catch (t: Throwable) {
            Logx.e("waitForService($name) failed", t)
            false
        }
    }

    private fun onNotify(f: Frame) {
        val svc = service
        val hiding = svc?.hidingNotifications() ?: (!ModuleConfig.disabled && ModuleConfig.config.hideNotif)
        if (!hiding) {
            f.proceed()
            return
        }
        val notification = f.args.firstOrNull { it is Notification } as? Notification
        if (notification != null && isAdbNotification(notification)) {
            svc?.let { it.notifBlocked++ }
            Logx.v { "swallowed adb notification from ${f.member.name}" }
            return
        }
        f.proceed()
    }

    private fun isAdbNotification(n: Notification): Boolean {
        try {
            if (n.channelId in Config.ADB_CHANNELS) return true
        } catch (_: Throwable) {
        }
        try {
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (title != null && title in adbTitles) return true
        } catch (_: Throwable) {
        }
        return false
    }

    private val adbTitles: Set<String> by lazy {
        val res = Resources.getSystem()
        arrayOf("adb_active_notification_title", "adb_wifi_active_notification_title")
            .mapNotNull { name ->
                val id = res.getIdentifier(name, "string", "android")
                if (id != 0) runCatching { res.getString(id) }.getOrNull() else null
            }
            .toSet()
    }
}
