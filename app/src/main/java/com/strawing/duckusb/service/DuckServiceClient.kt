package com.strawing.duckusb.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.strawing.duckusb.IDuckService

/**
 * App-side retrieval of the system_server service.
 *
 * Note this needs NO Xposed scope on our own app: it is a plain ContentResolver call. A
 * successful retrieval proves the *framework* half is live in system_server, which is a
 * strictly stronger "module active" signal than the self-hook on isModuleActive() (that one
 * only proves the module loaded into our own process).
 */
object DuckServiceClient {

    @Volatile private var cached: IDuckService? = null

    fun get(context: Context): IDuckService? {
        cached?.let { if (it.asBinder().isBinderAlive) return it else cached = null }
        val binder = try {
            context.contentResolver
                .call(Uri.parse(Bridge.URI), Bridge.METHOD, Bridge.ARG, null)
                ?.getBinder(Bridge.KEY_BINDER)
        } catch (_: Throwable) {
            null
        } ?: return null
        return IDuckService.Stub.asInterface(binder).also { cached = it }
    }

    /** Push the current toggles to system_server. No-op when framework mode isn't live. */
    fun pushConfig(context: Context, paused: Boolean, spoofSettings: Boolean, hideNotif: Boolean) {
        val svc = get(context) ?: return
        runCatching {
            svc.pushConfig(Bundle().apply {
                putBoolean("paused", paused)
                putBoolean("spoofSettings", spoofSettings)
                putBoolean("hideNotif", hideNotif)
            })
        }
    }
}
