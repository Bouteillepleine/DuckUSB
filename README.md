# DuckUSB (Zygisk)

DuckUSB without Xposed. Chosen apps read USB debugging, wireless debugging and Developer Options
as **off**, and read `sys.usb.*` / `init.svc.adbd` as if adb were not running — while the shell,
the system and everything unscoped keep seeing the truth.

Requires root and a Zygisk implementation (ZygiskNext / NeoZygisk, ReZygisk, Zygisk on KernelSU,
or Magisk's built-in Zygisk). No LSPosed, and nothing is registered with `ServiceManager`.

## Verified on device

OnePlus 15 (CPH2747), Android 16 / SDK 36, KernelSU Next + ReZygisk. A scoped app reads:

| read path | scoped app | shell |
|---|---|---|
| `Settings.Global.getString` | `0` | `1` |
| `content://settings/global/adb_enabled` | `0` | `1` |
| `content://settings/global` with `name=?` | `0` | `1` |
| bulk `content://settings/global` sweep | `0` | `1` |
| `sys.usb.state` in-process | `mtp` | `mtp,adb` |
| `init.svc.adbd` in-process | `stopped` | `running` |

All four settings paths agree, which is the point: a detector that cross-checks the getter
against a direct cursor query sees one consistent answer.

## How it is built

| piece | where |
|---|---|
| module framework, dex injection, per-process scope | ZygoteLoader (`packages/` directory, consulted at fork) |
| Java method hooking | **LSPlant**, the engine LSPosed uses |
| inline hooking (for LSPlant and for libc) | **Dobby** — the `LSPosed/Dobby` fork; upstream will not assemble on NDK 29 |
| ART symbols | parsed out of `libart.so`, including the LZMA-compressed `.gnu_debugdata` mini-symtab (xz-embedded) |
| settings spoof | inside the scoped app: `Settings$NameValueCache.getStringForUser`, the static getters, and `ContentResolver.query` cursors |
| property spoof | inside the scoped app: libc `__system_property_get` / `_read_callback` / `_read` |

Non-scoped apps get no injection at all — ZygoteLoader checks the `packages/` directory per fork,
so nothing is loaded into processes you did not pick.

### system_server

Two features run there, both behind `hookSystemServer` (opt-in, off by default) and both armed
only **after `sys.boot_completed`** plus an 8 s settle, so a bad hook can never block a boot:

* **`frameworkMode`** — the settings spoof done in `system_server` on the settings provider's own
  `call` and `query`. Verified: a target app that is **not** in `packages/`, and therefore never
  injected, still reads `0` on all four paths. That is the stealthier layer — zero module
  footprint inside the app being fooled.
* **`hideNotif`** — swallows the "USB debugging enabled" notification at
  `NotificationManagerService.enqueueNotificationInternal`, matching by channel (`DEVELOPER`,
  `DEVELOPER_IMPORTANT`) and by the ROM's own localized titles. Verified by posting a notification
  titled "Débogage USB activé": swallowed, while an ordinary notification passed through and
  SystemUI's USB notification was untouched.

Two guards for when they are on:

* `service.sh` watches for `sys.boot_completed`; if it has not arrived within 150 s it writes
  `disable_hooks` and reboots, which survives a zygote crash loop (`post-fs-data.sh`'s boot
  counter does not — a crash loop never re-runs it).
* `disable_hooks` is also the manual kill switch, exposed in the app.

If a boot ever hangs: hold **Volume Down** during boot for KernelSU safe mode, which disables all
modules.

## Install

1. Flash the zip in your root manager (it aborts if no Zygisk implementation is present, and keeps
   an existing configuration).
2. Install the manager app and grant it root.
3. Reboot, open the app, **Choose apps**, pick your detectors.

Scope changes apply the next time an app starts. Writing config while a module update is staged in
`modules_update/` would otherwise be lost on the next boot, so the app mirrors writes into the
staged copy.

## Layout

```
common/   config model and shared constants
zygote/   the module: ZygoteLoader entry point, hooks, native library
app/      the manager: status, toggles, scope picker, root writes
probe/    a debuggable app that logs every read path — the regression harness
external/ LSPlant, Dobby, xz-embedded
```

## Build

```bash
git submodule update --init --recursive
./gradlew :zygote:assembleRelease :app:assembleRelease
```

Needs CMake 3.31.6 (`sdkmanager "cmake;3.31.6"`, LSPlant wants ≥ 3.28 and C++23 modules) and
NDK 29.0.14206865. Outputs land in `zygote/build/outputs/magisk/release/` and
`app/build/outputs/apk/`.

## Known limits

* `getprop` is a separate process, so a detector comparing an in-process property read against
  `getprop` output still sees a difference. That is why `persist.sys.usb.config` is left truthful:
  a spoof that only half-matches is louder than no spoof.
* The notification suppressor is proven against a notification carrying the ROM's own ADB title,
  not against a live USB replug — the match path is identical, but the replug case is untested.
* Zygisk itself remains detectable. This removes the LSPosed surface, not the Zygisk one.
