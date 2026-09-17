package com.strawing.duckusb.zygote

import android.app.Notification
import android.content.res.Resources
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import java.lang.reflect.Field
import kotlin.concurrent.thread

object SystemServerPart {

    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    private const val SETTLE_MS = 8000L
    private const val SWEEP_PASSES = 20
    private const val SWEEP_INTERVAL_MS = 2000L

    @Volatile
    var blocked = 0
        private set

    @Volatile
    private var seen = false

    fun init() {
        if (ModuleConfig.disabled) {
            Logx.i("kill switch present, no hooks installed")
            return
        }
        val config = ModuleConfig.config
        if (!config.hookSystemServer) {
            Logx.i("system_server hooks are opt-in and disabled")
            return
        }
        if (!config.hideNotif && !config.frameworkMode) {
            Logx.i("nothing enabled for system_server")
            return
        }
        thread(name = "duckusb-server", isDaemon = true) { armAfterBoot() }
    }

    private fun armAfterBoot() {
        if (!waitForBootCompleted()) {
            Logx.e("boot never completed, notification suppressor not armed")
            return
        }
        Thread.sleep(SETTLE_MS)
        if (ModuleConfig.config.frameworkMode) {
            runCatching { FrameworkPart.arm() }
                .onFailure { Logx.e("framework mode failed to arm", it) }
        }
        if (!ModuleConfig.config.hideNotif) return
        val nms = XHook.findClass(NMS_CLASS, systemServerClassLoader()) ?: run {
            Logx.e("NotificationManagerService not found")
            return
        }
        var count = 0
        for (m in XHook.methodsOf(nms)) {
            if (m.name != "enqueueNotificationInternal") continue
            if (m.returnType != Void.TYPE) continue
            if (XHook.hook(m, ::onEnqueue)) count++
        }
        Logx.i("notification suppressor armed: $count methods, strings=$adbStrings")
        if (count > 0) sweepAlreadyPosted()
    }

    private fun sweepAlreadyPosted() {
        val service = notificationService() ?: run {
            Logx.e("no notification service, cannot clear what was posted during boot")
            return
        }
        val server = outerInstance(service)
        if (server == null) Logx.i("notification service internals unavailable, using the public list")
        var total = 0
        for (pass in 0 until SWEEP_PASSES) {
            total += sweepOnce(service, server, pass == 0)
            if (total > 0) break
            Thread.sleep(SWEEP_INTERVAL_MS)
        }
        if (total == 0) Logx.i("no adb notification was posted before arming")
    }

    private fun sweepOnce(service: Any, server: Any?, first: Boolean): Int {
        val posted = postedFromServer(server) ?: activeNotifications(service) ?: run {
            if (first) Logx.e("could not list the posted notifications")
            return 0
        }
        if (first) Logx.i("sweeping ${posted.size} posted notifications")
        var cleared = 0
        for (sbn in posted) {
            if (sbn == null) continue
            val notification = invoke(sbn, "getNotification") as? Notification ?: continue
            if (!isAdbNotification(notification)) continue
            val pkg = invoke(sbn, "getPackageName") as? String ?: continue
            val tag = invoke(sbn, "getTag") as? String
            val id = invoke(sbn, "getId") as? Int ?: continue
            val userId = invoke(sbn, "getUserId") as? Int ?: 0
            if (cancelOne(service, pkg, tag, id, userId)) {
                cleared++
                Logx.i("cleared the adb notification already posted by $pkg (id=$id)")
            } else {
                Logx.e("could not cancel the adb notification from $pkg (id=$id)")
            }
        }
        return cleared
    }

