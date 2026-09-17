package com.strawing.duckusb.zygote

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Settings
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.hook.Frame
import com.strawing.duckusb.zygote.hook.XHook
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig
import com.strawing.duckusb.zygote.util.NativeLib

object AppPart {

    private const val NAME_VALUE_CACHE = "android.provider.Settings\$NameValueCache"

    private val GETTERS = arrayOf("getInt", "getString", "getLong", "getFloat")

    @Volatile
    private var armed = false

    @Volatile
    private var settingsSpoofLive = false

    private fun active(): Boolean {
        val config = ModuleConfig.config
        return !ModuleConfig.disabled && !config.paused
    }

    fun preSpecialize(packageName: String?, moduleDir: String?) {
        if (packageName == null) return
        if (packageName == Config.PKG) {
            announceToManager()
            return
        }
        if (packageName in Config.SKIP_SPOOF_PROCESSES) return
        if (packageName in Config.SPARE_PACKAGES) return

        val config = ModuleConfig.config
        if (ModuleConfig.disabled || config.paused) return
        if (!config.isTarget(packageName)) {
            Logx.v { "$packageName is not a target, nothing installed" }
            return
        }

        armed = NativeLib.load(moduleDir)
        Logx.v { "native library loaded for $packageName: $armed" }
    }

    fun postSpecialize() {
        if (!armed) return
        val config = ModuleConfig.config
        if (config.spoofProps) {
            val ok = NativeProps.install(Config.PROP_OVERRIDES)
            Logx.i("property spoof installed=$ok")
        }
        if (config.spoofSettings) installSettingsSpoof()
    }

    private fun installSettingsSpoof() {
        var count = 0
        val cache = XHook.findClass(NAME_VALUE_CACHE)
        if (cache != null) {
            count += XHook.hookAll(cache, "getStringForUser", 0, ::onCachedRead)
        } else {
            Logx.e("NameValueCache not found")
        }
        for (name in arrayOf("android.provider.Settings\$Global", "android.provider.Settings\$Secure")) {
            val clazz = XHook.findClass(name) ?: continue
            for (getter in GETTERS) count += XHook.hookAll(clazz, getter, 0, ::onSettingsGetter)
        }
        if (ModuleConfig.config.coverQueryPath) {
            val resolver = XHook.findClass("android.content.ContentResolver")
            if (resolver != null) count += XHook.hookAll(resolver, "query", 2, ::onResolverQuery)
        }
        settingsSpoofLive = selfTest()
        Logx.i("client settings spoof: $count methods hooked, ${if (settingsSpoofLive) "live" else "DEAD"}")
    }

    private fun onCachedRead(f: Frame) {
        val key = f.args.firstOrNull { it is String } as? String
        if (key == null || key !in Config.SPOOF_KEYS || !active()) {
            f.proceed()
            return
        }
        f.result = "0"
    }

    private fun onSettingsGetter(f: Frame) {
        val key = f.args.firstOrNull { it is String } as? String
        if (key == null || key !in Config.SPOOF_KEYS || !active()) {
            f.proceed()
            return
        }
        when (f.returnType) {
            java.lang.Long.TYPE -> f.result = 0L
            java.lang.Float.TYPE -> f.result = 0f
            java.lang.Integer.TYPE -> f.result = 0
            String::class.java -> f.result = "0"
            else -> f.proceed()
        }
    }


    private fun onResolverQuery(f: Frame) {
        f.proceed()
        if (!active() || !ModuleConfig.config.coverQueryPath) return
        try {
            val uri = f.args.firstOrNull { it is Uri } as? Uri ?: return
            if (uri.authority != Config.SETTINGS_AUTHORITY) return
            val cursor = f.result as? Cursor ?: return
            val replaced = rewriteCursor(cursor, uri) ?: return
            f.result = replaced
        } catch (t: Throwable) {
            Logx.e("cursor spoof failed", t)
        }
    }

    private fun rewriteCursor(cursor: Cursor, uri: Uri): Cursor? {
        if (cursor.count <= 0) return null
        val columns = cursor.columnNames ?: return null
        val nameIdx = cursor.getColumnIndex("name")
        val valueIdx = cursor.getColumnIndex("value")
        if (valueIdx < 0) return null

        val position = cursor.position
        val rows = ArrayList<Array<Any?>>(cursor.count)
        var hit = false
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            val row = arrayOfNulls<Any?>(columns.size)
            for (i in columns.indices) {
                row[i] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> cursor.getString(i)
                }
            }
            val name = if (nameIdx >= 0) row[nameIdx] as? String else uri.lastPathSegment
            if (name != null && name in Config.SPOOF_KEYS) {
                row[valueIdx] = "0"
                hit = true
            }
            rows.add(row)
        }
        cursor.moveToPosition(position)
        if (!hit) return null

        val matrix = MatrixCursor(columns, rows.size)
        for (row in rows) matrix.addRow(row)
        runCatching { cursor.extras?.let { matrix.extras = it } }
        runCatching { cursor.close() }
        return matrix
    }

    private fun announceToManager() {
        runCatching { System.setProperty(Config.LIVE_PROPERTY, Config.MODULE_VERSION) }
    }

    private fun selfTest(): Boolean = try {
        Settings.Global.getString(null, Config.SPOOF_KEYS.first()) == "0"
    } catch (_: Throwable) {
        false
    }
}
