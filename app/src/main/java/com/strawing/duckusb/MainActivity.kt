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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MR
import com.strawing.duckusb.service.DuckServiceClient
import io.github.libxposed.service.XposedService

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

        prefs = resolvePrefs()

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
                "One log line per injection. For troubleshooting a hook that won't install.",
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

    // ------------------------------------------------------- framework service (libxposed)

    /**
     * The framework binder arrives asynchronously and may land after this activity is built,
     * which would leave the UI reading the local fallback prefs and reporting "Not active"
     * forever. Rebuild once when it shows up (or goes away).
     */
    private val serviceListener: (XposedService?) -> Unit = { svc ->
        runOnUiThread {
            if ((svc != null) != usingRemotePrefs && !isFinishing) recreate()
        }
    }

    /** True when [prefs] is the framework's remote store rather than the local fallback. */
    private var usingRemotePrefs = false

    override fun onStart() {
        super.onStart()
        DuckApp.addListener(serviceListener)
    }

    override fun onStop() {
        DuckApp.removeListener(serviceListener)
        super.onStop()
    }

    /**
     * Module settings live in the framework's remote preferences, which is how the hook reads
     * them from inside system_server and every scoped app. Falling back to a local file keeps
     * the screen usable when the framework is absent — nothing is hooked in that state anyway,
     * so the values would have no effect either way.
     */
    private fun resolvePrefs(): SharedPreferences {
        DuckApp.service?.let { svc ->
            runCatching { svc.getRemotePreferences(Config.PREFS_NAME) }.getOrNull()?.let { remote ->
                usingRemotePrefs = true
                importLegacyPrefs(remote)
                return remote
            }
        }
        usingRemotePrefs = false
        return getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Carry 1.3.x settings across once. Those lived in a MODE_WORLD_READABLE SharedPreferences
     * file that the old framework redirected out of the app's data dir; a modern module does
     * not get that redirect, so the read may legitimately find nothing and the user simply
     * starts from defaults. Best effort by design — never let a failed import block the UI.
     */
    private fun importLegacyPrefs(remote: SharedPreferences) {
        if (remote.getBoolean(Config.KEY_PREFS_IMPORTED, false)) return
        @Suppress("DEPRECATION")
        val legacy = runCatching {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            runCatching { getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
        }
        val edit = remote.edit() ?: return
        legacy?.let {
            for (key in Config.BOOLEAN_KEYS) {
                if (it.contains(key)) edit.putBoolean(key, it.getBoolean(key, false))
            }
        }
        edit.putBoolean(Config.KEY_PREFS_IMPORTED, true).apply()
    }

    /**
     * DuckUSB's own LSPosed scope, straight from the framework.
     *
     * The legacy API could not see this at all — a comment in Config.kt used to say as much —
     * which is why the UI had to guess, and why issue #4 could be told its scope was wrong
     * when it was fine.
     */
    private val moduleScope: List<String>? by lazy {
        runCatching { DuckApp.service?.scope }.getOrNull()
    }

    /** Null when the scope is unreadable (no framework), else whether system_server is in it. */
    private fun systemScoped(): Boolean? =
        moduleScope?.any { it == "system" || it == "android" }

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

        // When framework mode is off, "framework mode not running" is not a fault to report —
        // it is the configuration. Saying so anyway sent issue #4 chasing a scope that was
        // already correct, so the per-app case now states what per-app actually requires.
        //
        // The one state that IS a fault: framework mode selected, its hook not in system_server.
        // Selecting it also turned per-app off, so nothing is installed for this boot — calling
        // that "Active" in a green card would be the same flavour of lie. It says so instead.
        val frameworkWanted = prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false)
        val perAppWanted = prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, true)
        val pendingReboot = loadedHere && !frameworkLive && frameworkWanted
        // Turning a layer off does not turn the other on, so "neither" is reachable in two taps.
        // loadedHere only means the module got injected into this process — it says nothing about
        // whether a spoof layer is selected, so on its own it must not be reported as "Active".
        val noLayer = loadedHere && !frameworkLive && !frameworkWanted && !perAppWanted
        val healthy = !paused && !pendingReboot && !noLayer && (frameworkLive || loadedHere)

        val bg = if (healthy) cPrimaryCont else cErrorCont
        val fg = if (healthy) cOnPrimaryCont else cOnErrorCont
        val title = when {
            paused -> "Paused"
            frameworkLive -> "Active — framework mode"
            pendingReboot -> "Reboot needed"
            noLayer -> "Nothing is spoofing"
            loadedHere -> "Active — per-app only"
            else -> "Not active"
        }
        val detail = when {
            paused -> "All spoofing stopped. Hooks stay loaded until reboot; LSPosed's switch is the real off."
            frameworkLive -> "Hook live in system_server, covering every app"
            // The scope is now readable, so name the actual fault instead of listing candidates.
            pendingReboot && systemScoped() == false ->
                "Framework mode is on but \"System Framework (system)\" is NOT in DuckUSB's " +
                "scope, so the hook can never install. Tick it in LSPosed → Scope, then reboot."
            pendingReboot ->
                "Framework mode is on and System Framework is scoped, but the hook is not live " +
                "in system_server. Nothing is spoofing until you reboot."
            noLayer -> "Framework mode and per-app spoof are both off. Turn one on below."
            loadedHere -> "Per-app mode — only the apps you tick in LSPosed → Scope are spoofed."
            // The app cannot tell "not enabled yet" from "framework too old": both look like
            // no service binder. A modern module carries no xposedmodule meta-data, so an
            // LSPosed without libxposed 101 does not list it at all — say so here, or the user
            // hunts for a module entry that will never appear.
            else -> "Enable DuckUSB in LSPosed, then scope your apps. Not listed there? " +
                    "That LSPosed is too old — this build needs libxposed API 101."
        }

        val card = filledCard(bg, dp(22))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (paused) "⏸️" else if (healthy) "✅" else "⛔"
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
        // Captioned, because a bare switch on the hero card reads as "turn the module off" —
        // which it is not. It stops every hook body; the hooks themselves stay loaded until
        // reboot, and LSPosed's own switch is the real off. An unlabelled control that looks
        // like the master power button is the one thing on this screen a user could get
        // confidently wrong.
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), 0, 0, 0)
            addView(MaterialSwitch(this@MainActivity).apply {
                isChecked = paused
                contentDescription = "Pause all spoofing"
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(Config.KEY_PAUSED, checked).apply()
                    pushConfigToService()
                    refreshCards()
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Pause"
                setTextColor(fg)
                alpha = 0.75f
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, dp(2), 0, 0)
            })
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
            // Split on the one cause the app can actually check. The old copy listed all three
            // at once and led with "not scoped", which is the wrong first guess whenever the
            // toggle is simply off — the state every fresh install starts in (issue #4).
            col.addView(TextView(this).apply {
                text = when {
                    !prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false) ->
                        "Not running — framework mode is off.\nTurn on “Framework mode” above, " +
                        "tick “System Framework (system)” in LSPosed → Scope, then reboot."
                    systemScoped() == false ->
                        "Not running — “System Framework (system)” is not in DuckUSB's scope.\n" +
                        "Tick it in LSPosed → Scope and reboot."
                    else ->
                        "Framework mode is on and System Framework is scoped, but the hook is " +
                        "not live in system_server.\nReboot — the hook only installs at boot."
                }
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
        // Neutral chips, never ticks: DuckUSB never spoofs itself, so this card is the REAL
        // device state. A tick on "0" would read as pass/fail and imply a "1" here is a fault,
        // when the only honest reading of this card is "what your device actually says".
        col.addView(readingRow("adb_enabled", g("adb_enabled").toString(), false))
        col.addView(readingRow("development_settings_enabled", g("development_settings_enabled").toString(), false))
        col.addView(readingRow("adb_wifi_enabled", g("adb_wifi_enabled").toString(), false))

        col.addView(thinDivider())

        val get = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        } catch (t: Throwable) { null }
        fun p(k: String) = try { (get?.invoke(null, k) as? String).orEmpty().ifEmpty { "—" } } catch (t: Throwable) { "?" }
        col.addView(readingRow("sys.usb.state", p("sys.usb.state"), false))
        col.addView(readingRow("sys.usb.config", p("sys.usb.config"), false))
        col.addView(readingRow("init.svc.adbd", p("init.svc.adbd"), false))

        col.addView(TextView(this).apply {
            text = "Real device state — DuckUSB never spoofs itself. Check a scoped app to see these lied about."
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

        col.addView(toggleRow("🔌", "Spoof USB debugging",
            "adb_enabled · adb_wifi_enabled · Developer Options → off", Config.KEY_SPOOF))
        col.addView(thinDivider())
        col.addView(methodRow())
        col.addView(thinDivider())
        col.addView(toggleRow("🔕", "Hide \"USB debugging\" notification",
            "Needs System Framework + System UI in scope", Config.KEY_HIDE_NOTIF))
        card.addView(col)
        return card
    }

    /**
     * The two spoof layers are ONE choice, not two switches.
     *
     * They were always mutually exclusive, and expressing that as a pair of switches is what
     * made framework mode unreachable on a fresh install (issue #4): per-app ships on, so the
     * framework switch arrived greyed out behind a subtitle that never said what to turn off
     * first. A single selector cannot reach that state — picking one *is* turning the other off.
     */
    private fun methodRow(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(TextView(this).apply {
            text = "Method"
            setTextColor(cOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        val idFramework = View.generateViewId()
        val idPerApp = View.generateViewId()
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        fun pill(id: Int, label: String) = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.id = id
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        group.addView(pill(idFramework, "Framework"))
        group.addView(pill(idPerApp, "Per-app"))
        // Check before the listener is attached, so restoring state cannot fire a write.
        if (prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false)) group.check(idFramework)
        else if (prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, true)) group.check(idPerApp)

        col.addView(group)
        col.addView(TextView(this).apply {
            text = when {
                prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false) ->
                    "Covers every app from system_server, and installs nothing inside the " +
                    "target — no hook traces in its memory. Needs \"system\" scope + reboot."
                prefs.getBoolean(Config.KEY_CLIENT_FALLBACK, true) ->
                    "Hooks inside each scoped app — also spoofs sys.usb.*, but leaves hook " +
                    "traces in that app's memory. Restart the app to apply."
                else -> "No method selected — nothing is being spoofed."
            }
            setTextColor(cOnSurfaceVar)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, 0)
        })

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.edit()
                .putBoolean(Config.KEY_FRAMEWORK_MODE, checkedId == idFramework)
                .putBoolean(Config.KEY_CLIENT_FALLBACK, checkedId == idPerApp)
                .apply()
            pushConfigToService()
            refreshCards()
        }
        return col
    }

    private fun toggleRow(
        icon: String, title: String, subtitle: String, key: String,
        default: Boolean = true,
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
            setPadding(dp(10), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().apply {
                    putBoolean(key, checked)
                }.apply()
                pushConfigToService()
                // The status card reports what is running, so it has to re-read after any
                // toggle. Rebuilt in place rather than recreate()-ing the activity, which
                // would throw the user back to the top of the page mid-interaction.
                refreshCards()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
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
                // The scope is readable now, so report it instead of only advising about it.
                // "how many apps did I actually tick" was unanswerable in every 1.x build, and
                // an empty scope is the quietest way for this module to do nothing at all.
                val scope = moduleScope
                val targets = scope?.filter { it != "system" && it != "android" }
                val framework = prefs.getBoolean(Config.KEY_FRAMEWORK_MODE, false)
                text = when {
                    scope == null ->
                        "Scope in LSPosed: “System Framework (system)” for framework mode, your " +
                        "target apps for per-app mode, System UI to hide the notification."
                    framework && systemScoped() != true ->
                        "⚠️ Framework mode needs “System Framework (system)” ticked in LSPosed. " +
                        "It is not, so nothing is being spoofed."
                    framework ->
                        "Framework mode covers every app — no per-app ticks needed. " +
                        "Add System UI only if you want the notification hidden."
                    targets.isNullOrEmpty() ->
                        "⚠️ Per-app mode, but no target apps are ticked in LSPosed, so nothing " +
                        "is being spoofed."
                    else ->
                        "Per-app mode · ${targets.size} app(s) ticked. Force-stop a target after " +
                        "changing its scope."
                }
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

    /**
     * Whether the framework has this module loaded. Holding its service binder proves it —
     * the legacy build inferred this from a self-hook that only proved the module had been
     * injected into its own process, which happens whether or not anything is scoped.
     */
    private fun isModuleActive(): Boolean = DuckApp.service != null

    /** Rebuild status + controls in place; leaves scroll position untouched. */
    private fun refreshCards() {
        statusHolder.removeAllViews()
        statusHolder.addView(statusCard())
        controlsHolder.removeAllViews()
        controlsHolder.addView(controlsCard())
        // The service card's copy now depends on the framework-mode toggle, so it goes stale the
        // moment that switch flips. It used to be toggle-independent, which is why refreshing it
        // here was not needed before — verified stale on-device: flipping framework mode on left
        // the card still reading "framework mode is off".
        if (::diagHolder.isInitialized) refreshDiagnostics()
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
