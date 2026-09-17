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

    @Volatile
    var blocked = 0
        private set

    fun init() {
        if (ModuleConfig.disabled) {
            Logx.i("kill switch present, no hooks installed")
            return
        }
        if (!ModuleConfig.config.hideNotif) {
            Logx.i("notification suppressor off, nothing to install")
            return
        }
        hookNotificationManager()
        thread(name = "duckusb-nms", isDaemon = true) { hookNotificationManagerService() }
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
        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused || !config.hideNotif) {
            f.proceed()
            return
        }
        val notification = f.args.firstOrNull { it is Notification } as? Notification
        if (notification != null && isAdbNotification(notification)) {
            blocked++
            Logx.i("swallowed the adb notification from ${f.member.name}")
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
