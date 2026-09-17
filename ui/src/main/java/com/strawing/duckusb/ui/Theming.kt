package com.strawing.duckusb.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class NightMode(val flag: Int, val icon: String, val label: String) {
    FOLLOW(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "🌗", "Follow system"),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "☀️", "Light"),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, "🌙", "Dark"),
    ;

    fun next(): NightMode = entries[(ordinal + 1) % entries.size]
}

object Theming {

    private const val PREFS = "duckusb"
    private const val KEY = "night_mode"

    fun current(context: Context): NightMode {
        val stored = prefs(context).getString(KEY, null) ?: return NightMode.FOLLOW
        return runCatching { NightMode.valueOf(stored) }.getOrDefault(NightMode.FOLLOW)
    }

    fun apply(context: Context, mode: NightMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.flag)
    }

    fun restore(context: Context) {
        AppCompatDelegate.setDefaultNightMode(current(context).flag)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
