package com.strawing.duckusb.common

import android.util.Log
import java.lang.reflect.InvocationHandler
import java.util.concurrent.ConcurrentHashMap

object SpoofRegistry {

    private const val TAG = "DuckUSB"
    private const val KEY = "duck.hook.settings.filter"

    const val CALL = "call"
    const val QUERY = "query"

    private val filters = ConcurrentHashMap<String, InvocationHandler>()

    private val registry = InvocationHandler { _, _, args ->
        val name = args?.getOrNull(0) as? String
        val handler = args?.getOrNull(1) as? InvocationHandler
        if (name == null || handler == null) {
            false
        } else {
            filters[name] = handler
            Log.i(TAG, "registered the settings filter of $name")
            true
        }
    }

    fun publish() {
        runCatching { System.getProperties()[KEY] = registry }
            .onFailure { Log.e(TAG, "could not publish the settings filter registry", it) }
    }

    fun registerWithOwner(name: String, handler: InvocationHandler): Boolean {
        val owner = runCatching {
            System.getProperties()[KEY] as? InvocationHandler
        }.getOrNull()
        if (owner == null || owner === registry) return false
        val ok = runCatching {
            owner.invoke(null, null, arrayOf<Any?>(name, handler)) == true
        }.getOrDefault(false)
        Log.i(TAG, "handing our settings filter to the module that owns the provider hook: $ok")
        return ok
    }

    fun apply(kind: String, uid: Int, args: List<Any?>, result: Any?): Any? {
        if (filters.isEmpty()) return null
        var current = result
        var changed = false
        for ((name, handler) in filters) {
            val next = runCatching {
                handler.invoke(null, null, arrayOf<Any?>(kind, uid, args.toTypedArray(), current))
            }.onFailure { Log.e(TAG, "settings filter of $name failed", it) }.getOrNull() ?: continue
            current = next
            changed = true
        }
        return if (changed) current else null
    }
}
