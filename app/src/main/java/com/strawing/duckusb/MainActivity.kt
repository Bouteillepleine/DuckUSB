package com.strawing.duckusb

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MR
import com.strawing.duckusb.service.DuckServiceClient

/**
 * Config UI. Writes the three live toggles to a world-readable SharedPreferences file that
 * the Xposed hook re-reads on every call, so flipping a switch applies without a reboot
 * (at most force-stop the target app). All default ON.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        /** Remembers whether the caller list on the service card is expanded. */
        const val PREF_RECORDS_EXPANDED = "svc_records_expanded"

        /** "light" / "dark"; absent = follow the system. */
        const val PREF_THEME = "ui_theme"

        /** Scroll offset preserved across the recreate() that a theme switch triggers. */
        const val STATE_SCROLL_Y = "scroll_y"
    }

    private lateinit var prefs: SharedPreferences

    /** system_server service snapshot, fetched once per onCreate. Null = framework half not live. */
    /** Containers rebuilt in place on toggle, so scroll position survives. */
    private lateinit var statusHolder: LinearLayout
    private lateinit var controlsHolder: LinearLayout
    private lateinit var diagHolder: LinearLayout
    private lateinit var scroll: ScrollView

    private var svcState: Bundle? = null
    private var svcRecords: List<Bundle> = emptyList()

    // Theme roles resolved once (dynamic-color aware).
    private val cPrimary get() = attr(MR.attr.colorPrimary)
    private val cOnSurface get() = attr(MR.attr.colorOnSurface)
    private val cOnSurfaceVar get() = attr(MR.attr.colorOnSurfaceVariant)
    private val cSurfaceCard get() = attr(MR.attr.colorSurfaceContainer, attr(MR.attr.colorSurface))
    private val cOutline get() = attr(MR.attr.colorOutlineVariant)
    private val cPrimaryCont get() = attr(MR.attr.colorPrimaryContainer)
    private val cOnPrimaryCont get() = attr(MR.attr.colorOnPrimaryContainer)
    private val cErrorCont get() = attr(MR.attr.colorErrorContainer)
    private val cOnErrorCont get() = attr(MR.attr.colorOnErrorContainer)

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        prefs = try {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (t: Throwable) {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        }

        applySavedTheme()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        // Fetch the system_server service once, before building any card that reports on it.
        fetchServiceSnapshot()

        root.addView(header())

        statusHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusHolder.addView(statusCard())
        root.addView(statusHolder)

        root.addView(sectionLabel("Spoofing"))
        controlsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlsHolder.addView(controlsCard())
        root.addView(controlsHolder)

        root.addView(sectionLabel("Diagnostics"))
        diagHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagHolder.addView(serviceCard())
        diagHolder.addView(readingsCard())
        root.addView(diagHolder)

        root.addView(sectionLabel("Setup"))
        root.addView(outlinedCard().apply {
            addView(toggleRow("🐞", "Verbose logging",
                "One LSPosed log line per injection — package, process, uid, guards. " +
                "For troubleshooting a hook that won't install. Takes effect on reboot.",
                Config.KEY_VERBOSE_LOG, default = false))
        })
        root.addView(scopeHintCard())
        root.addView(footer())

        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(20), dp(20) + bars.top, dp(20), dp(28) + bars.bottom)
            insets
        }
        setContentView(scroll)

        // A theme switch goes through AppCompatDelegate, which recreates the activity — restore
        // where the user was instead of dumping them at the top.
        savedInstanceState?.getInt(STATE_SCROLL_Y, 0)?.takeIf { it > 0 }?.let { y ->
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scroll.isInitialized) outState.putInt(STATE_SCROLL_Y, scroll.scrollY)
    }

    /** Diagnostics are a point-in-time snapshot, so re-read them whenever the user comes back. */
    override fun onResume() {
        super.onResume()
        if (::diagHolder.isInitialized) refreshDiagnostics()
    }

    // ---------------------------------------------------------------- header

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, 0, dp(12))
        addView(TextView(this@MainActivity).apply {
            text = "🦆"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setPadding(0, 0, dp(12), 0)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "DuckUSB"
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Hide USB debugging from the apps you choose"
                setTextColor(cOnSurfaceVar)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        })
        addView(themeButton())
    }

    /** Sun/moon switch. Shows the theme you would switch TO, which is the usual convention. */
    private fun themeButton(): View = TextView(this).apply {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        text = if (dark) "☀️" else "🌙"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(dp(12), dp(8), dp(4), dp(8))
        setOnClickListener {
            prefs.edit().putString(PREF_THEME, if (dark) "light" else "dark").apply()
            // Triggers an activity recreate on its own.
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )
        }
    }

    private fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString(PREF_THEME, null)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // --------------------------------------------------------------- status

    /**
     * One card for "what is running" plus the master pause. Previously these were two cards
     * saying nearly the same thing, and the hero one reported only *this* process — which is
     * the least interesting answer, since the framework half lives in system_server.
     *
     * The pause stops every hook body and pushes live over the binder, but cannot uninstall
     * hooks or unload the native library (both fixed at process load). LSPosed's own switch
     * is the real off, and the subtitle says so rather than implying otherwise.
     */
    private fun statusCard(): View {
        val paused = prefs.getBoolean(Config.KEY_PAUSED, false)
        val frameworkLive = svcState != null
        val loadedHere = isModuleActive()

        val bg = when {
            paused -> cErrorCont
            frameworkLive || loadedHere -> cPrimaryCont
            else -> cErrorCont
        }
        val fg = when {
            paused -> cOnErrorCont
            frameworkLive || loadedHere -> cOnPrimaryCont
            else -> cOnErrorCont
        }
        val title = when {
            paused -> "Paused"
            frameworkLive -> "Active — framework mode"
            loadedHere -> "Active — per-app only"
            else -> "Not active"
        }
        val detail = when {
            paused -> "All spoofing stopped. Hooks stay loaded until reboot; LSPosed's switch is the real off."
            frameworkLive -> "Hook live in system_server, covering every app"
            loadedHere -> "Loaded in this process. Framework mode not running — scope \"system\" and reboot for full coverage."
            else -> "Enable DuckUSB in LSPosed, then scope your apps"
        }

        val card = filledCard(bg, dp(22))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (paused) "⏸️" else if (frameworkLive || loadedHere) "✅" else "⛔"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, 0, dp(14), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                setTextColor(fg); alpha = 0.9f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(MaterialSwitch(this).apply {
            isChecked = paused
            setPadding(dp(10), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Config.KEY_PAUSED, checked).apply()
                pushConfigToService()
                refreshCards()
            }
        })
        card.addView(row)
        return card
    }

    // ------------------------------------------------------- framework service

    /**
     * Reports the system_server half directly: retrieving the binder at all proves the
     * framework hook is live over there, which the in-process readings can't tell you.
     */
    private fun serviceCard(): View {
        val card = outlinedCard()
        // Snapshot, not a live feed — tap to re-read it without leaving the screen.
        card.setOnClickListener { refreshDiagnostics() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = svcState

        if (st == null) {
            col.addView(TextView(this).apply {
                text = "Not reachable.\nFramework mode off, “System Framework (system)” not scoped, " +
                       "or no reboot since enabling it."
                setTextColor(cOnSurfaceVar)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
            card.addView(col)
            return card
        }

        val upMs = st.getLong("nowRealtimeMs") - st.getLong("installedAtRealtimeMs")
        col.addView(readingRow("service version", st.getInt("version").toString(), true))
        col.addView(readingRow("SettingsProvider.call hooks", st.getInt("hookCount").toString(),
            st.getInt("hookCount") > 0))
        col.addView(readingRow("installed", "${upMs / 1000}s ago", true))
        col.addView(readingRow("apps spoofed", st.getInt("recordCount").toString(),
            st.getInt("recordCount") > 0))

        // The caller list can run to a dozen rows, so it collapses. Collapsed is the default:
        // the summary above already answers "is the framework half alive"; the list is detail
        // you go looking for. State persists so it stays how you left it.
        if (svcRecords.isNotEmpty()) {
            col.addView(thinDivider())

            val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            svcRecords.sortedByDescending { it.getInt("count") }.take(12).forEach { r ->
                listCol.addView(recordRow(
                    r.getString("label") ?: r.getString("packageName") ?: "?",
                    "${r.getInt("count")}× ${shortKeys(r.getString("keys").orEmpty())}"
                ))
            }

            var expanded = prefs.getBoolean(PREF_RECORDS_EXPANDED, false)
            val head = TextView(this).apply {
                setTextColor(cOnSurfaceVar)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(6), 0, dp(6))
            }
            fun render() {
                head.text = (if (expanded) "▾" else "▸") +
                    "  Callers lied to since boot (${svcRecords.size})"
                listCol.visibility = if (expanded) View.VISIBLE else View.GONE
            }
            head.setOnClickListener {
                expanded = !expanded
                prefs.edit().putBoolean(PREF_RECORDS_EXPANDED, expanded).apply()
                render()
            }
            render()

            col.addView(head)
            col.addView(listCol)
        }
        card.addView(col)
        return card
    }

    // -------------------------------------------------------------- readings

    private fun readingsCard(): View {
        val card = outlinedCard()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cr = contentResolver
        fun g(k: String) = try { Settings.Global.getInt(cr, k, 0) } catch (t: Throwable) { -1 }
        col.addView(readingRow("adb_enabled", g("adb_enabled").toString(), g("adb_enabled") == 0))
        col.addView(readingRow("development_settings_enabled", g("development_settings_enabled").toString(), g("development_settings_enabled") == 0))
        col.addView(readingRow("adb_wifi_enabled", g("adb_wifi_enabled").toString(), g("adb_wifi_enabled") == 0))

        col.addView(thinDivider())

        val get = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        } catch (t: Throwable) { null }
        fun p(k: String) = try { (get?.invoke(null, k) as? String).orEmpty().ifEmpty { "—" } } catch (t: Throwable) { "?" }
        col.addView(readingRow("sys.usb.state", p("sys.usb.state"), p("sys.usb.state") == "mtp"))
        col.addView(readingRow("sys.usb.config", p("sys.usb.config"), p("sys.usb.config") == "mtp"))
        col.addView(readingRow("init.svc.adbd", p("init.svc.adbd"), p("init.svc.adbd") == "stopped"))

        col.addView(TextView(this).apply {
            text = "Scope DuckUSB onto an app (force-stop + reopen) to see these read spoofed there."
            setTextColor(cOnSurfaceVar)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(col)
        return card
    }

    /** The three spoofed keys are long; abbreviate so the chip can't crowd out the app name. */
    private fun shortKeys(keys: String): String = keys
        .replace("development_settings_enabled", "dev")
        .replace("adb_wifi_enabled", "adb_wifi")
        .replace("adb_enabled", "adb")

    /**
     * Like readingRow, but the value can be long. readingRow gives the label weight=1 against a
     * wrap_content chip, so a long chip starves the label to ~0dp and a long app name then wraps
     * to one character per line — nameless, absurdly tall rows. Here the name is capped to one
     * ellipsized line and the chip is bounded, so neither can crush the other.
     */
    private fun recordRow(name: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = name
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chip(value, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(190)
            })
        }

    private fun readingRow(key: String, value: String, spoofed: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = key
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chip(value, spoofed))
        }

    // -------------------------------------------------------------- controls

    private fun controlsCard(): View {
        val card = outlinedCard()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // The two settings layers are mutually exclusive. Framework mode already covers every
        // app at the server chokepoint, so per-app adds nothing on top of it — and worse, it
        // short-circuits reads inside the app so the framework hook never sees them, blanking
        // those callers from the records list. Whichever is on greys the other out.
        val fw = prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false)
        val perApp = prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, true)

        col.addView(toggleRow("🔌", "Spoof USB debugging",
            "adb_enabled · adb_wifi_enabled · Developer Options → off", Config.KEY_SPOOF))
        col.addView(thinDivider())
        col.addView(toggleRow("🧪", "Framework mode",
            if (perApp) "Disabled while per-app is on — they cover the same thing"
            else "One hook in System Framework covers every app, no per-app scope. Needs the \"system\" scope + reboot.",
            Config.KEY_FRAMEWORK_MODE, default = false, enabled = !perApp, rebuild = true))
        col.addView(thinDivider())
        col.addView(toggleRow("🎯", "Per-app Settings spoof",
            if (fw) "Disabled while framework mode is on — it would also hide those apps from the records above"
            else "Hooks Settings getters inside each scoped app (restart the app)",
            Config.KEY_CLIENT_FALLBACK, default = true, enabled = !fw, rebuild = true))
        col.addView(thinDivider())
        col.addView(toggleRow("🔕", "Hide \"USB debugging\" notification",
            "Needs System Framework + System UI in scope", Config.KEY_HIDE_NOTIF))
        card.addView(col)
        return card
    }

    private fun toggleRow(
        icon: String, title: String, subtitle: String, key: String,
        default: Boolean = true, enabled: Boolean = true, rebuild: Boolean = false,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(TextView(this).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dp(14), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(cOnSurfaceVar)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, 0)
            })
        })
        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            isEnabled = enabled
            setPadding(dp(10), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                pushConfigToService()
                // Re-render so the paired toggle greys/ungreys immediately. Rebuilds the two
                // cards in place rather than recreate()-ing the activity, which would throw the
                // user back to the top of the page mid-interaction.
                if (rebuild) refreshCards()
            }
        }
        row.addView(sw)
        if (enabled) row.setOnClickListener { sw.isChecked = !sw.isChecked }
        row.alpha = if (enabled) 1f else 0.45f
        return row
    }

    // -------------------------------------------------------------- scope hint

    private fun scopeHintCard(): View {
        val card = filledCard(attr(MR.attr.colorSurfaceContainerHigh, cSurfaceCard), dp(16))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = "💡"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, 0, dp(10), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "In LSPosed → DuckUSB → Scope, tick your detector apps (banking, Intune, games) " +
                    "for the spoof, and System Framework + System UI if you want the notification hidden. " +
                    "Force-stop a target after changing scope. (Framework mode is experimental — it can " +
                    "bootloop system_server on some ROMs, so leave it off unless you know your ROM is safe.)"
                setTextColor(cOnSurfaceVar)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
        })
        return card
    }

    private fun footer(): View = TextView(this).apply {
        text = "v${appVersion()} · author XxxY"
        setTextColor(cOnSurfaceVar); alpha = 0.7f; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(18), 0, 0)
    }

    /** LSPosed replaces the body of this method at runtime when the module is active. */
    private fun isModuleActive(): Boolean = false

    /** Rebuild status + controls in place; leaves scroll position untouched. */
    private fun refreshCards() {
        statusHolder.removeAllViews()
        statusHolder.addView(statusCard())
        controlsHolder.removeAllViews()
        controlsHolder.addView(controlsCard())
    }

    /** Re-read the service snapshot and redraw the diagnostics section. */
    private fun refreshDiagnostics() {
        fetchServiceSnapshot()
        diagHolder.removeAllViews()
        diagHolder.addView(serviceCard())
        diagHolder.addView(readingsCard())
        // Status depends on whether the service answered, so keep it in step.
        statusHolder.removeAllViews()
        statusHolder.addView(statusCard())
    }

    private fun fetchServiceSnapshot() {
        val svc = DuckServiceClient.get(this)
        svcState = svc?.let { runCatching { it.getState() }.getOrNull() }
        svcRecords = svc?.let { runCatching { it.getRecords() }.getOrNull() }.orEmpty()
    }

    /** Live-push the toggles to system_server; no-op when the framework half isn't running. */

    private fun pushConfigToService() = DuckServiceClient.pushConfig(
        this,
        prefs.getBoolean(Config.KEY_PAUSED, false),
        prefs.getBoolean(Config.KEY_SPOOF, true),
        prefs.getBoolean(Config.KEY_HIDE_NOTIF, true),
    )

    // --------------------------------------------------------------- helpers

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(cPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        letterSpacing = 0.06f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    private fun filledCard(bg: Int, pad: Int) = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(bg)
        setContentPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun outlinedCard() = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(cSurfaceCard)
        strokeColor = cOutline
        strokeWidth = dp(1)
        setContentPadding(dp(18), dp(14), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun chip(text: String, ok: Boolean): TextView = TextView(this).apply {
        this.text = if (ok) "$text  ✓" else text
        val bg = if (ok) cPrimaryCont else attr(MR.attr.colorSurfaceContainerHighest, cSurfaceCard)
        val fg = if (ok) cOnPrimaryCont else cOnSurfaceVar
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat(); setColor(bg)
        }
    }

    private fun thinDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(4); bottomMargin = dp(4) }
        setBackgroundColor(cOutline)
    }

    private fun attr(attrId: Int, fallback: Int = Color.GRAY): Int =
        MaterialColors.getColor(this, attrId, fallback)

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) { "?" }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
