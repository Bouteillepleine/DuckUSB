package com.strawing.duckusb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.strawing.duckusb.service.DuckServiceClient
import com.strawing.duckusb.ui.DuckUi
import com.strawing.duckusb.ui.Health
import com.strawing.duckusb.ui.Theming
import io.github.libxposed.service.XposedService

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREF_RECORDS_EXPANDED = "svc_records_expanded"
        const val STATE_TAB = "tab"
        val PROP_KEYS =
            listOf("persist.sys.usb.config", "sys.usb.state", "sys.usb.config", "init.svc.adbd")
        val SETTING_KEYS =
            listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var ui: DuckUi

    private var tab = DuckUi.TAB_STATUS
    private var usingRemotePrefs = false
    private var recordsOpen = false

    private var svcState: Bundle? = null
    private var svcRecords: List<Bundle> = emptyList()
    private var rootSettings: Map<String, String> = emptyMap()
    private var serviceSettings: Map<String, String> = emptyMap()
    private var rootProps: Map<String, String> = emptyMap()
    private var rootAvailable = false
    private var propMaskInstalled = false
    private var updateLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        Theming.restore(this)

        prefs = resolvePrefs()
        savedInstanceState?.getInt(STATE_TAB, 0)?.takeIf { it != 0 }?.let { tab = it }
        recordsOpen = prefs.getBoolean(PREF_RECORDS_EXPANDED, false)

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

    private val serviceListener: (XposedService?) -> Unit = { svc ->
        runOnUiThread {
            if ((svc != null) != usingRemotePrefs && !isFinishing) recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        DuckApp.addListener(serviceListener)
    }

    override fun onStop() {
        DuckApp.removeListener(serviceListener)
        super.onStop()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        render()
        background {
            val state = DuckServiceClient.get(this)?.let { runCatching { it.state }.getOrNull() }
            val records = DuckServiceClient.get(this)
                ?.let { runCatching { it.records }.getOrNull() }
                .orEmpty()
                .sortedByDescending { it.getInt("count") }
            val truth = DuckServiceClient.get(this)?.let {
                runCatching { it.getTrueSettings(SETTING_KEYS.toTypedArray()) }.getOrNull()
            }
            val snapshot = RootTools.snapshot(SETTING_KEYS, PROP_KEYS)
            runOnUiThread {
                serviceSettings = truth?.let { bundle ->
                    SETTING_KEYS.mapNotNull { key ->
                        bundle.getString(key)?.takeIf { it.isNotEmpty() }?.let { key to it }
                    }.toMap()
                } ?: emptyMap()
                svcState = state
                svcRecords = records
                rootAvailable = snapshot.rootAvailable
                rootSettings = snapshot.settings
                rootProps = snapshot.props
                propMaskInstalled = snapshot.propMaskInstalled
                render()
            }
        }
    }

    private fun background(work: () -> Unit) {
        Thread { runCatching(work) }.apply { isDaemon = true }.start()
    }

    private fun frameworkLive(): Boolean = svcState != null

    private fun flag(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private fun setFlag(key: String, value: Boolean) {
        prefs.edit()?.putBoolean(key, value)?.apply()
        background {
            DuckServiceClient.pushConfig(
                this,
                flag(Config.KEY_PAUSED, false),
                flag(Config.KEY_SPOOF, true),
                flag(Config.KEY_HIDE_NOTIF, true),
            )
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
                ui.content.addView(coverageCard())
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
                ui.content.addView(ui.footer("Xposed module · changes to the notification hook apply on reboot"))
            }
        }
    }

    private fun statusCard(): View {
        val paused = flag(Config.KEY_PAUSED, false)
        val spoofing = frameworkLive() && !paused && flag(Config.KEY_SPOOF, true)
        val health = when {
            paused -> Health.PAUSED
            spoofing -> Health.GOOD
            else -> Health.BAD
        }
        val title = when {
            !frameworkLive() -> "Not active"
            paused -> "Paused"
            !flag(Config.KEY_SPOOF, true) -> "Settings spoof is off"
            else -> "Active — framework mode"
        }
        val detail = when {
            !frameworkLive() -> "Enable DuckUSB in LSPosed, tick System Framework in its scope, then reboot."
            paused -> "Everything reads true again. The hook stays loaded until you unpause."
            !flag(Config.KEY_SPOOF, true) -> "The hook is live but the settings spoof is switched off."
            else -> "Hook live in system_server, covering every app"
        }
        return ui.heroCard(title, detail, health, "Pause", paused, true) { checked ->
            setFlag(Config.KEY_PAUSED, checked)
            renderContent()
        }
    }

    private fun infoCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(10, 10)
        col.addView(ui.infoRow("Module version", appVersion()))
        col.addView(ui.infoRow("Hook engine", frameworkName() ?: "Xposed"))
        col.addView(ui.infoRow("System Framework scoped", when (systemScoped()) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        }))
        col.addView(ui.infoRow("Hook live", if (frameworkLive()) "yes" else "no"))
        col.addView(ui.infoRow("Root", if (rootAvailable) "available" else "no"))
        col.addView(ui.infoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        col.addView(ui.infoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}"))
        card.addView(col)
        return card
    }

    private fun controlsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(6, 6)
        col.addView(ui.toggleRow(
            DuckUi.Icons.usb, "Spoof USB debugging",
            "adb_enabled · adb_wifi_enabled · Developer Options → 0",
            flag(Config.KEY_SPOOF, true),
        ) { setFlag(Config.KEY_SPOOF, it); renderContent() })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.query, "Cover the query path",
            "Also rewrites cursor reads of the settings tables, so a direct query agrees with the getter.",
            flag(Config.KEY_COVER_QUERY, true),
        ) { setFlag(Config.KEY_COVER_QUERY, it) })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.notification, "Hide the notification",
            "Swallows the persistent USB debugging notification. Needs a reboot.",
            flag(Config.KEY_HIDE_NOTIF, true),
        ) { setFlag(Config.KEY_HIDE_NOTIF, it) })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.tag, "Mask the USB config property",
            "persist.sys.usb.config reads mtp instead of adb, in the property area itself so every read route agrees. Needs root.",
            propMaskInstalled,
        ) { wanted ->
            propMaskInstalled = wanted
            setFlag(Config.KEY_MASK_USB_PROP, wanted)
            renderContent()
            background {
                val ok = if (wanted) RootTools.setPropMask(true) else {
                    RootTools.setPropMask(false)
                    RootTools.restoreProp()
                }
                runOnUiThread {
                    if (!ok) {
                        propMaskInstalled = !wanted
                        Toast.makeText(
                            this@MainActivity,
                            "Needs root — grant DuckUSB superuser access, then try again.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    reload()
                }
            }
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.log, "Verbose log",
            "One logcat line per spoofed read. Off unless you are investigating.",
            flag(Config.KEY_VERBOSE_LOG, false),
        ) { setFlag(Config.KEY_VERBOSE_LOG, it) })
        col.addView(ui.thinDivider())
        col.addView(updateRow())
        card.addView(col)
        return card
    }

    private fun updateRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(6))
        }
        row.addView(ui.bodyText(updateLabel ?: "Check GitHub for a newer release."))
        row.addView(ui.linkRow("Check for updates") {
            updateLabel = "Checking…"
            renderContent()
            background {
                val result = Updater.check(appVersionCode(), frameworkApi())
                val text = when (result) {
                    is Updater.Result.Available -> "Version ${result.versionName} is available."
                    is Updater.Result.Blocked ->
                        "Version ${result.versionName} needs Xposed API ${result.needsApi}; this framework offers ${result.hasApi}."
                    Updater.Result.UpToDate -> "Up to date."
                    is Updater.Result.Failed -> "Check failed: ${result.reason}"
                }
                runOnUiThread { updateLabel = text; renderContent() }
            }
        })
        return row
    }

    private fun coverageCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(16, 16)
        col.addView(ui.bodyText(
            "Every app on the device reads USB debugging as off. The lie is told once in " +
                "system_server, so nothing is injected into the apps being fooled and there is " +
                "no scope list to maintain."
        ))
        if (systemScoped() == false) {
            col.addView(ui.noteRow(
                "DuckUSB is not scoped to System Framework, so the hook cannot install. " +
                    "Tick it in LSPosed and reboot."
            ))
        }
        card.addView(col)
        return card
    }

    private fun diagnosticsCard(): View {
        val card = ui.outlinedCard()
        card.setOnClickListener { reload() }
        val col = ui.column()
        val state = svcState
        if (state == null) {
            col.addView(ui.bodyText(
                "The system_server service is not answering. Either the module is not enabled, " +
                    "System Framework is not in its scope, or it has not been through a reboot yet."
            ))
            card.addView(col)
            return card
        }
        col.addView(ui.statRow("service version", state.getInt("version").toString()))
        col.addView(ui.statRow("provider hooks", state.getInt("hookCount").toString()))
        col.addView(ui.statRow("armed", "${state.getLong("installedAtRealtimeMs") / 1000}s into this boot"))
        col.addView(ui.statRow("apps spoofed", svcRecords.size.toString()))
        col.addView(ui.thinDivider())
        col.addView(ui.expander("Callers lied to since boot (${svcRecords.size})", recordsOpen) {
            recordsOpen = !recordsOpen
            prefs.edit()?.putBoolean(PREF_RECORDS_EXPANDED, recordsOpen)?.apply()
            renderContent()
        })
        if (recordsOpen) {
            if (svcRecords.isEmpty()) {
                col.addView(ui.noteRow("Nothing yet. An app has to read one of the keys first."))
            }
            for (record in svcRecords) {
                col.addView(ui.callerRow(
                    record.getString("label") ?: "uid ${record.getInt("uid")}",
                    "${record.getInt("count")}× ${shortKeys(record.getString("keys").orEmpty())}",
                ))
            }
        }
        card.addView(col)
        return card
    }

    private fun readingsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column()
        for (key in SETTING_KEYS) {
            col.addView(ui.readingRow(key, globalSetting(key), serviceSettings[key] ?: rootSettings[key]))
        }
        col.addView(ui.thinDivider())
        for (key in PROP_KEYS) {
            col.addView(ui.readingRow(key, systemProperty(key), rootProps[key]))
        }
        col.addView(ui.noteRow(
            "Left chip is what this app reads, right chip is the real value read in " +
                "system_server (or by root for the properties). DuckUSB never spoofs itself, so " +
                "a mismatch here means something else on this device is spoofing this app."
        ))
        card.addView(col)
        return card
    }

    private fun shortKeys(keys: String): String = keys
        .split(", ")
        .joinToString(", ") {
            when (it) {
                "development_settings_enabled" -> "dev"
                "adb_wifi_enabled" -> "adb_wifi"
                "adb_enabled" -> "adb"
                else -> it
            }
        }

    // ------------------------------------------------------------------ plumbing

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

    private val moduleScope: List<String>? by lazy {
        runCatching { DuckApp.service?.scope }.getOrNull()
    }

    private fun systemScoped(): Boolean? =
        moduleScope?.any { it == "system" || it == "android" }

    private fun frameworkName(): String? = runCatching {
        DuckApp.service?.let { "${it.frameworkName} ${it.frameworkVersion}" }
    }.getOrNull()

    private fun frameworkApi(): Int? = runCatching { DuckApp.service?.apiVersion }.getOrNull()

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

    private fun appVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: Throwable) {
        0L
    }
}
