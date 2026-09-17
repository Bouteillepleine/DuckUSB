package com.strawing.duckusb.zygote.hook

import com.strawing.duckusb.zygote.util.Logx
import java.lang.reflect.Executable
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

    val args: List<Any?>
        get() = (0 until argCount).map { arg(it) }

    fun proceed(): Any? {
        val backup = hooker.backup ?: return null
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
        return frame.result
    }
}

object XHook {

    @Volatile
    private var ready = false

    private val callbackMethod: Method by lazy {
        Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
            .apply { isAccessible = true }
    }

    fun prepare(): Boolean {
        if (ready) return true
        ready = try {
            Native.initHooking()
        } catch (t: Throwable) {
            Logx.e("native hooking unavailable", t)
            false
        }
        Logx.i("lsplant init: $ready")
        return ready
    }

    fun hook(target: Executable, body: (Frame) -> Unit): Boolean {
        if (!prepare()) return false
        return try {
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
