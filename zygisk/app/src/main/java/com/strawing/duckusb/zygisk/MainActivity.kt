package com.strawing.duckusb.zygisk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.strawing.duckusb.ui.DuckUi
import com.strawing.duckusb.ui.Health
import com.strawing.duckusb.ui.Theming

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_TAB = "tab"
        val PROP_KEYS =
            listOf("persist.sys.usb.config", "sys.usb.state", "sys.usb.config", "init.svc.adbd")
    }

    private lateinit var ui: DuckUi

    private var tab = DuckUi.TAB_STATUS
    private var config = DuckConfig()
    private var rootAvailable = false
    private var moduleInstalled = false
    private var hooksKilled = false
    private var live = false
    private var serviceState: Bundle? = null
    private var records: List<Bundle> = emptyList()
    private var recordsOpen = false
    private var zygiskFlavor = "unknown"
    private var moduleVersion: String? = null
    private var rootSettings: Map<String, String> = emptyMap()
    private var serviceSettings: Map<String, String> = emptyMap()
    private var rootProps: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theming.restore(this)
        savedInstanceState?.getInt(STATE_TAB, 0)?.takeIf { it != 0 }?.let { tab = it }
        ui = DuckUi(this)
        setContentView(ui.scaffold(onTabSelected = { tab = it; renderContent() }, startTab = tab))
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        live = runCatching { System.getProperty(Config.LIVE_PROPERTY) != null }.getOrDefault(false)
        render()
        background {
            val snapshot = Root.snapshot(Config.SPOOF_KEYS.toList(), PROP_KEYS)
            val state = ServiceClient.state(this)
            val callers = ServiceClient.records(this)
                .sortedByDescending { it.getInt(Bridge.REC_COUNT) }
            val truth = ServiceClient.trueSettings(this, Config.SPOOF_KEYS.toTypedArray())
            runOnUiThread {
                rootAvailable = snapshot.rootAvailable
                moduleInstalled = snapshot.moduleInstalled
                hooksKilled = snapshot.hooksKilled
                snapshot.config?.let { config = it }
                moduleVersion = snapshot.moduleVersion
                zygiskFlavor = snapshot.zygisk
                rootSettings = snapshot.settings
                rootProps = snapshot.props
                serviceState = state
                records = callers
                serviceSettings = truth
                render()
            }
        }
    }

    private fun background(work: () -> Unit) {
        Thread { runCatching(work) }.apply { isDaemon = true }.start()
    }

    private fun serviceLive(): Boolean = serviceState != null

    private fun save() {
        val snapshot = config.copy()
        val installed = moduleInstalled
        background {
            if (installed) {
                Root.writeConfig(snapshot)
                Root.refreshDescription()
            }
            ServiceClient.push(this, snapshot)
        }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        ui.setHeader(getString(R.string.app_name)) { recreate() }
        renderContent()
    }

    private fun renderContent() {
        ui.content.removeAllViews()
        when (tab) {
            DuckUi.TAB_BEHAVIOUR -> {
                ui.content.addView(ui.sectionLabel("Behaviour"))
                ui.content.addView(controlsCard())
                ui.content.addView(ui.sectionLabel("Coverage"))
                ui.content.addView(scopeCard())
            }
            DuckUi.TAB_DIAGNOSTICS -> {
                ui.content.addView(ui.sectionLabel("Diagnostics"))
                ui.content.addView(diagnosticsCard())
                ui.content.addView(ui.sectionLabel("What this app sees vs the device"))
                ui.content.addView(readingsCard())
            }
            else -> {
                ui.content.addView(statusCard())
                ui.content.addView(ui.sectionLabel("Module"))
                ui.content.addView(infoCard())
                ui.content.addView(
                    ui.footer(
                        "Module id ${Config.MODULE_ID} · framework and notification changes " +
                            "apply on reboot"
                    )
                )
            }
        }
    }

    private fun statusCard(): View {
        val spoofing = serviceLive() && !config.paused && config.spoofSettings
        val healthy = rootAvailable && moduleInstalled && !hooksKilled && spoofing
        val health = when {
            config.paused -> Health.PAUSED
            healthy -> Health.GOOD
            else -> Health.BAD
        }
        val title = when {
            !rootAvailable -> "No root access"
            !moduleInstalled -> "Module not installed"
            hooksKilled -> "Hooks disabled"
            config.paused -> "Paused"
            !config.hookSystemServer || !config.frameworkMode -> "Framework mode is off"
            !serviceLive() -> "Reboot needed"
            !config.spoofSettings -> "Settings spoof is off"
            else -> "Active — framework mode"
        }
        val detail = when {
            !rootAvailable -> "Grant root in your root manager so the app can read and write the module configuration."
            !moduleInstalled -> "Flash the module zip, then reboot."
            hooksKilled -> "The kill switch or the boot watchdog disabled every hook. Turn it back on below, then reboot."
            config.paused -> "Everything reads true again. The hooks stay loaded until reboot."
            !config.hookSystemServer || !config.frameworkMode -> "Nothing is spoofing. Turn framework mode on below, then reboot."
            !serviceLive() -> "Framework mode is on but its hook is not live in system_server. Nothing is spoofing until you reboot."
            !config.spoofSettings -> "The hook is live but the settings spoof is switched off."
            config.frameworkAllApps -> "Hook live in system_server, covering every app"
            config.targets.isEmpty() -> "Hook live, but no app is selected. Pick some under Coverage."
            else -> "Hook live in system_server, covering ${config.targets.size} app(s)"
        }
        return ui.heroCard(title, detail, health, "Pause", config.paused, moduleInstalled) { checked ->
            config.paused = checked
            save()
            renderContent()
        }
    }

    private fun infoCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(10, 10)
        col.addView(ui.infoRow("Module version", moduleVersion ?: "not installed"))
        col.addView(ui.infoRow("Manager version", appVersion()))
        col.addView(ui.infoRow("Hook engine", "LSPlant + Dobby"))
        col.addView(ui.infoRow("Zygisk", zygiskFlavor))
        col.addView(ui.infoRow("Injected here", if (live) "yes" else "no"))
        col.addView(ui.infoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        col.addView(ui.infoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}"))
        card.addView(col)
        return card
    }

    private fun diagnosticsCard(): View {
        val card = ui.outlinedCard()
        card.setOnClickListener { reload() }
        val col = ui.column()
        val state = serviceState
        if (state == null) {
            col.addView(
                ui.bodyText(
                    "The system_server service is not answering. Either framework mode is off, " +
                        "or the module has not been through a reboot yet."
                )
            )
            card.addView(col)
            return card
        }
        col.addView(ui.statRow("service version", state.getInt(Bridge.STATE_VERSION).toString()))
        col.addView(ui.statRow("provider hooks", state.getInt(Bridge.STATE_HOOKS).toString()))
        col.addView(
            ui.statRow("armed", "${state.getLong(Bridge.STATE_INSTALLED_AT) / 1000}s into this boot")
        )
        col.addView(ui.statRow("apps spoofed", records.size.toString()))
        col.addView(
            ui.statRow("notifications swallowed", state.getInt(Bridge.STATE_NOTIF_BLOCKED).toString())
        )
        col.addView(ui.thinDivider())
        col.addView(ui.expander("Callers lied to since boot (${records.size})", recordsOpen) {
            recordsOpen = !recordsOpen
            renderContent()
        })
        if (recordsOpen) {
            if (records.isEmpty()) {
                col.addView(ui.noteRow("Nothing yet. An app has to read one of the keys first."))
            }
            for (record in records) {
                val uid = record.getInt(Bridge.REC_UID)
                val count = record.getInt(Bridge.REC_COUNT)
                val keys = record.getStringArrayList(Bridge.REC_KEYS).orEmpty()
                col.addView(ui.callerRow(labelForUid(uid), "${count}× ${shortKeys(keys)}"))
            }
            if (records.isNotEmpty()) {
                col.addView(ui.linkRow("Clear") {
                    records = emptyList()
                    renderContent()
                    background {
                        ServiceClient.clearRecords(this@MainActivity)
                        runOnUiThread { reload() }
                    }
                })
            }
        }
        card.addView(col)
        return card
    }

    private fun readingsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column()
        for (key in Config.SPOOF_KEYS) {
            col.addView(ui.readingRow(key, globalSetting(key), serviceSettings[key] ?: rootSettings[key]))
        }
        col.addView(ui.thinDivider())
        for (key in PROP_KEYS) {
            col.addView(ui.readingRow(key, systemProperty(key), rootProps[key]))
        }
        col.addView(
            ui.noteRow(
                "Left chip is what this app reads, right chip is the real value read in " +
                    "system_server (or by root for the properties). DuckUSB never spoofs itself, " +
                    "so a mismatch here means something else on this device is spoofing this app."
            )
        )
        card.addView(col)
        return card
    }

    private fun controlsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(6, 6)
        col.addView(ui.toggleRow(
            DuckUi.Icons.framework, "Framework mode",
            "The lie is told in system_server. Nothing is injected into the apps being fooled. Needs a reboot.",
            config.frameworkMode,
        ) {
            config.frameworkMode = it
            config.hookSystemServer = it || config.hideNotif
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.allApps, "Cover every app",
            "No scope list at all. Shell, system uids and the file-transfer apps still read the truth.",
            config.frameworkAllApps,
        ) {
            config.frameworkAllApps = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.usb, "Spoof USB debugging",
            "adb_enabled · adb_wifi_enabled · Developer Options → 0",
            config.spoofSettings,
        ) {
            config.spoofSettings = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.query, "Cover the query path",
            "Also rewrites cursor reads of the settings tables, so a direct query agrees with the getter.",
            config.coverQueryPath,
        ) {
            config.coverQueryPath = it
            save()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.notification, "Hide the notification",
            "Swallows the persistent USB debugging notification. Needs a reboot.",
            config.hideNotif,
        ) {
            config.hideNotif = it
            config.hookSystemServer = it || config.frameworkMode
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.tag, "Mask the USB config property",
            "persist.sys.usb.config reads mtp and init.svc.adbd reads stopped, in the property area itself so every read route agrees. adbd keeps running. Reverts on reboot.",
            config.spoofProps,
        ) {
            config.spoofProps = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.log, "Verbose log",
            "One logcat line per spoofed read. Off unless you are investigating.",
            config.verboseLog,
        ) {
            config.verboseLog = it
            save()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.power, "Kill switch",
            "Disables every hook on the next boot without uninstalling.",
            hooksKilled,
        ) {
            hooksKilled = it
            renderContent()
            background {
                Root.setHooksKilled(it)
                Root.refreshDescription()
            }
        })
        card.addView(col)
        return card
    }

    private fun scopeCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(16, 16)
        col.addView(ui.bodyText(when {
            config.frameworkAllApps -> "Every app on the device reads USB debugging as off. No list to maintain."
            config.targets.isEmpty() -> "No app is covered yet. Pick the detectors you want to fool, or turn on \"Cover every app\"."
            else -> "${config.targets.size} app(s) covered. The rest of the device reads the truth."
        }))
        if (!config.frameworkAllApps) {
            col.addView(ui.linkRow("Choose apps") {
                startActivity(Intent(this@MainActivity, ScopeActivity::class.java))
            })
        }
        card.addView(col)
        return card
    }

    // ------------------------------------------------------------------ plumbing

    private fun shortKeys(keys: List<String>): String = keys.joinToString(", ") {
        when (it) {
            "development_settings_enabled" -> "dev"
            "adb_wifi_enabled" -> "adb_wifi"
            "adb_enabled" -> "adb"
            else -> it
        }
    }

    private fun labelForUid(uid: Int): String {
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
        val pkg = packages?.firstOrNull() ?: return "uid $uid"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun globalSetting(key: String): String = try {
        Settings.Global.getString(contentResolver, key) ?: "—"
    } catch (_: Throwable) {
        "—"
    }

    private fun systemProperty(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty().ifEmpty { "—" }
    } catch (_: Throwable) {
        "—"
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
