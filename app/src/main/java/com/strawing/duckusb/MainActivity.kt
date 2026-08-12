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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MR

/**
 * Config UI. Writes the three live toggles to a world-readable SharedPreferences file that
 * the Xposed hook re-reads on every call, so flipping a switch applies without a reboot
 * (at most force-stop the target app). All default ON.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(header())
        root.addView(heroStatusCard())
        root.addView(sectionLabel("Live readings — as this app sees them"))
        root.addView(readingsCard())
        root.addView(sectionLabel("Controls"))
        root.addView(controlsCard())
        root.addView(scopeHintCard())
        root.addView(footer())

        val scroll = ScrollView(this).apply {
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
    }

    // ------------------------------------------------------------ hero status

    private fun heroStatusCard(): View {
        val active = isModuleActive()
        val bg = if (active) cPrimaryCont else cErrorCont
        val fg = if (active) cOnPrimaryCont else cOnErrorCont
        val card = filledCard(bg, dp(24))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (active) "✅" else "⛔"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, dp(14), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = if (active) "Module active" else "Not active"
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (active) "Hooks are live in this process"
                       else "Enable DuckUSB in LSPosed, then scope your apps"
                setTextColor(fg); alpha = 0.9f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        })
        card.addView(row)
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
        col.addView(toggleRow("🎯", "Per-app Settings spoof",
            "Hooks Settings getters in each scoped app — the default, safe path (restart app)",
            Config.KEY_CLIENT_FALLBACK, default = true))
        col.addView(thinDivider())
        col.addView(toggleRow("🧪", "Framework mode (experimental)",
            "One System-Framework hook for every app. ⚠️ Can BOOTLOOP system_server on some ROMs — scope System Framework manually, then reboot at your own risk.",
            Config.KEY_FRAMEWORK_MODE, default = false))
        col.addView(thinDivider())
        col.addView(toggleRow("🔕", "Hide \"USB debugging\" notification",
            "Needs System Framework + System UI in scope", Config.KEY_HIDE_NOTIF))
        col.addView(thinDivider())
        col.addView(toggleRow("⚙️", "Spoof USB system properties",
            "sys.usb.* · init.svc.adbd — per-app, Java + native (restart target for native)", Config.KEY_SPOOF_PROPS))
        card.addView(col)
        return card
    }

    private fun toggleRow(icon: String, title: String, subtitle: String, key: String, default: Boolean = true): View {
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
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
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
