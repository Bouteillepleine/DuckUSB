package com.strawing.duckusb.zygote.hook

object InitLock {

    private const val KEY = "duck.hook.init.monitor"

    private fun monitor(): Any {
        val props = System.getProperties()
        synchronized(props) {
            props[KEY]?.let { return it }
            val fresh = Any()
            props[KEY] = fresh
            return fresh
        }
    }

    fun <T> serialized(body: () -> T): T = synchronized(monitor()) { body() }
}
