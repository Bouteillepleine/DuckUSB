package com.strawing.duckprobe

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log

class ProbeActivity : Activity() {

    private val keys = listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled")
    private val props = listOf("sys.usb.config", "sys.usb.state", "init.svc.adbd", "persist.sys.usb.config")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = StringBuilder()
        for (key in keys) {
            out.append("$key getString=${getter(key)} path=${pathQuery(key)} selection=${selectionQuery(key)} bulk=${bulkQuery(key)}\n")
        }
        for (prop in props) {
            out.append("$prop native=${property(prop)} exec=${execGetprop(prop)}\n")
        }
        for (line in out.toString().trim().split("\n")) Log.i(TAG, line)
        finish()
    }

    private fun getter(key: String): String = try {
        Settings.Global.getString(contentResolver, key) ?: "null"
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun pathQuery(key: String): String = try {
        contentResolver.query(Uri.parse("content://settings/global/$key"), null, null, null, null)
            .use { readValue(it, key) }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun selectionQuery(key: String): String = try {
        contentResolver.query(
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
        contentResolver.query(Uri.parse("content://settings/global"), null, null, null, null)
            .use { readValue(it, key) }
    } catch (t: Throwable) {
        "err:${t.javaClass.simpleName}"
    }

    private fun readValue(cursor: android.database.Cursor?, key: String): String {
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

    companion object {
        private const val TAG = "DuckProbe"
    }
}
