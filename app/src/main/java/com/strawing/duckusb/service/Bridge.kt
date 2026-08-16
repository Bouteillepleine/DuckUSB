package com.strawing.duckusb.service

/**
 * Constants for the "parasitic" binder channel between system_server and the UI.
 *
 * The app fetches the service by calling the settings provider with [METHOD] as the
 * ContentProvider call() method name; DuckUSB's existing SettingsProvider.call hook
 * intercepts it before the real provider sees it and hands back the binder.
 *
 * No ServiceManager registration, so the service never shows in `service list`.
 */
object Bridge {
    /** Authority we ride. Any settings URI works; secure matches the existing hook path. */
    const val URI = "content://settings/secure"

    /** call() method name. Not a real SettingsProvider method, so stock behaviour is null. */
    const val METHOD = "duckusb_get_service"

    /** call() arg, checked as a second factor. */
    const val ARG = "service"

    /** Bundle key the IBinder is returned under. */
    const val KEY_BINDER = "binder"
}
