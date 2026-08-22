# DuckUSB

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

An LSPosed / Xposed module that makes apps read **USB debugging as OFF while it stays really ON** — same for wireless debugging and Developer Options. It also spoofs the raw USB system properties and can hide the persistent *"USB debugging enabled"* notification.

Detection apps (banking, MDM, games, integrity checks) don't read any real adb state — they query the settings provider:

```
Settings.Global.getInt(cr, "adb_enabled")                   // USB debugging
Settings.Global.getInt(cr, "adb_wifi_enabled")              // wireless debugging
Settings.Global.getInt(cr, "development_settings_enabled")  // Developer Options
```

## Install

1. Install the APK and enable **DuckUSB** in LSPosed.
2. LSPosed → DuckUSB → **Scope**, tick the entry whose package is **`system`** — that is the one that injects into `system_server`, and framework mode needs it.
   > ⚠️ **Not** the entry whose package is `android`. That one does *not* inject into `system_server`; picking it gives you a module that looks enabled and silently does nothing. The module ships an `xposedscope` recommendation so LSPosed highlights the right entries.
3. Also tick **System UI** if you want the notification hidden, and any individual apps you want the property spoof for.
4. **Reboot.** Force-stop an individual app after scoping it.

## Two ways to spoof settings

**Framework mode (recommended)** — one hook in `system_server` covers every app, no per-app scope. Hooks `ContentProvider.attachInfo`, waits for the settings provider (matched by **authority**, so ROMs that subclass `SettingsProvider` still work), then hooks only that provider's `call()`. Results are rewritten per caller by `Binder.getCallingUid()`, with `_generation_index = -1` so the client settings cache can't serve a stale real value.

**Per-app mode** — the client-side hook on the static `Settings.Global` / `Settings.Secure` getters inside each scoped app. For when you don't want `system_server` touched at all.

The two are mutually exclusive and the UI enforces it: framework mode already covers everything per-app would, and per-app short-circuits reads inside the app, hiding those callers from the diagnostics.

Callers at **uid < 10000** (root / system / shell) always see the truth, so `adb` and the Settings toggle keep working.

## Diagnostics

Framework mode publishes a small binder service in `system_server`, fetched by calling the settings provider with a private method name gated on the caller's app id. On any mismatch the hook does nothing, so the call is indistinguishable from stock. It is never registered with `ServiceManager`.

The app then shows the hook count, uptime, and **every caller lied to since boot** with per-key counts. Without it, a mis-scoped module looks identical to a working one. Toggles also push over this binder and apply immediately, no reboot.

## System properties

Some detectors skip the settings provider and read the raw properties. DuckUSB hooks `SystemProperties.native_get*` and, natively, `__system_property_get` / `__system_property_find` (`libduckusb.so`) to catch the paths the Java hook alone would miss.

**Automatic in every scoped non-core app** — no toggle. Property reads are process-local, so no `system_server` hook can reach them; this half only works in apps you scope.

## DuckUSB never lies to itself

