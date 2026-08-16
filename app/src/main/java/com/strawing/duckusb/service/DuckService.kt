package com.strawing.duckusb.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.ArrayMap
import com.strawing.duckusb.Config
import com.strawing.duckusb.IDuckService

/**
 * Lives in system_server. Holds the live config the hooks read, and records which UIDs
 * were actually lied to, so the UI can prove the framework half is running.
 *
 * Deliberately has NO handler thread and NO blocking hand-off: every call is a volatile
 * read or a short critical section, executed inline on the binder thread. (NoAbleism posts
 * to a HandlerThread and blocks the binder thread on an untimed CountDownLatch — that is a
 * way to wedge system_server binder threads, and none of this work needs it.)
 */
class DuckService(private val context: Context) : IDuckService.Stub() {

    companion object {
        /** Bump when the interface changes so the UI can warn about a stale service. */
        const val VERSION = 1

        /** Hard cap so a hostile/chatty device can't grow this without bound. */
        private const val MAX_RECORDS = 256

    }

    // ---- live config: hooks read these instead of re-parsing XSharedPreferences ----
    @Volatile var paused = false
    @Volatile var spoofSettings = true
    @Volatile var hideNotif = true

    // ---- liveness/diagnostics surfaced to the UI ----
    @Volatile var hookCount = 0
    @Volatile var installedAtRealtimeMs = 0L

    private class Rec(
        var count: Int = 0,
        var lastMs: Long = 0L,
        val keys: MutableSet<String> = HashSet(3),
    )

    private val lock = Any()
    private val records = ArrayMap<Int, Rec>()

    /**
     * Only the module's own appId may talk to this binder, in any user. The binder is only
     * ever handed out through the UID-gated bridge, but a binder that can leave the process
     * should defend itself rather than trust its distribution path.
     */
    val callerAppId: Int = runCatching {
        context.packageManager.getApplicationInfo(Config.PKG, 0).uid % 100000
    }.getOrDefault(-1)

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (callerAppId < 0 || uid % 100000 != callerAppId) {
            throw SecurityException("DuckUSB: caller uid $uid is not the module")
        }
    }

    /** uid -> "is OS file-transfer plumbing". Resolved once per uid; the hook path is hot. */
    private val spareCache = android.util.SparseBooleanArray()

    /**
     * True for callers that must see the truth even though they sit at an app uid.
     * Called on the settings hook path, so the PackageManager lookup is cached per uid.
     */
    fun isSpared(uid: Int): Boolean {
        synchronized(lock) {
            val i = spareCache.indexOfKey(uid)
            if (i >= 0) return spareCache.valueAt(i)
        }
        val token = Binder.clearCallingIdentity()
        var spared = false
        try {
            spared = context.packageManager.getPackagesForUid(uid)?.any { it in Config.SPARE_PACKAGES } == true
        } catch (_: Throwable) {
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        synchronized(lock) { spareCache.put(uid, spared) }
        return spared
    }

    /** Called from the settings hook when a caller is actually spoofed. Keep it O(1). */
    fun note(uid: Int, key: String) {
        synchronized(lock) {
            var r = records[uid]
            if (r == null) {
                if (records.size >= MAX_RECORDS) return
                r = Rec()
                records[uid] = r
            }
            r.count++
            r.lastMs = SystemClock.elapsedRealtime()
            r.keys.add(key)
        }
    }

    override fun getVersion(): Int = VERSION

    override fun getState(): Bundle {
        enforceCaller()
        return Bundle().apply {
            putInt("version", VERSION)
            putInt("hookCount", hookCount)
            putLong("installedAtRealtimeMs", installedAtRealtimeMs)
            putLong("nowRealtimeMs", SystemClock.elapsedRealtime())
            putBoolean("paused", paused)
            putBoolean("spoofSettings", spoofSettings)
            putBoolean("hideNotif", hideNotif)
            putInt("recordCount", synchronized(lock) { records.size })
        }
    }

    /** Live config push from the UI — replaces the XSharedPreferences round-trip. */
    override fun pushConfig(config: Bundle?) {
        enforceCaller()
        config ?: return
        if (config.containsKey("paused")) paused = config.getBoolean("paused")
        if (config.containsKey("spoofSettings")) spoofSettings = config.getBoolean("spoofSettings")
        if (config.containsKey("hideNotif")) hideNotif = config.getBoolean("hideNotif")
    }

    override fun getRecords(): List<Bundle> {
        enforceCaller()
        // Snapshot under the lock, then resolve names outside it.
        val uids = IntArray(synchronized(lock) { records.size })
        val counts = IntArray(uids.size)
        val lasts = LongArray(uids.size)
        val keys = arrayOfNulls<String>(uids.size)
        synchronized(lock) {
            for (i in 0 until records.size) {
                uids[i] = records.keyAt(i)
                val r = records.valueAt(i)
                counts[i] = r.count
                lasts[i] = r.lastMs
                keys[i] = r.keys.sorted().joinToString(", ")
            }
        }

        // PackageManager lookups need system identity, not the caller's.
        val token = Binder.clearCallingIdentity()
        try {
            val pm = context.packageManager
            return uids.indices.map { i ->
                val pkg = runCatching { pm.getPackagesForUid(uids[i])?.firstOrNull() }.getOrNull()
                val label = pkg?.let {
                    runCatching {
                        pm.getApplicationLabel(
                            pm.getApplicationInfo(it, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        ).toString()
                    }.getOrNull()
                }
                Bundle().apply {
                    putInt("uid", uids[i])
                    putInt("userId", uids[i] / 100000)
                    putString("packageName", pkg ?: "unknown")
                    putString("label", label ?: pkg ?: "unknown")
                    putString("keys", keys[i].orEmpty())
                    putInt("count", counts[i])
                    putLong("lastRealtimeMs", lasts[i])
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun clearRecords() {
        enforceCaller()
        synchronized(lock) { records.clear() }
    }
}
