package com.strawing.duckprobe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log

private const val TAG = "DuckProbe"

class Probe(private val resolver: ContentResolver) {

    private val keys = listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled")
    private val props = listOf("sys.usb.config", "sys.usb.state", "init.svc.adbd", "persist.sys.usb.config")

    fun run() {
        for (key in keys) {
            Log.i(
                TAG,
                "$key getString=${getter(key)} path=${pathQuery(key)} selection=${selectionQuery(key)} bulk=${bulkQuery(key)}"
            )
        }
        for (prop in props) {
            Log.i(TAG, "$prop native=${property(prop)} exec=${execGetprop(prop)}")
        }
    }

    private fun getter(key: String): String = try {
        Settings.Global.getString(resolver, key) ?: "null"
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun pathQuery(key: String): String = try {
        resolver.query(Uri.parse("content://settings/global/$key"), null, null, null, null)
            .use { readValue(it, key) }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun selectionQuery(key: String): String = try {
        resolver.query(
            Uri.parse("content://settings/global"),
            null,
            "name=?",
            arrayOf(key),
            null,
        ).use { readValue(it, key) }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun bulkQuery(key: String): String = try {
        resolver.query(Uri.parse("content://settings/global"), null, null, null, null)
            .use { readValue(it, key) }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun readValue(cursor: Cursor?, key: String): String {
        if (cursor == null) return "nocursor"
        val nameIdx = cursor.getColumnIndex("name")
        val valueIdx = cursor.getColumnIndex("value")
        if (valueIdx < 0) return "nocolumn"
        while (cursor.moveToNext()) {
            if (nameIdx < 0 || cursor.getString(nameIdx) == key) {
                return cursor.getString(valueIdx) ?: "null"
            }
        }
        return "absent"
    }

    private fun property(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.ifEmpty { "empty" } ?: "null"
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun execGetprop(key: String): String = try {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "empty" }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }
}

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Probe(context.contentResolver).run()
    }
}

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe(contentResolver).run()
        finish()
    }
}