LSPosed loads a module into its own app process whether or not you scope it, so DuckUSB used to spoof its own UI. Two things were wrong with that. The readings card exists to report the **real** device state, and it was reading its own lie — `adb_enabled 0` and `init.svc.adbd stopped` on a device where they were `1` and `running`. Worse, `libduckusb.so` was inline-hooking libc in a process that had no reason to carry the hook: on a Nothing A065 / Android 16 build the first property read off the `EmojiCompatInit` thread hit the trampoline and took `SIGILL`, killing the app seconds after launch ([#2](https://github.com/Bouteillepleine/DuckUSB/issues/2)).

Both halves now skip our own package:

- **native** — `should_skip_hooks()` in [`native_hooks.cpp`](app/src/main/jni/native_hooks.cpp) refuses `com.strawing.duckusb` (and `:sub` processes) before installing anything. This is the one that matters: the hooks go in when the library loads, before any Kotlin gate runs.
- **framework mode** — the `SettingsProvider.call` hook spares our own app id, since a client-side guard cannot stop a lie told inside `system_server`.

Spoofing ourselves bought nothing — detectors are *other* apps — and cost the one screen meant to tell you the truth.

## Core processes are never lied to

Three guards, each covering a gap the others miss:

- **uid** — anything below 10000 is OS
- **process name** — `android`, `system`, `com.android.systemui`, `com.android.settings`, `com.android.shell`, `com.android.phone`
- **package name** — the same list, for plain per-app cases

The process guard matters: `handleLoadPackage` fires once per package *hosted* in a process, not once per process. With the `system` scope, `system_server` reports `android`, `com.android.providers.settings`, `com.android.location.fused`, `com.android.server.telecom` and more — all at uid 1000 — so a package-name check catches only the first.

This is not cosmetic. Telling `system_server` that `sys.usb.ffs.ready=0` takes the USB gadget down entirely: no MTP **and** no adb.

OS file-transfer components run at *app* uids, so the uid guard misses them; they're spared explicitly via `SPARE_PACKAGES` in [`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt) — `com.android.mtp`, `com.android.externalstorage`, `com.android.storagemanager`, `com.android.sharedstoragebackup` and the OnePlus/OPlus file managers.

## Toggles

| Toggle | What it does |
|---|---|
| **Pause** | Master switch, pushed live. Does *not* uninstall hooks — LSPosed's own switch is the real off. |
| **Spoof USB debugging** | The `adb_enabled` / `adb_wifi_enabled` / Developer Options lie. |
| **Framework mode** | Server-side spoof. Needs the `system` scope + reboot. |
| **Per-app Settings spoof** | Client-side fallback; greyed out while framework mode is on. |
| **Hide notification** | Suppressor in `system_server` / System UI, matched by channel and by the ROM's own localized title strings. |
| **Verbose logging** | One log line per injection (package / process / uid / guards). Off by default. |

Hook *installation* follows the toggles, so enabling one feature places that feature's hooks and nothing else.

## Spoofed values

| Setting | Forced |
|---|---|
| `adb_enabled` | `0` |
| `adb_wifi_enabled` | `0` |
| `development_settings_enabled` | `0` |

| Property | Forced |
|---|---|
| `sys.usb.config` | `mtp` |
| `sys.usb.state` | `mtp` |
| `init.svc.adbd` | `stopped` |

`sys.usb.ffs.ready` is deliberately **not** spoofed — it's the USB function-filesystem ready flag, machinery the USB stack acts on rather than telemetry detectors read. `persist.sys.usb.config` is excluded as persisted / boot-influencing. Lists live in `SPOOF_KEYS` ([`DuckUSBModule.kt`](app/src/main/java/com/strawing/duckusb/DuckUSBModule.kt)) and `PROP_OVERRIDES` ([`Config.kt`](app/src/main/java/com/strawing/duckusb/Config.kt)).

## Tested on

OnePlus 15 (CPH2747) / OxygenOS / Android 16, LSPosed + KernelSU. Framework mode is verified there and nowhere else — other ROMs are unknown, though the provider is matched by authority and the guards key off uid and process name rather than OEM-specific package names.

## Build & CI

- JDK 21, NDK `27.2.12479018` (override locally with `-PduckusbNdk=<version>`), `./gradlew :app:assembleRelease`
- CI builds a signed release APK on every push and uploads it as **DuckUSB-release**. Keystore comes from repository secrets: `DUCKUSB_KEYSTORE_BASE64`, `DUCKUSB_STORE_PASSWORD`, `DUCKUSB_KEY_ALIAS`, `DUCKUSB_KEY_PASSWORD`. Forks without them still build, just unsigned.
- Local builds can use a git-ignored `key.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`), which takes precedence over the env vars.

**Signing identity:** CI signs with the same key as the published releases, so any CI artifact installs in place over a release and vice versa. Android identifies an app by its signature — a build signed with a different key cannot update an installed one, so users would have to uninstall and lose their LSPosed enable/scope. Don't rotate the key unless you intend that break.
