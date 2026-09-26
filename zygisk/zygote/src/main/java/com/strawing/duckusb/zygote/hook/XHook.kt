package com.strawing.duckusb.zygote.hook

import com.strawing.duckusb.zygote.util.Logx
import java.lang.reflect.Executable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class Frame(
    val member: Executable,
    private val hooker: Hooker,
    private val raw: Array<Any?>,
) {
    private val static = Modifier.isStatic(member.modifiers)
    private val offset = if (static) 0 else 1

    var proceeded = false
        private set

    var result: Any? = null

    val thisObject: Any?
        get() = if (static) null else raw.getOrNull(0)

    val argCount: Int
        get() = raw.size - offset

    val returnType: Class<*>
        get() = (member as? Method)?.returnType ?: Void.TYPE

    fun arg(index: Int): Any? =
        if (index < 0 || index >= argCount) null else raw[offset + index]

    fun setArg(index: Int, value: Any?) {
        if (index < 0 || index >= argCount) return
        raw[offset + index] = value
    }

    val args: List<Any?>
        get() = (0 until argCount).map { arg(it) }

    fun proceed(): Any? {
        val backup = hooker.backup ?: throw IllegalStateException("no backup for ${member.name}")
        val params = if (static) raw else raw.copyOfRange(1, raw.size)
        result = backup.invoke(thisObject, *params)
        proceeded = true
        return result
    }
}

class Hooker(private val member: Executable, private val body: (Frame) -> Unit) {

    @Volatile
    @JvmField
    var backup: Method? = null

    fun callback(args: Array<Any?>): Any? {
        val frame = Frame(member, this, args)
        try {
            body(frame)
        } catch (t: Throwable) {
            Logx.e("hook body failed on ${member.name}", t)
            if (!frame.proceeded) return frame.proceed()
        }
        val type = frame.returnType
        if (!frame.proceeded && frame.result == null && type != Void.TYPE && type.isPrimitive) {
            Logx.e("hook on ${member.name} returned null for ${type.name}, falling through")
            return frame.proceed()
        }
        return frame.result
    }
}

class Relay(private val callback: InvocationHandler) {
    fun dispatch(args: Array<Any?>): Any? = callback.invoke(null, null, args)
}

object XHook {

    private const val ENGINE_KEY = "runtime.transform.engine"

    @Volatile
    private var ready = false

    @Volatile
    private var owns = false

    @Volatile
    private var adopted: InvocationHandler? = null

    private val callbackMethod: Method by lazy {
        Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
            .apply { isAccessible = true }
    }

    private val relayMethod: Method by lazy {
        Relay::class.java.getDeclaredMethod("dispatch", Array<Any?>::class.java)
            .apply { isAccessible = true }
    }

    private val engine = InvocationHandler { _, method, args ->
        val target = method as? Method
        val callback = args?.getOrNull(0) as? InvocationHandler
        if (target == null || callback == null) null else serve(target, callback)
    }

    fun engineMode(): String = when {
        !ready -> "none"
        owns -> "own"
        else -> "adopted"
    }

    fun prepare(): Boolean {
        if (ready) return true
        return InitLock.serialized {
            if (ready) return@serialized true
            val existing = runCatching {
                System.getProperties()[ENGINE_KEY] as? InvocationHandler
            }.getOrNull()
            if (existing != null) {
                adopted = existing
                ready = true
                Logx.i("adopted the hook engine another module already started")
                return@serialized true
            }
            val started = try {
                Native.initHooking()
            } catch (t: Throwable) {
                Logx.e("native hooking unavailable", t)
                false
            }
            if (started) {
                owns = true
                ready = true
                runCatching { System.getProperties()[ENGINE_KEY] = engine }
                    .onFailure { Logx.e("could not publish the hook engine", it) }
                Logx.i("started the hook engine and published it")
            } else {
                Logx.e("the hook engine would not start")
            }
            started
        }
    }

    private fun serve(target: Method, callback: InvocationHandler): Method? {
        if (!owns) return null
        return try {
            val relay = Relay(callback)
            val backup = Native.hookMethod(target, relay, relayMethod) ?: return null
            backup.isAccessible = true
            backup
        } catch (t: Throwable) {
            Logx.e("could not serve a hook on ${target.declaringClass.name}.${target.name}", t)
            null
        }
    }

    fun hook(target: Executable, body: (Frame) -> Unit): Boolean {
        if (!prepare()) return false
        val borrowed = adopted
        return if (borrowed != null) hookBorrowed(target, body, borrowed)
        else hookOwn(target, body)
    }

    private fun hookOwn(target: Executable, body: (Frame) -> Unit): Boolean = try {
        val hooker = Hooker(target, body)
        val backup = Native.hookMethod(target, hooker, callbackMethod)
        if (backup == null) {
            Logx.e("hook refused on ${target.declaringClass.name}.${target.name}")
            false
        } else {
            backup.isAccessible = true
            hooker.backup = backup
            true
        }
    } catch (t: Throwable) {
        Logx.e("hook failed on ${target.declaringClass.name}.${target.name}", t)
        false
    }

    private fun hookBorrowed(
        target: Executable,
        body: (Frame) -> Unit,
        borrowed: InvocationHandler,
    ): Boolean {
        val method = target as? Method ?: return false
        return try {
            val hooker = Hooker(target, body)
            val callback = InvocationHandler { _, _, raw ->
                @Suppress("UNCHECKED_CAST")
                hooker.callback((raw ?: emptyArray()) as Array<Any?>)
            }
            val backup = borrowed.invoke(null, method, arrayOf<Any?>(callback)) as? Method
            if (backup == null) {
                Logx.e("the other module refused a hook on ${method.declaringClass.name}.${method.name}")
                false
            } else {
                backup.isAccessible = true
                hooker.backup = backup
                true
            }
        } catch (t: Throwable) {
            Logx.e("borrowed hook failed on ${method.declaringClass.name}.${method.name}", t)
            false
        }
    }

    fun hookAll(clazz: Class<*>, name: String, minParams: Int = 0, body: (Frame) -> Unit): Int {
        var count = 0
        for (m in methodsOf(clazz)) {
            if (m.name != name) continue
            if (m.parameterCount < minParams) continue
            if (hook(m, body)) count++
        }
        return count
    }

    fun methodsOf(clazz: Class<*>): Array<out Method> =
        runCatching { clazz.declaredMethods }.getOrElse { emptyArray() }

    fun findClass(name: String, loader: ClassLoader? = null): Class<*>? = try {
        Class.forName(name, false, loader ?: XHook::class.java.classLoader)
    } catch (_: Throwable) {
        null
    }
}