    private fun notificationService(): Any? {
        val binder = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "notification")
        }.getOrNull() ?: return null
        return runCatching {
            Class.forName("android.app.INotificationManager\$Stub")
                .getDeclaredMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder)
        }.getOrNull()
    }

    private fun outerInstance(service: Any): Any? {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (!f.name.startsWith("this$")) continue
                val value = runCatching { f.apply { isAccessible = true }.get(service) }.getOrNull()
                if (value != null) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun postedFromServer(server: Any?): List<Any?>? {
        if (server == null) return null
        val field = findField(server.javaClass, "mNotificationList") ?: return null
        val list = runCatching { field.get(server) as? Collection<*> }.getOrNull() ?: return null
        val copy = synchronized(list) { ArrayList<Any?>(list) }
        return copy.mapNotNull { record ->
            if (record == null) null else invoke(record, "getSbn") ?: fieldValue(record, "sbn")
        }
    }

    private fun findField(start: Class<*>, name: String): Field? {
        var cls: Class<*>? = start
        while (cls != null) {
            val found = cls
            val f = runCatching { found.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (f != null) return f
            cls = cls.superclass
        }
        return null
    }

    private fun fieldValue(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.let { runCatching { it.get(target) }.getOrNull() }

    private fun invoke(target: Any, name: String): Any? = runCatching {
        target.javaClass.getMethod(name).apply { isAccessible = true }.invoke(target)
    }.getOrNull()

    private fun activeNotifications(service: Any): List<Any?>? {
        for (m in service.javaClass.methods) {
            if (!m.name.startsWith("getActiveNotifications")) continue
            val args: Array<Any?> = when (m.parameterTypes.size) {
                1 -> arrayOf("android")
                2 -> arrayOf("android", null)
                else -> continue
            }
            val result = runCatching { m.invoke(service, *args) }.getOrNull()
            if (result is Array<*>) return result.toList()
        }
        return null
    }

    private fun cancelOne(service: Any, pkg: String, tag: String?, id: Int, userId: Int): Boolean {
        for (m in service.javaClass.methods) {
            if (m.name != "cancelNotificationWithTag") continue
            val types = m.parameterTypes
            val args: Array<Any?> = when (types.size) {
                5 -> arrayOf(pkg, pkg, tag, id, userId)
                4 -> arrayOf(pkg, tag, id, userId)
                6 -> arrayOf(pkg, pkg, null, tag, id, userId)
                else -> continue
            }
            if (runCatching { m.invoke(service, *args) }.isSuccess) return true
        }
        return false
    }

    private fun waitForBootCompleted(): Boolean {
        val get = runCatching {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: return false
        repeat(600) {
            val value = runCatching { get.invoke(null, "sys.boot_completed") as? String }.getOrNull()
            if (value == "1") return true
            Thread.sleep(1000)
        }
        return false
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

    private fun onEnqueue(f: Frame) {
        if (!seen) {
            seen = true
            Logx.i("notification hook live")
        }
        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused || !config.hideNotif) {
            f.proceed()
            return
        }
        val swallow = try {
            val notification = f.args.firstOrNull { it is Notification } as? Notification
            notification != null && isAdbNotification(notification)
        } catch (t: Throwable) {
            Logx.e("notification match failed", t)
            false
        }
        if (swallow) {
            blocked++
            FrameworkPart.service?.let { it.notifBlocked = it.notifBlocked + 1 }
            Logx.i("swallowed the adb notification")
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
            val extras = n.extras ?: return false
            for (key in arrayOf(Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT)) {
                val value = extras.getCharSequence(key)?.toString()?.trim() ?: continue
                if (value.isEmpty()) continue
                if (value in adbStrings) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private val adbStrings: Set<String> by lazy {
        val res = Resources.getSystem()
        arrayOf(
            "adb_active_notification_title",
            "adb_active_notification_message",
            "adb_wifi_active_notification_title",
            "adb_wifi_active_notification_message",
        ).mapNotNull { name ->
            val id = res.getIdentifier(name, "string", "android")
            if (id != 0) runCatching { res.getString(id).trim() }.getOrNull() else null
        }.filter { it.isNotEmpty() }.toSet()
    }
}
