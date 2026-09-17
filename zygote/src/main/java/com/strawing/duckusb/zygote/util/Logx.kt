package com.strawing.duckusb.zygote.util

import android.util.Log

object Logx {
    private const val TAG = "DuckUSB"

    @Volatile
    var verbose = false

    fun i(msg: String) = Log.i(TAG, msg)

    fun v(msg: () -> String) {
        if (verbose) Log.i(TAG, msg())
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
    }
}
