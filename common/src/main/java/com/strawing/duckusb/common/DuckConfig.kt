package com.strawing.duckusb.common

import org.json.JSONArray
import org.json.JSONObject

data class DuckConfig(
    var paused: Boolean = false,
    var spoofSettings: Boolean = true,
    var spoofProps: Boolean = true,
    var hideNotif: Boolean = true,
    var coverQueryPath: Boolean = true,
    var verboseLog: Boolean = false,
    var targets: MutableSet<String> = LinkedHashSet(),
) {
    fun isTarget(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg in Config.SPARE_PACKAGES) return false
        return pkg in targets
    }

    fun toJson(): String = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_PAUSED, paused)
        put(KEY_SPOOF_SETTINGS, spoofSettings)
        put(KEY_SPOOF_PROPS, spoofProps)
        put(KEY_HIDE_NOTIF, hideNotif)
        put(KEY_COVER_QUERY, coverQueryPath)
        put(KEY_VERBOSE, verboseLog)
        put(KEY_TARGETS, JSONArray(targets.toList()))
    }.toString(2)

    companion object {
        const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_PAUSED = "paused"
        private const val KEY_SPOOF_SETTINGS = "spoofSettings"
        private const val KEY_SPOOF_PROPS = "spoofProps"
        private const val KEY_HIDE_NOTIF = "hideNotif"
        private const val KEY_COVER_QUERY = "coverQueryPath"
        private const val KEY_VERBOSE = "verboseLog"
        private const val KEY_TARGETS = "targets"

        fun parse(text: String?): DuckConfig {
            if (text.isNullOrBlank()) return DuckConfig()
            return try {
                val o = JSONObject(text)
                val targets = LinkedHashSet<String>()
                o.optJSONArray(KEY_TARGETS)?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotEmpty() }?.let(targets::add)
                }
                DuckConfig(
                    paused = o.optBoolean(KEY_PAUSED, false),
                    spoofSettings = o.optBoolean(KEY_SPOOF_SETTINGS, true),
                    spoofProps = o.optBoolean(KEY_SPOOF_PROPS, true),
                    hideNotif = o.optBoolean(KEY_HIDE_NOTIF, true),
                    coverQueryPath = o.optBoolean(KEY_COVER_QUERY, true),
                    verboseLog = o.optBoolean(KEY_VERBOSE, false),
                    targets = targets,
                )
            } catch (_: Throwable) {
                DuckConfig()
            }
        }
    }
}
