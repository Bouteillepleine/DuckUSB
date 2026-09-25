package com.strawing.duckusb.zygote.hook

import android.os.Bundle
import com.strawing.duckusb.zygote.util.Logx
import java.lang.reflect.InvocationHandler
import java.util.concurrent.ConcurrentHashMap

object BridgeRegistry {

    private const val KEY = "duck.hook.bridge"

    private val served = ConcurrentHashMap<String, InvocationHandler>()

    private val registry = InvocationHandler { _, _, args ->
        val name = args?.getOrNull(0) as? String
        val handler = args?.getOrNull(1) as? InvocationHandler
        if (name == null || handler == null) {
            false
        } else {
            served[name] = handler
            Logx.i("registered the bridge of $name")
            true
        }
    }

    fun publish(name: String, handler: InvocationHandler) {
        served[name] = handler
        runCatching { System.getProperties()[KEY] = registry }
            .onSuccess { Logx.i("published the bridge registry") }
            .onFailure { Logx.e("could not publish the bridge registry", it) }
    }

    fun registerWithOwner(name: String, handler: InvocationHandler): Boolean {
        val owner = runCatching {
            System.getProperties()[KEY] as? InvocationHandler
        }.getOrNull()
        if (owner == null || owner === registry) return false
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
