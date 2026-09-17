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
        return result.isSuccess
    }

    fun syncPackages(targets: Set<String>): Boolean {
        val keep = LinkedHashSet<String>().apply {
            add(Config.SYSTEM_SERVER_PACKAGE)
            addAll(targets)
        }
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
        return result.isSuccess
    }

    fun listedPackages(): Set<String> {
        val result = exec("ls -1 ${Config.PACKAGES_DIR} 2>/dev/null")
        if (!result.isSuccess) return emptySet()
        return result.out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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

    private val FAILED = object : Shell.Result() {
        override fun getOut(): MutableList<String> = ArrayList()
        override fun getErr(): MutableList<String> = ArrayList()
        override fun getCode(): Int = -1
    }
}
