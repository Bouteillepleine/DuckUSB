# DuckUSB

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

An LSPosed / Xposed module that makes **scoped apps read USB debugging as OFF while it stays really ON** on the device — and does the same for wireless debugging and the Developer Options master toggle. It can also **hide the persistent "USB debugging enabled" notification**.

Detection apps (banking, MDM/Intune, games, integrity checks) don't read any real "adb" state — they query the settings provider:

```
Settings.Global.getInt(cr, "adb_enabled")                  // USB debugging
Settings.Global.getInt(cr, "adb_wifi_enabled")             // wireless / ADB-over-Wi-Fi
Settings.Global.getInt(cr, "development_settings_enabled")  // Developer Options
Settings.Secure.getInt(cr, "adb_enabled")                  // legacy (pre-4.2) location
```

By default DuckUSB does this in **framework mode**: a single hook in **System Framework**
(system_server) on `android.content.ContentProvider$Transport.call` — the server-side
chokepoint every `ContentResolver.call` funnels through — rewrites the result for those
keys for **every** app at once, so you don't scope detectors one by one. It's gated by the
caller's UID: only real apps (UID ≥ 10000) are lied to, never shell or system, so `adbd`
and the Settings toggle keep seeing the truth and `adb` still works for you. It also resets
the reply's `_generation_index` to `-1` so the client-side settings cache can't serve a
stale real value. Every other setting passes through untouched.

A per-app **client-side fallback** (hooks the static getters on `Settings$Global` /
`Settings$Secure` inside a scoped app) is included but **off by default** — enable it only
for an app that somehow dodges framework mode.

## Hiding the notification

The persistent *"USB debugging enabled / Débogage USB activé"* notification is posted by
`system_server`, so a second, independent hook runs in the **System Framework** and
**System UI** processes. It swallows the post at `NotificationManager.notify*` and at
`NotificationManagerService.enqueueNotificationInternal`, matching the ADB notification
by its channel (`DEVELOPER` / `DEVELOPER_IMPORTANT`) and by the ROM's own localized
title strings (`adb_active_notification_title`, `adb_wifi_active_notification_title`),
resolved live so any wording/language matches.

To enable it: in LSPosed → DuckUSB → Scope, also tick **System Framework** and
**System UI**, then reboot.

## Spoofing the system properties

Some detectors skip the settings provider and read the raw USB/adb **system properties**
instead — either through `android.os.SystemProperties` or by calling libc directly. A
third hook (same per-app scope as the settings spoof) makes those read back as a plain,
MTP-only, adbd-stopped device. It works at two levels so nothing gets through:

- **Java** — hooks `android.os.SystemProperties.native_get*`, covering apps that call
  `SystemProperties.get()` / `getInt()` / `getBoolean()`.
- **Native** — `libduckusb.so` inline-hooks `__system_property_get` and
  `__system_property_find`, catching native code, `getprop` via `exec`, and the modern
  `SystemProperties.get()` path that routes through `__system_property_find` (which the
  Java hook alone would miss). Installed once per process; **skips core OS processes**
  (system_server / SystemUI) entirely, and the override map is published lock-free so a
  property read from inside the hook can never deadlock.

`persist.sys.usb.config` is deliberately left untouched. The override list lives in
`PROP_OVERRIDES` in [`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt).

## CI

`.github/workflows/build.yml` builds a release APK on every push / PR and uploads it as the
**DuckUSB-release** artifact. Signing material is **not** in the repo — CI reconstructs the
keystore from repository secrets:

- `DUCKUSB_KEYSTORE_BASE64` — `base64 -w0` of the release `.jks`
- `DUCKUSB_STORE_PASSWORD`, `DUCKUSB_KEY_ALIAS`, `DUCKUSB_KEY_PASSWORD`

A fork without these secrets still builds; the release APK just comes out unsigned. For
local builds, drop a git-ignored `key.properties` (`storeFile` / `storePassword` /
`keyAlias` / `keyPassword`) next to the project — `app/build.gradle.kts` prefers it and
falls back to the CI env vars otherwise.

## Why it's safe by design

- **Per-app scope.** Nothing happens until you tick apps in the LSPosed manager. The
  module ships with **no** default scope.
- **Core OS is hard-skipped.** Even if you accidentally scope the framework, DuckUSB
  refuses to run in `android`, `com.android.settings`, `com.android.systemui`,
  `com.android.shell` and `com.android.phone`, so the Settings toggle and `adbd`
  never get lied to.

## Live toggles

The app has three switches, all **on** by default, written to a world-readable prefs file
that the hook re-reads on every call — so flipping them applies **without a reboot** (at
most force-stop the target app so it does a fresh read):

- **Spoof USB debugging** — the `adb_enabled` / `adb_wifi_enabled` / Developer Options lie.
- **Hide "USB debugging" notification** — the system_server / System UI suppressor.
- **Spoof USB system properties** — the `sys.usb.*` / `init.svc.adbd` lie via the Java and
  native libc hooks (the native half applies on the target's next start).

Turn any off to temporarily let apps see the real state, then back on.

## Install

1. Build (`./gradlew :app:assembleRelease`) or grab the APK from
   `app/build/outputs/apk/release/app-release.apk`.
2. Install it, enable **DuckUSB** in LSPosed.
3. LSPosed → DuckUSB → **Scope**: tick **System Framework** + **System UI**. That alone
   covers the Settings spoof (framework mode) for every app plus the notification hider.
   Only add individual apps if you also want the **system-property** spoof for them.
4. **Reboot** after scoping System Framework. Force-stop an individual app after scoping it.

## Build

- JDK 21 (Android Studio JBR). `gradle.properties` pins `org.gradle.java.home`.
- NDK `27.2.12479018` (pinned via `android.ndkVersion`) for the native `libduckusb.so`.
- `./gradlew :app:assembleRelease`

## Spoofed keys

| Key | Meaning | Forced value |
|-----|---------|--------------|
| `adb_enabled` | USB debugging | `0` |
| `adb_wifi_enabled` | Wireless debugging | `0` |
| `development_settings_enabled` | Developer Options | `0` |

To change the list, edit `SPOOF_KEYS` in
[`DuckUSBModule.kt`](app/src/main/java/com/strawing/duckusb/DuckUSBModule.kt).

## Spoofed properties

Applied to scoped apps at the Java (`SystemProperties`) and native (libc) layers:

| Property | Meaning | Forced value |
|----------|---------|--------------|
| `sys.usb.ffs.ready` | ADB gadget function ready | `0` |
| `sys.usb.config` | Current USB function config | `mtp` |
| `sys.usb.state` | Current USB state | `mtp` |
| `init.svc.adbd` | adbd service state | `stopped` |

`persist.sys.usb.config` is intentionally excluded. To change the list, edit
`PROP_OVERRIDES` in [`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt).
