package com.strawing.duckusb.zygote.service

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.util.ArrayMap
import android.util.SparseBooleanArray
import com.strawing.duckusb.IDuckService
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

class DuckService(private val context: Context) : IDuckService.Stub() {

    companion object {
        const val VERSION = 2
        private const val MAX_RECORDS = 256
    }

    @Volatile
    var config: DuckConfig = ModuleConfig.config

    @Volatile
    var hookCount = 0

    @Volatile
    var installedAtRealtimeMs = 0L

    @Volatile
    var notifBlocked = 0

    private class Rec(
        var count: Int = 0,
        var lastMs: Long = 0L,
        val keys: MutableSet<String> = HashSet(3),
    )

    private val lock = Any()
    private val records = ArrayMap<Int, Rec>()
    private val spareCache = SparseBooleanArray()
    private val targetCache = SparseBooleanArray()

    val callerAppId: Int = runCatching {
        context.packageManager.getApplicationInfo(Config.PKG, 0).uid % Config.PER_USER_RANGE
    }.getOrDefault(-1)

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (callerAppId < 0 || uid % Config.PER_USER_RANGE != callerAppId) {
            throw SecurityException("caller uid $uid is not the module")
        }
    }

    private fun packagesFor(uid: Int): Array<String>? {
        val token = Binder.clearCallingIdentity()
        return try {
            context.packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun isSpared(uid: Int): Boolean {
        synchronized(lock) {
            val i = spareCache.indexOfKey(uid)
            if (i >= 0) return spareCache.valueAt(i)
        }
        val spared = packagesFor(uid)?.any { it in Config.SPARE_PACKAGES } == true
        synchronized(lock) { spareCache.put(uid, spared) }
        return spared
    }

    fun isTarget(uid: Int): Boolean {
        if (uid % Config.PER_USER_RANGE < Config.FIRST_APP_UID) return false
        if (callerAppId >= 0 && uid % Config.PER_USER_RANGE == callerAppId) return false
        if (isSpared(uid)) return false
        synchronized(lock) {
            val i = targetCache.indexOfKey(uid)
            if (i >= 0) return targetCache.valueAt(i)
        }
        val target = packagesFor(uid)?.any { config.isTarget(it) } == true
        synchronized(lock) { targetCache.put(uid, target) }
        return target
    }

    private fun invalidateTargets() {
        synchronized(lock) { targetCache.clear() }
    }

    fun spoofingEnabled(): Boolean {
        val c = config
        return !c.paused && c.spoofSettings
    }

    fun hidingNotifications(): Boolean {
        val c = config
        return !c.paused && c.hideNotif
    }

    fun note(uid: Int, key: String) {
        synchronized(lock) {
            var rec = records[uid]
            if (rec == null) {
                if (records.size >= MAX_RECORDS) return
                rec = Rec()
                records[uid] = rec
            }
            rec.count++
            rec.lastMs = System.currentTimeMillis()
            if (rec.keys.size < 8) rec.keys.add(key)
        }
    }

    override fun getVersion(): Int {
        enforceCaller()
        return VERSION
    }

    override fun getState(): Bundle {
        enforceCaller()
        val c = config
        return Bundle().apply {
            putInt(Bridge.STATE_VERSION, VERSION)
            putInt(Bridge.STATE_HOOKS, hookCount)
            putLong(Bridge.STATE_INSTALLED_AT, installedAtRealtimeMs)
            putBoolean(Bridge.STATE_PAUSED, c.paused)
            putBoolean(Bridge.STATE_SPOOF_SETTINGS, c.spoofSettings)
            putBoolean(Bridge.STATE_SPOOF_PROPS, c.spoofProps)
            putBoolean(Bridge.STATE_HIDE_NOTIF, c.hideNotif)
            putBoolean(Bridge.STATE_COVER_QUERY, c.coverQueryPath)
            putInt(Bridge.STATE_NOTIF_BLOCKED, notifBlocked)
            putStringArrayList(Bridge.STATE_TARGETS, ArrayList(c.targets))
        }
    }

    override fun pushConfig(bundle: Bundle) {
        enforceCaller()
        val next = config.copy(targets = LinkedHashSet(config.targets))
        if (bundle.containsKey(Bridge.STATE_PAUSED)) next.paused = bundle.getBoolean(Bridge.STATE_PAUSED)
        if (bundle.containsKey(Bridge.STATE_SPOOF_SETTINGS)) next.spoofSettings = bundle.getBoolean(Bridge.STATE_SPOOF_SETTINGS)
        if (bundle.containsKey(Bridge.STATE_SPOOF_PROPS)) next.spoofProps = bundle.getBoolean(Bridge.STATE_SPOOF_PROPS)
        if (bundle.containsKey(Bridge.STATE_HIDE_NOTIF)) next.hideNotif = bundle.getBoolean(Bridge.STATE_HIDE_NOTIF)
        if (bundle.containsKey(Bridge.STATE_COVER_QUERY)) next.coverQueryPath = bundle.getBoolean(Bridge.STATE_COVER_QUERY)
        bundle.getStringArrayList(Bridge.STATE_TARGETS)?.let {
            next.targets = LinkedHashSet(it)
        }
        config = next
        ModuleConfig.config = next
        Logx.verbose = next.verboseLog
        invalidateTargets()
        Logx.i("config pushed: paused=${next.paused} settings=${next.spoofSettings} notif=${next.hideNotif} targets=${next.targets.size}")
    }

    override fun getRecords(): List<Bundle> {
        enforceCaller()
        synchronized(lock) {
            return records.map { (uid, rec) ->
                Bundle().apply {
                    putInt(Bridge.REC_UID, uid)
                    putInt(Bridge.REC_COUNT, rec.count)
                    putLong(Bridge.REC_LAST, rec.lastMs)
                    putStringArrayList(Bridge.REC_KEYS, ArrayList(rec.keys))
                }
            }
        }
    }

    override fun clearRecords() {
        enforceCaller()
        synchronized(lock) { records.clear() }
    }
}
