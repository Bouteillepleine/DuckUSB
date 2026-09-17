package com.strawing.duckusb.zygisk

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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

    private val cPrimary get() = attr(MR.attr.colorPrimary)
    private val cOnSurface get() = attr(MR.attr.colorOnSurface)
    private val cOnSurfaceVar get() = attr(MR.attr.colorOnSurfaceVariant)
    private val cSurfaceCard get() = attr(MR.attr.colorSurfaceContainer, attr(MR.attr.colorSurface))
    private val cOutline get() = attr(MR.attr.colorOutlineVariant)
    private val cPrimaryCont get() = attr(MR.attr.colorPrimaryContainer)
    private val cErrorCont get() = attr(MR.attr.colorErrorContainer)

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
        root.addView(scopeCard())
        root.addView(controlsCard())
        root.addView(readingsCard())
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
        val card = filledCard(if (live) cPrimaryCont else cErrorCont, dp(16))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        box.addView(TextView(this).apply {
            text = when {
                live && !rootAvailable -> "Active, but no root access"
                live -> "Active in system_server"
                !rootAvailable -> "No root access"
                !moduleInstalled -> "Module not installed"
                hooksKilled -> "Hooks disabled (safe mode)"
                else -> "Installed, waiting for a reboot"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnSurface)
        })

        val detail = when {
            live && !rootAvailable -> "The hooks are running. Grant root in your root manager so this app can read and write the module configuration."
            !rootAvailable -> "Grant root to the app so it can read and write the module configuration."
            !moduleInstalled -> "Flash DuckUSB-Zygisk.zip in your root manager, then reboot."
            hooksKilled -> "The boot guard or the kill switch disabled the hooks. Turn them back on below."
            live -> "The module is injected into this app, so its hooks are running."
            else -> "The module is installed but was not injected here. Reboot, or check that Zygisk is enabled."
        }
        box.addView(TextView(this).apply {
            text = detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cOnSurfaceVar)
            setPadding(0, dp(6), 0, 0)
        })

        card.addView(box)
        return card
    }

    private fun scopeCard(): View {
        val card = outlinedCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(sectionLabel("Scope"))
        box.addView(TextView(this).apply {
            text = if (config.targets.isEmpty()) {
                "No app is being lied to yet. Pick the detectors you want to fool."
            } else {
                "${config.targets.size} app(s) selected. Only these see USB debugging as off."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cOnSurfaceVar)
            setPadding(0, dp(4), 0, dp(12))
        })
        box.addView(TextView(this).apply {
            text = "Choose apps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cPrimary)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ScopeActivity::class.java))
            }
        })
        card.addView(box)
        return card
    }

    private fun controlsCard(): View {
        val card = outlinedCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        box.addView(sectionLabel("Behaviour"))
        box.addView(
            toggleRow("Pause everything", "Keeps the module loaded but stops every lie.", config.paused) {
                config.paused = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Spoof settings", "adb_enabled, adb_wifi_enabled and development_settings_enabled read 0.", config.spoofSettings) {
                config.spoofSettings = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Cover the query path", "Also rewrites bulk cursor reads of the settings tables, not just the getters.", config.coverQueryPath) {
                config.coverQueryPath = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Spoof properties", "sys.usb.* and init.svc.adbd, inside the scoped apps only. Needs an app restart.", config.spoofProps) {
                config.spoofProps = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Hide the notification", "Swallows the persistent USB debugging notification.", config.hideNotif) {
                config.hideNotif = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Verbose log", "One logcat line per spoofed read. Off unless you are investigating.", config.verboseLog) {
                config.verboseLog = it
                save()
            }
        )
        box.addView(thinDivider())
        box.addView(
            toggleRow("Kill switch", "Disables every hook on the next boot without uninstalling.", hooksKilled) {
                hooksKilled = it
                Root.setHooksKilled(it)
            }
        )
        card.addView(box)
        return card
    }

    private fun readingsCard(): View {
        val card = outlinedCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(sectionLabel("What this app reads"))
        box.addView(TextView(this).apply {
            text = "DuckUSB is never spoofed to itself, so these are the real values."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cOnSurfaceVar)
            setPadding(0, dp(2), 0, dp(8))
        })
        for (key in Config.SPOOF_KEYS) {
            box.addView(readingRow(key, globalSetting(key)))
        }
        for ((key, _) in Config.PROP_OVERRIDES) {
            box.addView(readingRow(key, systemProperty(key)))
        }
        card.addView(box)
        return card
    }

    private fun footer(): View = TextView(this).apply {
        text = "Module id ${Config.MODULE_ID} · scope changes apply when an app restarts"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(cOnSurfaceVar)
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, 0)
    }

    private fun save() {
        if (moduleInstalled) Root.writeConfig(config)
    }

    private fun toggleRow(
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

    private fun readingRow(key: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = key
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(cOnSurfaceVar)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.MONOSPACE)
                setTextColor(cOnSurface)
            })
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
        setBackgroundColor(cOutline)
    }

    private fun attr(attrId: Int, fallback: Int = Color.GRAY): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attrId, value, true)) value.data else fallback
    }

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
