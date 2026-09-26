package com.strawing.duckusb.common

import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object SpoofRegistry {

    private const val TAG = "DuckUSB"
    private const val KEY = "provider.filter.registry"

    const val CALL = "call"
    const val QUERY = "query"

    private val filters = ConcurrentHashMap<String, InvocationHandler>()

    private val registry = InvocationHandler { proxy, method, args ->
        when (method?.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "java.lang.Object@" + Integer.toHexString(System.identityHashCode(proxy))
            else -> {
                val call = unwrap(args)
                val name = call.getOrNull(0) as? String
                val handler = call.getOrNull(1) as? InvocationHandler
                if (name == null || handler == null) {
                    false
                } else {
                    filters[name] = handler
                    Log.i(TAG, "registered the settings filter of $name")
                    true
                }
            }
        }
    }

    private val published: InvocationHandler by lazy {
        for (loader in listOf(InvocationHandler::class.java.classLoader, SpoofRegistry::class.java.classLoader)) {
            val proxy = runCatching {
                Proxy.newProxyInstance(
                    loader, arrayOf(InvocationHandler::class.java), registry
                ) as InvocationHandler
            }.getOrNull()
            if (proxy != null) return@lazy proxy
        }
        registry
    }

    private fun unwrap(args: Array<out Any?>?): List<Any?> {
        if (args == null) return emptyList()
        val nested = args.getOrNull(2) as? Array<*>
        return nested?.toList() ?: args.toList()
    }

    fun publish() {
        runCatching { System.getProperties()[KEY] = published }
            .onFailure { Log.e(TAG, "could not publish the settings filter registry", it) }
    }

    fun registerWithOwner(name: String, handler: InvocationHandler): Boolean {
        val owner = runCatching {
            System.getProperties()[KEY] as? InvocationHandler
        }.getOrNull()
        if (owner == null || owner === published || owner === registry) return false
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
