# DuckUSB (Zygisk)

DuckUSB without Xposed. Same two tricks as the LSPosed module — make chosen apps read USB
debugging, wireless debugging and Developer Options as **off**, and swallow the persistent
"USB debugging enabled" notification — implemented as a Zygisk module instead.

Requires root and a Zygisk implementation (ZygiskNext / NeoZygisk, ReZygisk, Zygisk on
KernelSU, or Magisk's built-in Zygisk). It does **not** require LSPosed, and nothing about it
is visible to `service list`.

## What it does

| | Where it runs |
|---|---|
| `adb_enabled`, `adb_wifi_enabled`, `development_settings_enabled` read `0` | system_server, `SettingsProvider.call` |
| the same keys rewritten in cursor reads (bulk table sweeps and `name=?` selections) | system_server, `SettingsProvider.query` |
| the ADB notification never reaches the shade | system_server, `NotificationManagerService.enqueueNotificationInternal` and `NotificationManager.notify*` |
| `sys.usb.config`, `sys.usb.state`, `init.svc.adbd` lie to scoped apps | the scoped app process, libc `__system_property_*` |

Everything is per-caller: the spoof only applies to the packages you pick. Root, shell,
system uids, the OS file-transfer plumbing (`com.android.mtp` and friends) and DuckUSB itself
always get the truth, so adb, MTP and the Settings toggle keep working.

### Compared with the LSPosed build

* **No per-app Java hooks.** The settings spoof lives entirely in system_server, so a scoped
  app's own process gets no LSPlant residue — no dirty `libart.so` / `linker64` pages for a
  detector to find in its own `smaps`. Only the property spoof touches the app process, and
  only when you enable it.
* **The query path is covered.** The LSPosed build spoofed the static getters and the provider
  `call`; a caller that queried `content://settings/global` directly read the true value, and
  cross-checking the two paths exposed the spoof. This build rewrites the cursor too.
* **Scope without LSPosed.** The app list is the module's own `packages/` directory, which
  ZygoteLoader consults per process, so non-scoped apps get no injection at all.
* **A boot guard.** Three failed boots in a row and `post-fs-data.sh` disables the hooks by
  itself. There is also a manual kill switch in the app.

## Install

1. Flash `DuckUSB-Zygisk-<version>-release.zip` in your root manager. The installer aborts if
   no Zygisk implementation is present, and keeps any existing configuration.
2. Install the manager app (`app-release.apk`) and grant it root.
3. Reboot.
4. Open the app → **Scope** → pick the apps that should be lied to.

Scope changes apply to apps started afterwards; the settings spoof applies immediately to
anything that reads a key after the change.

## Layout

```
common/   config model, AIDL, the constants both halves share
zygote/   the Zygisk module: ZygoteLoader entry point, the hooks, the libc spoof
app/      the manager: status, toggles, scope picker, root writes
external/ AndroidVMTools (submodule) — ART method hooking without Xposed
```

The module talks to the app over a binder handed out through the settings provider hook
itself (`call("duckusb_get_service", "service")`), gated on the manager's own uid. Nothing is
registered with `ServiceManager`.

Configuration lives in `/data/adb/modules/duckusb_zygisk/config.json`, read at process fork
through the module-directory fd and pushed live over the binder when the app changes it.

## Build

```bash
git submodule update --init
./gradlew :zygote:assembleRelease :app:assembleRelease
```

Outputs: `zygote/build/outputs/magisk/release/` (the flashable zip) and
`app/build/outputs/apk/release/`. Drop a `key.properties` next to `build.gradle.kts` to sign
the manager app; the module zip needs no signing.

## Known limits

* `getprop` is a separate process, so a detector that compares an in-process property read
  against `getprop` output still sees a difference. That is why `persist.sys.usb.config` is
  deliberately left truthful.
* Java hooking in system_server rides ART internals (AndroidVMTools). New Android releases can
  break it before the upstream library catches up — that is what the boot guard is for.
* Zygisk itself remains detectable. This module removes the LSPosed surface, not the Zygisk one.
