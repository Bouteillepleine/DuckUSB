package com.strawing.duckusb.zygisk

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.strawing.duckusb.ui.Theming

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Theming.restore(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        Thread { runCatching { Root.warm() } }.apply { isDaemon = true }.start()
    }
}
