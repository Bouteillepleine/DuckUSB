package com.strawing.duckusb.zygote.hook

import android.os.Bundle
import com.strawing.duckusb.zygote.util.Logx
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object BridgeRegistry {

    private const val KEY = "provider.bridge.registry"

    private val served = ConcurrentHashMap<String, InvocationHandler>()

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
                    served[name] = handler
                    Logx.i("registered the bridge of $name")
                    true
                }
            }
        }
    }

    private val published: InvocationHandler by lazy {
        for (loader in listOf(InvocationHandler::class.java.classLoader, BridgeRegistry::class.java.classLoader)) {
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

    fun publish(name: String, handler: InvocationHandler) {
        served[name] = handler
        runCatching { System.getProperties()[KEY] = published }
            .onSuccess { Logx.i("published the bridge registry") }
            .onFailure { Logx.e("could not publish the bridge registry", it) }
    }

    fun registerWithOwner(name: String, handler: InvocationHandler): Boolean {
        val owner = runCatching {
            System.getProperties()[KEY] as? InvocationHandler
        }.getOrNull()
        if (owner == null || owner === published || owner === registry) return false
        return runCatching {
            owner.invoke(null, null, arrayOf<Any?>(name, handler)) == true
        }.getOrDefault(false)
    }

    fun serve(args: List<Any?>, uid: Int): Bundle? {
        if (served.isEmpty()) return null
        for ((name, handler) in served) {
            if (args.none { it == name }) continue
            val answer = runCatching { handler.invoke(null, null, arrayOf<Any?>(uid)) }.getOrNull()
            if (answer is Bundle) return answer
        }
        return null
    }
}
