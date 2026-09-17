package com.strawing.duckusb.zygisk

import android.content.Intent
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.google.android.material.R as MR

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var config = DuckConfig()
    private var rootAvailable = false
    private var moduleInstalled = false
    private var hooksKilled = false
    private var live = false

    private val cOnSurface get() = attr(MR.attr.colorOnSurface)
    private val cOnSurfaceVar get() = attr(MR.attr.colorOnSurfaceVariant)
    private val cSurfaceCard get() = attr(MR.attr.colorSurfaceContainer, attr(MR.attr.colorSurface))
    private val cOutline get() = attr(MR.attr.colorOutlineVariant)
    private val cPrimary get() = attr(MR.attr.colorPrimary)
    private val cPrimaryCont get() = attr(MR.attr.colorPrimaryContainer)
    private val cOnPrimaryCont get() = attr(MR.attr.colorOnPrimaryContainer)
    private val cErrorCont get() = attr(MR.attr.colorErrorContainer)
    private val cOnErrorCont get() = attr(MR.attr.colorOnErrorContainer)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        rootAvailable = Root.available()
        moduleInstalled = rootAvailable && Root.moduleInstalled()
        hooksKilled = moduleInstalled && Root.hooksKilled()
        config = (if (moduleInstalled) Root.readConfig() else null) ?: config
        live = runCatching { System.getProperty(Config.LIVE_PROPERTY) != null }.getOrDefault(false)
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(header())
        root.addView(statusCard())
        root.addView(sectionLabel("What this app sees vs the device"))
        root.addView(readingsCard())
        root.addView(sectionLabel("Behaviour"))
        root.addView(controlsCard())
        root.addView(sectionLabel("Coverage"))
        root.addView(scopeCard())
        root.addView(footer())
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(16), dp(4), dp(12))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnSurface)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Zygisk module ${appVersion()} · no Xposed framework"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cOnSurfaceVar)
        })
    }

    private fun statusCard(): View {
        val spoofing = live && !config.paused && config.spoofSettings &&
            config.frameworkMode && config.hookSystemServer
        val healthy = rootAvailable && moduleInstalled && !hooksKilled && spoofing

        val title = when {
            !rootAvailable -> "No root access"
            !moduleInstalled -> "Module not installed"
            hooksKilled -> "Hooks disabled"
            config.paused -> "Paused"
            !live -> "Waiting for a reboot"
            !config.hookSystemServer || !config.frameworkMode -> "Framework mode is off"
            !config.spoofSettings -> "Settings spoof is off"
            config.frameworkAllApps -> "Active — every app"
            config.targets.isEmpty() -> "Active — but no app is covered"
            else -> "Active — ${config.targets.size} app(s)"
        }
        val detail = when {
            !rootAvailable -> "Grant root in your root manager so the app can read and write the module configuration."
            !moduleInstalled -> "Flash the module zip, then reboot."
            hooksKilled -> "The kill switch or the boot watchdog disabled every hook. Turn it back on below, then reboot."
            config.paused -> "Everything reads true again. The hooks stay loaded until reboot."
            !live -> "The module is installed but has not been injected here yet. Reboot."
            !config.hookSystemServer || !config.frameworkMode -> "Nothing is spoofing. Turn framework mode on below, then reboot."
            !config.spoofSettings -> "Framework mode is running but the settings spoof is switched off."
            config.frameworkAllApps -> "Told in system_server for every app. Nothing is injected into them, so their memory holds nothing to find."
            config.targets.isEmpty() -> "Framework mode is running, but no app is selected. Pick some under Coverage."
            else -> "Told in system_server for the apps you picked. Nothing is injected into them."
        }

        val bg = if (healthy) cPrimaryCont else cErrorCont
        val fg = if (healthy) cOnPrimaryCont else cOnErrorCont
        val card = filledCard(bg, dp(22))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (config.paused) "⏸️" else if (healthy) "✅" else "⛔"
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
                setTextColor(fg)
                alpha = 0.9f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), 0, 0, 0)
            addView(MaterialSwitch(this@MainActivity).apply {
                isChecked = config.paused
                isEnabled = moduleInstalled
                contentDescription = "Pause all spoofing"
                setOnCheckedChangeListener { _, checked ->
                    config.paused = checked
                    save()
                    reload()
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

    private fun readingsCard(): View {
        val card = outlinedCard()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        for (key in Config.SPOOF_KEYS) {
            col.addView(readingRow(key, globalSetting(key), if (rootAvailable) Root.globalSetting(key) else null))
        }
        col.addView(thinDivider())
        for (key in listOf("sys.usb.state", "sys.usb.config", "init.svc.adbd")) {
            col.addView(readingRow(key, systemProperty(key), if (rootAvailable) Root.property(key) else null))
        }
        col.addView(TextView(this).apply {
            text = "Left chip is what this app reads, right chip is what root reads. DuckUSB never spoofs itself, so a mismatch here means something else on this device is spoofing this app."
            setTextColor(cOnSurfaceVar)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(col)
        return card
    }

    private fun controlsCard(): View {
        val card = outlinedCard()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        col.addView(
            toggleRow("🧩", "Framework mode", "The lie is told in system_server. Nothing is injected into the apps being fooled. Needs a reboot.", config.frameworkMode) {
                config.frameworkMode = it
                config.hookSystemServer = it || config.hideNotif
                save()
                reload()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("🌍", "Cover every app", "No scope list at all. Shell, system uids and the file-transfer apps still read the truth.", config.frameworkAllApps) {
                config.frameworkAllApps = it
                save()
                reload()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("🔌", "Spoof USB debugging", "adb_enabled · adb_wifi_enabled · Developer Options → 0", config.spoofSettings) {
                config.spoofSettings = it
                save()
                reload()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("🔎", "Cover the query path", "Also rewrites cursor reads of the settings tables, so a direct query agrees with the getter.", config.coverQueryPath) {
                config.coverQueryPath = it
                save()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("🔕", "Hide the notification", "Swallows the persistent USB debugging notification. Needs a reboot.", config.hideNotif) {
                config.hideNotif = it
                config.hookSystemServer = it || config.frameworkMode
                save()
                reload()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("📝", "Verbose log", "One logcat line per spoofed read. Off unless you are investigating.", config.verboseLog) {
                config.verboseLog = it
                save()
            }
        )
        col.addView(thinDivider())
        col.addView(
            toggleRow("🛑", "Kill switch", "Disables every hook on the next boot without uninstalling.", hooksKilled) {
                hooksKilled = it
                Root.setHooksKilled(it)
                reload()
            }
        )
        card.addView(col)
        return card
    }

    private fun scopeCard(): View {
        val card = outlinedCard()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = when {
                config.frameworkAllApps -> "Every app on the device reads USB debugging as off. No list to maintain."
                config.targets.isEmpty() -> "No app is covered yet. Pick the detectors you want to fool, or turn on \"Cover every app\"."
                else -> "${config.targets.size} app(s) covered. The rest of the device reads the truth."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cOnSurfaceVar)
        })
        if (!config.frameworkAllApps) {
            col.addView(TextView(this).apply {
                text = "Choose apps"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cPrimary)
                isClickable = true
                setPadding(0, dp(12), 0, 0)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ScopeActivity::class.java))
                }
            })
        }
        card.addView(col)
        return card
    }

    private fun footer(): View = TextView(this).apply {
        text = "Module id ${Config.MODULE_ID} · framework and notification changes apply on reboot"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(cOnSurfaceVar)
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, 0)
    }

    private fun save() {
        if (moduleInstalled) Root.writeConfig(config)
    }

    private fun toggleRow(
        icon: String,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(TextView(this).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, dp(12), 0)
        })
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(cOnSurface)
        })
        text.addView(TextView(this).apply {
            this.text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cOnSurfaceVar)
        })
        row.addView(text)
        row.addView(MaterialSwitch(this).apply {
            isChecked = checked
            isEnabled = rootAvailable && moduleInstalled
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        return row
    }

    private fun readingRow(key: String, seen: String, truth: String?): View =
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
            val differs = truth != null && truth != seen
            addView(chip(seen, differs))
            if (truth != null) {
                addView(TextView(this@MainActivity).apply {
                    text = if (differs) " ≠ " else " = "
                    setTextColor(cOnSurfaceVar)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
                addView(chip(truth, false))
            }
        }

    private fun chip(text: String, highlight: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(if (highlight) cOnErrorCont else cOnSurfaceVar)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(if (highlight) cErrorCont else attr(MR.attr.colorSurfaceContainerHighest, cSurfaceCard))
        }
    }

    private fun globalSetting(key: String): String = try {
        Settings.Global.getString(contentResolver, key) ?: "—"
    } catch (_: Throwable) {
        "?"
    }

    private fun systemProperty(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty().ifEmpty { "—" }
    } catch (_: Throwable) {
        "?"
    }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(cPrimary)
        letterSpacing = 0.08f
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }

    private fun filledCard(background: Int, padding: Int) = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        radius = dp(20).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(background)
        setContentPadding(padding, padding, padding, padding)
    }

    private fun outlinedCard() = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = cOutline
        setCardBackgroundColor(cSurfaceCard)
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
    } catch (_: Throwable) {
        "?"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
