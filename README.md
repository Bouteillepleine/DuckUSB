# DuckADB

[![Build APK](https://github.com/Bouteillepleine/DuckADB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckADB/actions/workflows/build.yml)

An LSPosed / Xposed module that makes **scoped apps read USB debugging as OFF while it stays really ON** on the device — and does the same for wireless debugging and the Developer Options master toggle. It can also **hide the persistent "USB debugging enabled" notification**.

Detection apps (banking, MDM/Intune, games, integrity checks) don't read any real "adb" state — they query the settings provider:

```
Settings.Global.getInt(cr, "adb_enabled")                  // USB debugging
Settings.Global.getInt(cr, "adb_wifi_enabled")             // wireless / ADB-over-Wi-Fi
Settings.Global.getInt(cr, "development_settings_enabled")  // Developer Options
Settings.Secure.getInt(cr, "adb_enabled")                  // legacy (pre-4.2) location
```

DuckADB hooks the static getters on `android.provider.Settings$Global` and
`android.provider.Settings$Secure` inside each **scoped** process. When the requested
key is one of the three above, it returns the "off" value (`0` / `"0"`). Every other
setting passes through untouched, and the device keeps debugging genuinely enabled so
`adb` still works for you.

## Hiding the notification

The persistent *"USB debugging enabled / Débogage USB activé"* notification is posted by
`system_server`, so a second, independent hook runs in the **System Framework** and
**System UI** processes. It swallows the post at `NotificationManager.notify*` and at
`NotificationManagerService.enqueueNotificationInternal`, matching the ADB notification
by its channel (`DEVELOPER` / `DEVELOPER_IMPORTANT`) and by the ROM's own localized
title strings (`adb_active_notification_title`, `adb_wifi_active_notification_title`),
resolved live so any wording/language matches.

To enable it: in LSPosed → DuckADB → Scope, also tick **System Framework** and
**System UI**, then reboot.

## CI

`.github/workflows/build.yml` builds a signed release APK on every push / PR (the module
signing key is in the repo, so no secrets are needed) and uploads it as the
**DuckADB-release** artifact.

## Why it's safe by design

- **Per-app scope.** Nothing happens until you tick apps in the LSPosed manager. The
  module ships with **no** default scope.
- **Core OS is hard-skipped.** Even if you accidentally scope the framework, DuckADB
  refuses to run in `android`, `com.android.settings`, `com.android.systemui`,
  `com.android.shell` and `com.android.phone`, so the Settings toggle and `adbd`
  never get lied to.

## Live toggles

The app has two switches, both **on** by default, written to a world-readable prefs file
that the hook re-reads on every call — so flipping them applies **without a reboot** (at
most force-stop the target app so it does a fresh read):

- **Spoof USB debugging** — the `adb_enabled` / `adb_wifi_enabled` / Developer Options lie.
- **Hide "USB debugging" notification** — the system_server / System UI suppressor.

Turn either off to temporarily let apps see the real state, then back on.

## Install

1. Build (`./gradlew :app:assembleRelease`) or grab the APK from
   `app/build/outputs/apk/release/app-release.apk`.
2. Install it, enable **DuckADB** in LSPosed.
3. LSPosed → DuckADB → **Scope**: tick the apps you want to fool.
4. Force-stop those apps (or reboot).

## Build

- JDK 21 (Android Studio JBR). `gradle.properties` pins `org.gradle.java.home`.
- `./gradlew :app:assembleRelease`

## Spoofed keys

| Key | Meaning | Forced value |
|-----|---------|--------------|
| `adb_enabled` | USB debugging | `0` |
| `adb_wifi_enabled` | Wireless debugging | `0` |
| `development_settings_enabled` | Developer Options | `0` |

To change the list, edit `SPOOF_KEYS` in
[`DuckADBModule.kt`](app/src/main/java/com/strawing/duckadb/DuckADBModule.kt).
