# DuckUSB

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

An LSPosed / Xposed module that makes apps read **USB debugging as OFF while it stays really ON** on the device — same for wireless debugging and the Developer Options master toggle. It also spoofs the raw USB system properties, and can hide the persistent *"USB debugging enabled"* notification.

Detection apps (banking, MDM/Intune, games, integrity checks) don't read any real "adb" state — they query the settings provider:

```
Settings.Global.getInt(cr, "adb_enabled")                   // USB debugging
Settings.Global.getInt(cr, "adb_wifi_enabled")              // wireless / ADB-over-Wi-Fi
Settings.Global.getInt(cr, "development_settings_enabled")  // Developer Options
```

## Two ways to spoof settings — pick one

**Framework mode (recommended).** One hook in `system_server` covers **every app at once**, with no per-app scope. DuckUSB hooks `ContentProvider.attachInfo`, waits for the settings provider to attach (matched by its **authority**, so ROMs that subclass `SettingsProvider` still work), then hooks only that provider's `call()`. Results are rewritten per caller, gated on `Binder.getCallingUid()`, and `_generation_index` is set to `-1` so the client-side settings cache can't serve a stale real value.

Callers at **uid < 10000** (root / system / shell) always see the truth, so `adb` and the Settings toggle keep working. OS file-transfer plumbing is spared too — see below.

**Per-app mode.** The original client-side hook on the static `Settings.Global` / `Settings.Secure` getters, installed inside each scoped app. Still supported for cases where you don't want `system_server` touched at all.

The two are **mutually exclusive** and the UI enforces it: framework mode already covers everything per-app mode would, and per-app short-circuits reads inside the app so the framework hook never sees them — which would blank those callers from the diagnostics.

## Diagnostics — knowing it actually works

Framework mode publishes a small binder service inside `system_server`. The app fetches it by calling the settings provider with a private method name, gated on the caller's app id; on any mismatch the hook does nothing, so the call is indistinguishable from stock. It is never registered with `ServiceManager`.

That gives the app a **Framework service** card showing the service version, how many `SettingsProvider.call` hooks are installed, how long ago, and a **list of every caller that was lied to since boot** with per-key counts. Without this, a mis-scoped module looks identical to a working one — which is the single most confusing failure this module has.

Config changes also push over the binder, so toggles apply to `system_server` **immediately**, with no reboot.

## Spoofing the system properties

Some detectors skip the settings provider and read the raw USB/adb **system properties**, either through `android.os.SystemProperties` or by calling libc directly. DuckUSB spoofs those too, at two levels:

- **Java** — hooks `android.os.SystemProperties.native_get*`.
- **Native** — `libduckusb.so` inline-hooks `__system_property_get` and `__system_property_find`, catching native code and the modern `SystemProperties.get()` path that the Java hook alone would miss. Installed once per process; the override map is published lock-free so a property read from inside the hook can't deadlock.

This is **automatic in every scoped non-core app** — there is no toggle. Scoping an app already states the intent, and the app cannot read LSPosed's scope to gate a switch honestly.

⚠️ **Property reads are process-local**, so no `system_server` hook can reach them. This half is inherently per-app: it only works in apps you scope.

## Never lied to

Three independent guards, each covering a gap the others miss:

- **uid** — anything below 10000 is OS.
- **process name** — `android`, `system`, `com.android.systemui`, `com.android.settings`, `com.android.shell`, `com.android.phone`.
- **package name** — the same core list, for plain per-app cases.

The process guard matters more than it looks. `handleLoadPackage` fires **once per package hosted in a process**, not once per process: with the `system` scope, `system_server` reports `android`, `com.android.providers.settings`, `com.android.location.fused`, `com.android.server.telecom` and others, all at uid 1000. A package-name check catches only the first. Some ROMs also load plugins into the SystemUI process under their own package names at an app uid.

Getting this wrong is not cosmetic: telling `system_server` that `sys.usb.ffs.ready=0` takes the whole USB gadget down — no MTP **and** no adb.

