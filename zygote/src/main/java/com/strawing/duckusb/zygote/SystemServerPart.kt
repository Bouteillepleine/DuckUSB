package com.strawing.duckusb.zygote

import android.app.Notification
import android.content.res.Resources
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import kotlin.concurrent.thread

object SystemServerPart {

    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    private const val SETTLE_MS = 8000L

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
        if (count > 0) cancelAlreadyPosted()
    }

    private fun cancelAlreadyPosted() {
        val binder = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, "notification")
        }.getOrNull() ?: run {
            Logx.e("no notification service, cannot clear what was posted during boot")
            return
        }
        val service = runCatching {
            Class.forName("android.app.INotificationManager\$Stub")
                .getDeclaredMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder)
        }.getOrNull() ?: return

        val active = activeNotifications(service) ?: run {
            Logx.e("could not list active notifications")
            return
        }
        var cleared = 0
        for (sbn in active) {
            if (sbn == null) continue
            val notification = runCatching {
                sbn.javaClass.getMethod("getNotification").invoke(sbn) as? Notification
            }.getOrNull() ?: continue
            if (!isAdbNotification(notification)) continue
            val pkg = runCatching { sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String }.getOrNull() ?: continue
            val tag = runCatching { sbn.javaClass.getMethod("getTag").invoke(sbn) as? String }.getOrNull()
            val id = runCatching { sbn.javaClass.getMethod("getId").invoke(sbn) as? Int }.getOrNull() ?: continue
            val userId = runCatching { sbn.javaClass.getMethod("getUserId").invoke(sbn) as? Int }.getOrNull() ?: 0
            if (cancelOne(service, pkg, tag, id, userId)) {
                cleared++
                Logx.i("cleared the adb notification already posted by $pkg (id=$id)")
            }
        }
        if (cleared == 0) Logx.i("no adb notification was posted before arming")
    }

    private fun activeNotifications(service: Any): Array<*>? {
        for (m in service.javaClass.methods) {
            if (!m.name.startsWith("getActiveNotifications")) continue
            val args: Array<Any?> = when (m.parameterTypes.size) {
                1 -> arrayOf("android")
                2 -> arrayOf("android", null)
                else -> continue
            }
            val result = runCatching { m.invoke(service, *args) }.getOrNull()
            if (result is Array<*>) return result
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
