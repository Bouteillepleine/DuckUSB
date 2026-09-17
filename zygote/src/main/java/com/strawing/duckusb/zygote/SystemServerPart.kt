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
        Logx.i("notification suppressor armed: $count methods, titles=$adbTitles")
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
