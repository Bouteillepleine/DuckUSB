package com.strawing.duckadb

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Config UI. Writes the two live toggles to a world-readable SharedPreferences file that
 * the Xposed hook re-reads on every call (so flipping a switch applies without a reboot —
 * at most force-stop the target app so a fresh read happens). Both default ON.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

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
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(title("DuckADB"))
        root.addView(statusCard())
        root.addView(selfReadCard())
        root.addView(body(
            "Makes scoped apps read USB debugging as OFF while it stays really on, and " +
            "hides the persistent \"USB debugging enabled\" notification."
        ))

        root.addView(toggleCard(
            "Spoof USB debugging",
            "adb_enabled · adb_wifi_enabled · Developer Options → off for your scoped detector apps",
            Config.KEY_SPOOF
        ))
        root.addView(toggleCard(
            "Hide \"USB debugging\" notification",
            "Swallows the persistent shade notification (needs System Framework + System UI in scope)",
            Config.KEY_HIDE_NOTIF
        ))

        root.addView(warn(
            "⚠  In LSPosed → DuckADB → Scope: tick your detector apps (banking, Intune, games) " +
            "for the spoof, and tick System Framework + System UI to hide the notification. " +
            "Force-stop a target app / reboot after changing scope."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun statusCard(): MaterialCardView {
        val active = isModuleActive()
        val card = card(dp(16))
        card.addView(TextView(this).apply {
            text = if (active) "✅ Module active" else "⭕ Not active — enable DuckADB in LSPosed"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        return card
    }

    /**
     * Self-test: reads the settings THIS process sees. If you scope DuckADB onto itself,
     * the module spoofs these to 0 even though the system really has them on — proving the
     * hook works, live. Open this screen, then force-stop & reopen after scoping.
     */
    private fun selfReadCard(): MaterialCardView {
        val cr = contentResolver
        fun g(k: String) = try { Settings.Global.getInt(cr, k, 0) } catch (t: Throwable) { -1 }
        val adb = g("adb_enabled")
        val dev = g("development_settings_enabled")
        val wifi = g("adb_wifi_enabled")
        fun mark(v: Int) = if (v == 0) "0 (looks off ✅)" else "$v"
        val card = card(dp(14)).apply { cardElevation = 0f; strokeWidth = dp(1) }
        card.addView(TextView(this).apply {
            text = "As read in THIS app:\n" +
                "· adb_enabled = ${mark(adb)}\n" +
                "· development_settings_enabled = ${mark(dev)}\n" +
                "· adb_wifi_enabled = ${mark(wifi)}\n\n" +
                "Scope DuckADB onto itself to see these flip to 0 while the system stays on."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        return card
    }

    private fun toggleCard(titleText: String, subtitle: String, key: String): MaterialCardView {
        val card = card(dp(14)).apply { cardElevation = 0f; strokeWidth = dp(1) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = titleText; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = subtitle; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.7f
        })
        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        row.addView(texts); row.addView(sw); card.addView(row)
        card.setOnClickListener { sw.isChecked = !sw.isChecked }
        return card
    }

    /** LSPosed replaces the body of this method at runtime when the module is active. */
    private fun isModuleActive(): Boolean = false

    // -- view helpers --
    private fun card(radiusPx: Int) = MaterialCardView(this).apply {
        radius = radiusPx.toFloat()
        setContentPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setPadding(0, 0, 0, dp(4))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.8f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun warn(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(0x22FF9800)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
