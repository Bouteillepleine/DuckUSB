package com.strawing.duckusb.common

object Bridge {
    const val URI = "content://settings/secure"
    const val METHOD = "duckusb_get_service"
    const val ARG = "service"
    const val KEY_BINDER = "binder"

    const val STATE_VERSION = "version"
    const val STATE_HOOKS = "hooks"
    const val STATE_INSTALLED_AT = "installedAt"
    const val STATE_PAUSED = "paused"
    const val STATE_SPOOF_SETTINGS = "spoofSettings"
    const val STATE_SPOOF_PROPS = "spoofProps"
    const val STATE_HIDE_NOTIF = "hideNotif"
    const val STATE_COVER_QUERY = "coverQueryPath"
    const val STATE_NOTIF_BLOCKED = "notifBlocked"
    const val STATE_TARGETS = "targets"

    const val REC_UID = "uid"
    const val REC_COUNT = "count"
    const val REC_LAST = "lastMs"
    const val REC_KEYS = "keys"
}
