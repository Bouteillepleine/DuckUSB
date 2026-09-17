package com.strawing.duckusb.zygote.service

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.util.ArrayMap
import com.strawing.duckusb.IDuckService
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.zygote.util.Logx
import com.strawing.duckusb.zygote.util.ModuleConfig

class DuckService(private val context: Context) : IDuckService.Stub() {

    companion object {
        const val VERSION = 4
        private const val MAX_RECORDS = 256
    }

    @Volatile
    var hookCount = 0

    @Volatile
    var installedAtRealtimeMs = 0L

    @Volatile
    var notifBlocked = 0

    private class Rec(
        var count: Int = 0,
        var lastMs: Long = 0L,
        val keys: MutableSet<String> = LinkedHashSet(3),
    )

    private val lock = Any()
    private val records = ArrayMap<Int, Rec>()

    @Volatile
    private var resolvedAppId = -1

    val callerAppId: Int
        get() {
            if (resolvedAppId >= 0) return resolvedAppId
            val id = runCatching {
                context.packageManager.getApplicationInfo(Config.PKG, 0).uid % Config.PER_USER_RANGE
            }.getOrDefault(-1)
            if (id >= 0) resolvedAppId = id
            return id
        }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (callerAppId < 0 || uid % Config.PER_USER_RANGE != callerAppId) {
            throw SecurityException("caller uid $uid is not the module")
        }
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
        val c = ModuleConfig.config
        return Bundle().apply {
            putInt(Bridge.STATE_VERSION, VERSION)
            putInt(Bridge.STATE_HOOKS, hookCount)
            putLong(Bridge.STATE_INSTALLED_AT, installedAtRealtimeMs)
            putBoolean(Bridge.STATE_PAUSED, c.paused)
            putBoolean(Bridge.STATE_SPOOF_SETTINGS, c.spoofSettings)
            putBoolean(Bridge.STATE_HIDE_NOTIF, c.hideNotif)
            putBoolean(Bridge.STATE_COVER_QUERY, c.coverQueryPath)
            putBoolean(Bridge.STATE_ALL_APPS, c.frameworkAllApps)
            putInt(Bridge.STATE_NOTIF_BLOCKED, notifBlocked)
            putInt(Bridge.STATE_SPOOFED_APPS, synchronized(lock) { records.size })
        }
    }

    override fun pushConfig(bundle: Bundle) {
        enforceCaller()
        val next = ModuleConfig.config.copy(targets = LinkedHashSet(ModuleConfig.config.targets))
        if (bundle.containsKey(Bridge.STATE_PAUSED)) next.paused = bundle.getBoolean(Bridge.STATE_PAUSED)
        if (bundle.containsKey(Bridge.STATE_SPOOF_SETTINGS)) next.spoofSettings = bundle.getBoolean(Bridge.STATE_SPOOF_SETTINGS)
        if (bundle.containsKey(Bridge.STATE_HIDE_NOTIF)) next.hideNotif = bundle.getBoolean(Bridge.STATE_HIDE_NOTIF)
        if (bundle.containsKey(Bridge.STATE_COVER_QUERY)) next.coverQueryPath = bundle.getBoolean(Bridge.STATE_COVER_QUERY)
        if (bundle.containsKey(Bridge.STATE_ALL_APPS)) next.frameworkAllApps = bundle.getBoolean(Bridge.STATE_ALL_APPS)
        bundle.getStringArrayList(Bridge.STATE_TARGETS)?.let { next.targets = LinkedHashSet(it) }
        ModuleConfig.config = next
        Logx.verbose = next.verboseLog
        Logx.i("config pushed: paused=${next.paused} settings=${next.spoofSettings} allApps=${next.frameworkAllApps}")
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

    /**
     * The unspoofed values, read here in system_server where nothing lies to us. Lets the UI
     * show the real device state without needing root.
     */
    override fun getTrueSettings(keys: Array<out String>?): Bundle {
        enforceCaller()
        val out = Bundle()
        if (keys == null) return out
        val token = Binder.clearCallingIdentity()
        try {
            for (key in keys) {
                if (key.isNullOrEmpty()) continue
                val value = runCatching {
                    android.provider.Settings.Global.getString(context.contentResolver, key)
                }.getOrNull()
                out.putString(key, value ?: "")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return out
    }
}
