package com.strawing.duckusb

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Holds the libxposed framework service for the UI.
 *
 * The binder does not arrive synchronously — it is delivered to a ContentProvider inside the
 * service library and handed on from a binder thread, which may be before or after the first
 * activity is created. [XposedServiceHelper] also keeps room for exactly ONE listener process
 * wide, so the Application registers itself and fans out to whoever is on screen.
 *
 * `service == null` is the honest answer to "is the module active": under the legacy API the UI
 * had to infer that from a self-hook that only proved the module was injected into its own
 * process, which is true whether or not anything is scoped.
 */
class DuckApp : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        /** Null until the framework binds, and again if it dies. */
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        /** Registers [l] and calls it immediately with the current state. */
        fun addListener(l: (XposedService?) -> Unit) {
            listeners.add(l)
            l(service)
        }

        fun removeListener(l: (XposedService?) -> Unit) = listeners.remove(l)

        private fun dispatch(svc: XposedService?) = listeners.forEach { it(svc) }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        Companion.service = null
        dispatch(null)
    }
}
