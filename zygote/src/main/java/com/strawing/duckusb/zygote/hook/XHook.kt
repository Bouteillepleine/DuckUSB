package com.strawing.duckusb.zygote.hook

import com.strawing.duckusb.zygote.util.Logx
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

class Frame(
    private val original: MethodHandle,
    private val frame: EmulatedStackFrame,
    val member: Executable,
) {
    private val static = Modifier.isStatic(member.modifiers)
    private val offset = if (static) 0 else 1

    var proceeded = false
        private set

    val thisObject: Any?
        get() = if (static) null else raw(0)

    val argCount: Int
        get() = frame.type().parameterCount() - offset

    val returnType: Class<*>
        get() = frame.type().returnType()

    fun arg(index: Int): Any? =
        if (index < 0 || index >= argCount) null else raw(offset + index)

    val args: List<Any?>
        get() = (0 until argCount).map { arg(it) }

    fun proceed(): Any? {
        Transformers.invokeExact(original, frame)
        proceeded = true
        return result
    }

    var result: Any?
        get() = if (returnType == Void.TYPE) null else frame.accessor().getValue(RETURN_VALUE_IDX)
        set(value) {
            if (returnType != Void.TYPE) frame.accessor().setValue(RETURN_VALUE_IDX, value)
        }

    private fun raw(index: Int): Any? {
        val a = frame.accessor()
        return when (a.getArgumentShorty(index)) {
            'L' -> a.getReference<Any?>(index)
            'Z' -> a.getBoolean(index)
            'B' -> a.getByte(index)
            'C' -> a.getChar(index)
            'S' -> a.getShort(index)
            'I' -> a.getInt(index)
            'J' -> a.getLong(index)
            'F' -> a.getFloat(index)
            'D' -> a.getDouble(index)
            else -> null
        }
    }
}

object XHook {

    fun hook(target: Executable, body: (Frame) -> Unit): Boolean {
        return try {
            Hooks.hook(
                target,
                Hooks.EntryPointType.CURRENT,
                HookTransformer { original, esf ->
                    val f = Frame(original, esf, target)
                    try {
                        body(f)
                    } catch (t: Throwable) {
                        Logx.e("hook body failed on ${target.name}", t)
                        if (!f.proceeded) f.proceed()
                    }
                },
                Hooks.EntryPointType.CURRENT,
            )
            true
        } catch (t: Throwable) {
            Logx.e("hook install failed on ${target.declaringClass.name}.${target.name}", t)
            false
        }
    }

    fun hookAll(clazz: Class<*>, name: String, minParams: Int = 0, body: (Frame) -> Unit): Int {
        var count = 0
        for (m in clazz.declaredMethods) {
            if (m.name != name) continue
            if (m.parameterCount < minParams) continue
            if (hook(m, body)) count++
        }
        return count
    }

    fun deoptimizeAll(clazz: Class<*>, name: String): Int {
        var count = 0
        for (m in clazz.declaredMethods) {
            if (m.name != name) continue
            try {
                Hooks.deoptimize(m)
                count++
            } catch (t: Throwable) {
                Logx.e("deoptimize failed on ${clazz.name}.$name", t)
            }
        }
        return count
    }

    fun findClass(name: String, loader: ClassLoader? = null): Class<*>? = try {
        Class.forName(name, false, loader ?: XHook::class.java.classLoader)
    } catch (_: Throwable) {
        null
    }
}