OS file-transfer components are spared from both the settings and property spoof, because they run at **app** uids and the uid guard doesn't reach them: `com.android.mtp`, `com.android.externalstorage`, `com.android.storagemanager`, `com.android.sharedstoragebackup`, and the OnePlus/OPlus file managers. See `SPARE_PACKAGES` in [`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt).

## Install

1. Build (`./gradlew :app:assembleRelease`) or grab the APK from the CI artifact / releases.
2. Install it and enable **DuckUSB** in LSPosed.
3. LSPosed → DuckUSB → **Scope**:
   - **"Cadre du sous-système" / package `system`** — this is the entry that injects into `system_server`. Framework mode needs it.
     ⚠️ **Not** "Système Android" / package `android` — that one does *not* inject into `system_server`, and picking it gives you a module that looks enabled and does nothing.
   - **System UI** — for the notification hider.
   - Individual apps — only if you want the **system-property** spoof for them.
4. **Reboot** after scoping. Force-stop an individual app after scoping it.

The module declares an `xposedscope` recommendation so LSPosed highlights the right entries.

## Toggles

- **Pause** — master switch, ANDed over everything else. Pushes live to `system_server`, so it stops spoofing immediately. It does **not** uninstall hooks or unload the native library — those are fixed at process load. LSPosed's own switch is the real off.
- **Spoof USB debugging** — the `adb_enabled` / `adb_wifi_enabled` / Developer Options lie.
- **Framework mode** — server-side spoof (needs the `system` scope + reboot).
- **Per-app Settings spoof** — client-side fallback; greyed out while framework mode is on.
- **Hide "USB debugging" notification** — the `system_server` / System UI suppressor, matching by channel (`DEVELOPER` / `DEVELOPER_IMPORTANT`) and by the ROM's own localized title strings, resolved live so any language matches.
- **Verbose logging** — one LSPosed line per injection (package / process / uid / guards). Off by default; turn it on when a hook won't install.

Hook *installation* follows the toggles, not just hook bodies — enabling one feature places that feature's hooks and nothing else.

## Spoofed keys

| Key | Meaning | Forced value |
|-----|---------|--------------|
| `adb_enabled` | USB debugging | `0` |
| `adb_wifi_enabled` | Wireless debugging | `0` |
| `development_settings_enabled` | Developer Options | `0` |

Edit `SPOOF_KEYS` in [`DuckUSBModule.kt`](app/src/main/java/com/strawing/duckusb/DuckUSBModule.kt).

## Spoofed properties

| Property | Meaning | Forced value |
|----------|---------|--------------|
| `sys.usb.config` | Current USB function config | `mtp` |
| `sys.usb.state` | Current USB state | `mtp` |
| `init.svc.adbd` | adbd service state | `stopped` |

`sys.usb.ffs.ready` is **deliberately not spoofed** — it's the USB function-filesystem *ready* flag, machinery the USB stack acts on rather than telemetry a detector reads. `persist.sys.usb.config` is excluded as persisted / boot-influencing. Edit `PROP_OVERRIDES` in [`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt).

## Tested on

OnePlus 15 (CPH2747) / OxygenOS / Android 16, LSPosed + KernelSU. Framework mode is verified there and nowhere else — other ROMs are unknown, though the hook matches the settings provider by authority and the guards key off uid and process name rather than OEM-specific package names.

## CI

`.github/workflows/build.yml` builds a release APK on every push / PR and uploads it as the **DuckUSB-release** artifact. Signing material is **not** in the repo — CI reconstructs the keystore from repository secrets:

- `DUCKUSB_KEYSTORE_BASE64` — `base64 -w0` of the release `.jks`
- `DUCKUSB_STORE_PASSWORD`, `DUCKUSB_KEY_ALIAS`, `DUCKUSB_KEY_PASSWORD`

A fork without these secrets still builds; the release APK just comes out unsigned. For local builds, drop a git-ignored `key.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) next to the project — `app/build.gradle.kts` prefers it and falls back to the CI env vars otherwise.

## Build

- JDK 21 (Android Studio JBR). `gradle.properties` pins `org.gradle.java.home`.
- NDK `27.2.12479018` (pinned via `android.ndkVersion`) for the native `libduckusb.so`; override locally with `-PduckusbNdk=<version>`.
- `./gradlew :app:assembleRelease`
