package com.strawing.duckusb.zygisk

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.strawing.duckusb.IDuckService
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.DuckConfig

object ServiceClient {

    @Volatile
    private var cached: IDuckService? = null

    fun get(context: Context): IDuckService? {
        cached?.let {
            if (it.asBinder().isBinderAlive) return it
            cached = null
        }
        val binder = try {
            context.contentResolver
                .call(Uri.parse(Bridge.URI), Bridge.METHOD, Bridge.ARG, null)
                ?.getBinder(Bridge.KEY_BINDER)
        } catch (_: Throwable) {
            null
        } ?: return null
        return IDuckService.Stub.asInterface(binder).also { cached = it }
    }

    fun state(context: Context): Bundle? = try {
        get(context)?.state
    } catch (_: Throwable) {
        null
    }

    fun records(context: Context): List<Bundle> = try {
        get(context)?.records ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun clearRecords(context: Context) {
        runCatching { get(context)?.clearRecords() }
    }

    fun push(context: Context, config: DuckConfig): Boolean {
        val service = get(context) ?: return false
        return runCatching {
            service.pushConfig(Bundle().apply {
                putBoolean(Bridge.STATE_PAUSED, config.paused)
                putBoolean(Bridge.STATE_SPOOF_SETTINGS, config.spoofSettings)
                putBoolean(Bridge.STATE_SPOOF_PROPS, config.spoofProps)
                putBoolean(Bridge.STATE_HIDE_NOTIF, config.hideNotif)
                putBoolean(Bridge.STATE_COVER_QUERY, config.coverQueryPath)
                putStringArrayList(Bridge.STATE_TARGETS, ArrayList(config.targets))
            })
            true
        }.getOrDefault(false)
    }
}
