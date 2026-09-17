package com.strawing.duckusb.common

import android.database.Cursor
import android.database.MatrixCursor
import com.strawing.duckusb.common.Config

object CursorSpoof {

    fun rewrite(cursor: Cursor, fallbackKey: String?): Cursor? {
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
            val name = if (nameIdx >= 0) row[nameIdx] as? String else fallbackKey
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
}
