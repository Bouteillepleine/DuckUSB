package com.strawing.duckusb.zygisk

import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.topjohnwu.superuser.Shell

object Root {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    fun available(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun moduleInstalled(): Boolean =
        exec("test -d ${Config.MODULE_DIR}").isSuccess

    fun moduleDisabled(): Boolean =
        exec("test -f ${Config.MODULE_DIR}/disable").isSuccess ||
            exec("test -f ${Config.MODULE_DIR}/remove").isSuccess

    fun hooksKilled(): Boolean =
        exec("test -f ${Config.DISABLE_FILE}").isSuccess

    fun setHooksKilled(killed: Boolean) {
        if (killed) {
            exec("touch ${Config.DISABLE_FILE}", "chmod 0644 ${Config.DISABLE_FILE}")
            relabel(Config.DISABLE_FILE)
        } else {
            exec("rm -f ${Config.DISABLE_FILE}")
        }
    }

    fun readConfig(): DuckConfig? {
        val result = exec("cat ${Config.CONFIG_FILE}")
        if (!result.isSuccess) return null
        val text = result.out.joinToString("\n")
        if (text.isBlank()) return null
        return DuckConfig.parse(text)
    }

    fun writeConfig(config: DuckConfig): Boolean {
        val json = config.toJson()
        val result = exec(
            "mkdir -p ${Config.MODULE_DIR}",
            "cat > ${Config.CONFIG_FILE} <<'DUCKUSB_EOF'\n$json\nDUCKUSB_EOF",
            "chmod 0644 ${Config.CONFIG_FILE}",
        )
        relabel(Config.CONFIG_FILE)
        mirrorToStagedUpdate()
        return result.isSuccess
    }

    fun syncPackages(): Boolean {
        val keep = linkedSetOf(Config.SYSTEM_SERVER_PACKAGE, Config.PKG)
        val commands = ArrayList<String>()
        commands += "mkdir -p ${Config.PACKAGES_DIR}"
        commands += "find ${Config.PACKAGES_DIR} -mindepth 1 -maxdepth 1 -delete"
        for (pkg in keep) {
            if (!pkg.matches(PACKAGE_PATTERN)) continue
            commands += "touch ${Config.PACKAGES_DIR}/$pkg"
        }
        commands += "chmod 0755 ${Config.PACKAGES_DIR}"
        commands += "chmod 0644 ${Config.PACKAGES_DIR}/*"
        val result = exec(*commands.toTypedArray())
        relabel(Config.PACKAGES_DIR)
        relabel("${Config.PACKAGES_DIR}/*")
        mirrorToStagedUpdate()
        return result.isSuccess
    }

    fun globalSetting(key: String): String? {
        val result = exec("settings get global $key")
        if (!result.isSuccess) return null
        return result.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun property(key: String): String? {
        val result = exec("getprop $key")
        if (!result.isSuccess) return null
        return result.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun moduleVersion(): String? {
        val result = exec("grep -m1 '^version=' ${Config.MODULE_DIR}/module.prop 2>/dev/null")
        if (!result.isSuccess) return null
        return result.out.firstOrNull()?.trim()?.removePrefix("version=")?.trim()?.takeIf { it.isNotEmpty() }
    }

    class Snapshot(
        val rootAvailable: Boolean,
        val moduleInstalled: Boolean,
        val hooksKilled: Boolean,
        val config: DuckConfig?,
        val moduleVersion: String?,
        val zygisk: String,
        val settings: Map<String, String>,
        val props: Map<String, String>,
    )

    fun warm() {
        runCatching { Shell.getShell() }
    }

    fun snapshot(settingKeys: List<String>, propKeys: List<String>): Snapshot {
        val script = buildString {
            append("MD=${Config.MODULE_DIR}\n")
            append("[ -d \$MD ] && echo 'installed=1' || echo 'installed=0'\n")
            append("[ -f ${Config.DISABLE_FILE} ] && echo 'killed=1' || echo 'killed=0'\n")
            append("grep -m1 '^version=' \$MD/module.prop 2>/dev/null | sed 's/^/module/'\n")
            for (dir in ZYGISK_MODULES) {
                append("grep -m1 '^name=' $dir/module.prop 2>/dev/null | sed 's/^name=/zygisk=/'\n")
            }
            append("echo '#settings'\n")
            append("PATTERN='^(")
            append(settingKeys.joinToString("|"))
            append(")='\n")
            append("cmd settings list global 2>/dev/null | grep -E \"\$PATTERN\" || ")
            append("settings list global 2>/dev/null | grep -E \"\$PATTERN\"\n")
            append("echo '#props'\n")
            for (key in propKeys) append("echo '$key='\$(getprop $key)\n")
            append("echo '#config'\n")
            append("cat ${Config.CONFIG_FILE} 2>/dev/null\n")
        }
        val rooted = try { Shell.getShell().isRoot } catch (_: Throwable) { false }
        val result = exec(script)
        if (!result.isSuccess && result.out.isEmpty()) {
            return Snapshot(false, false, false, null, null, "unknown", emptyMap(), emptyMap())
        }

        var installed = false
        var killed = false
        var moduleVersion: String? = null
        var zygisk: String? = null
        val settings = LinkedHashMap<String, String>()
        val props = LinkedHashMap<String, String>()
        val configText = StringBuilder()
        var section = ""

        for (raw in result.out) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                section = line
                continue
            }
            when (section) {
                "#settings" -> line.split("=", limit = 2).let {
                    if (it.size == 2) settings[it[0]] = it[1]
                }
                "#props" -> line.split("=", limit = 2).let {
                    if (it.size == 2) props[it[0]] = it[1]
                }
                "#config" -> configText.append(raw).append('\n')
                else -> when {
                    line == "installed=1" -> installed = true
                    line == "killed=1" -> killed = true
                    line.startsWith("moduleversion=") ->
                        moduleVersion = line.removePrefix("moduleversion=").takeIf { it.isNotEmpty() }
                    line.startsWith("zygisk=") ->
                        if (zygisk == null) zygisk = line.removePrefix("zygisk=").takeIf { it.isNotEmpty() }
                }
            }
        }

        val config = configText.toString().takeIf { it.isNotBlank() }?.let { DuckConfig.parse(it) }
        return Snapshot(
            rootAvailable = rooted,
            moduleInstalled = installed,
            hooksKilled = installed && killed,
            config = config,
            moduleVersion = moduleVersion,
            zygisk = zygisk ?: "unknown",
            settings = settings,
            props = props,
        )
    }

    fun refreshDescription() {
        exec("sh ${Config.MODULE_DIR}/describe.sh")
    }

    fun zygiskFlavor(): String {
        for (dir in ZYGISK_MODULES) {
            val result = exec("grep -m1 '^name=' $dir/module.prop 2>/dev/null")
            val name = result.out.firstOrNull()?.trim()?.removePrefix("name=")?.trim()
            if (!name.isNullOrEmpty()) return name
        }
        if (exec("test -f /data/adb/magisk/magisk64").isSuccess) return "Magisk built-in"
        return "unknown"
    }

    fun listedPackages(): Set<String> {
        val result = exec("ls -1 ${Config.PACKAGES_DIR} 2>/dev/null")
        if (!result.isSuccess) return emptySet()
        return result.out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun mirrorToStagedUpdate() {
        if (!exec("test -d ${Config.MODULE_UPDATE_DIR}").isSuccess) return
        exec(
            "cp -af ${Config.CONFIG_FILE} ${Config.MODULE_UPDATE_DIR}/config.json",
            "rm -rf ${Config.MODULE_UPDATE_DIR}/packages",
            "cp -af ${Config.PACKAGES_DIR} ${Config.MODULE_UPDATE_DIR}/packages",
        )
    }

    private fun moduleContext(): String? {
        val result = exec("ls -Zd ${Config.MODULE_DIR}/module.prop")
        if (!result.isSuccess) return null
        val token = result.out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        return token?.takeIf { it.count { c -> c == ':' } >= 3 }
    }

    private fun relabel(path: String) {
        val context = moduleContext() ?: return
        exec("chcon $context $path")
    }

    private fun exec(vararg commands: String): Shell.Result = try {
        Shell.cmd(*commands).exec()
    } catch (_: Throwable) {
        FAILED
    }

    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9._]+")

    private val ZYGISK_MODULES = listOf(
        "/data/adb/modules/zygisksu",
        "/data/adb/modules/rezygisk",
        "/data/adb/modules/zygisk_next",
    )

    private val FAILED = object : Shell.Result() {
        override fun getOut(): MutableList<String> = ArrayList()
        override fun getErr(): MutableList<String> = ArrayList()
        override fun getCode(): Int = -1
    }
}
