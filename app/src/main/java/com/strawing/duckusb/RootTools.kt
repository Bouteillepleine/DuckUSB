package com.strawing.duckusb

import com.topjohnwu.superuser.Shell

object RootTools {

    const val BOOT_SCRIPT = "/data/adb/service.d/duckusb-usbprop.sh"
    const val USB_PROP = "persist.sys.usb.config"
    const val USB_SAFE = "mtp"
    const val ADBD_PROP = "init.svc.adbd"
    const val ADBD_SAFE = "stopped"
    private const val MASKED = "$USB_PROP=$USB_SAFE $ADBD_PROP=$ADBD_SAFE"

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    class Snapshot(
        val rootAvailable: Boolean,
        val settings: Map<String, String>,
        val props: Map<String, String>,
        val propMaskInstalled: Boolean,
    )

    fun warm() {
        runCatching { Shell.getShell() }
    }

    fun available(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun snapshot(settingKeys: List<String>, propKeys: List<String>): Snapshot {
        val script = buildString {
            append("[ -f $BOOT_SCRIPT ] && echo 'mask=1' || echo 'mask=0'\n")
            append("echo '#settings'\n")
            append("PATTERN='^(")
            append(settingKeys.joinToString("|"))
            append(")='\n")
            append("cmd settings list global 2>/dev/null | grep -E \"\$PATTERN\" || ")
            append("settings list global 2>/dev/null | grep -E \"\$PATTERN\"\n")
            append("echo '#props'\n")
            for (key in propKeys) append("echo '$key='\$(getprop $key)\n")
        }
        val rooted = try { Shell.getShell().isRoot } catch (_: Throwable) { false }
        val result = exec(script)
        if (!result.isSuccess && result.out.isEmpty()) {
            return Snapshot(false, emptyMap(), emptyMap(), false)
        }
        val settings = LinkedHashMap<String, String>()
        val props = LinkedHashMap<String, String>()
        var masked = false
        var section = ""
        for (raw in result.out) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                section = line
                continue
            }
            when (section) {
                "#settings" -> line.split("=", limit = 2).let { if (it.size == 2) settings[it[0]] = it[1] }
                "#props" -> line.split("=", limit = 2).let { if (it.size == 2) props[it[0]] = it[1] }
                else -> if (line == "mask=1") masked = true
            }
        }
        return Snapshot(rooted, settings, props, masked)
    }

    /**
     * Values are changed in the property area only, never in
     * /data/property/persistent_properties: init seeds sys.usb.config from the persisted
     * value at boot, so writing mtp to disk would bring USB up with no adb interface.
     *
     * -n is load-bearing for init.svc.adbd too. Going through property_service would fire
     * init's "on property:init.svc.adbd=stopped" rule, which clears sys.usb.ffs.ready and
     * takes the USB gadget down entirely.
     */
    fun setPropMask(enabled: Boolean): Boolean {
        if (!enabled) {
            return exec("rm -f $BOOT_SCRIPT").isSuccess
        }
        val script = """
            mkdir -p /data/adb/service.d
            cat > $BOOT_SCRIPT <<'DUCKUSB_BOOT'
            #!/system/bin/sh
            until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 1; done
            sleep 30
            RP=
            for candidate in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /data/adb/magisk/resetprop; do
                [ -x "${'$'}candidate" ] && RP="${'$'}candidate" && break
            done
            [ -z "${'$'}RP" ] && RP=${'$'}(command -v resetprop 2>/dev/null)
            [ -z "${'$'}RP" ] && exit 0
            pass=0
            while [ ${'$'}pass -lt 10 ]; do
                for pair in $MASKED; do
                    key=${'$'}{pair%%=*}
                    want=${'$'}{pair#*=}
                    [ "${'$'}(getprop ${'$'}key)" = "${'$'}want" ] || "${'$'}RP" -n "${'$'}key" "${'$'}want"
                done
                pass=${'$'}((pass + 1))
                sleep 30
            done
            DUCKUSB_BOOT
            chmod 0755 $BOOT_SCRIPT
        """.trimIndent()
        val written = exec(script).isSuccess
        applyNow()
        return written
    }

    fun applyNow(): Boolean {
        val script = """
            RP=
            for candidate in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /data/adb/magisk/resetprop; do
                [ -x "${'$'}candidate" ] && RP="${'$'}candidate" && break
            done
            [ -z "${'$'}RP" ] && RP=${'$'}(command -v resetprop 2>/dev/null)
            [ -z "${'$'}RP" ] && exit 1
            for pair in $MASKED; do
                key=${'$'}{pair%%=*}
                want=${'$'}{pair#*=}
                "${'$'}RP" -n "${'$'}key" "${'$'}want"
            done
        """.trimIndent()
        return exec(script).isSuccess
    }

    /**
     * Only ever undoes our own change: the mask is memory-only, so the persisted value is
     * still the truth and a reboot would restore it anyway.
     */
    fun restoreProp(): Boolean = exec(
        """
        RP=
        for candidate in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /data/adb/magisk/resetprop; do
            [ -x "${'$'}candidate" ] && RP="${'$'}candidate" && break
        done
        [ -z "${'$'}RP" ] && exit 1
        [ "${'$'}(getprop $USB_PROP)" = "$USB_SAFE" ] && "${'$'}RP" -n $USB_PROP adb
        [ "${'$'}(getprop $ADBD_PROP)" = "$ADBD_SAFE" ] && "${'$'}RP" -n $ADBD_PROP running
        exit 0
        """.trimIndent()
    ).isSuccess

    private fun exec(vararg commands: String): Shell.Result = try {
        Shell.cmd(*commands).exec()
    } catch (_: Throwable) {
        FAILED
    }

    private val FAILED = object : Shell.Result() {
        override fun getOut(): MutableList<String> = ArrayList()
        override fun getErr(): MutableList<String> = ArrayList()
        override fun getCode(): Int = -1
    }
}
