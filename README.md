# DuckUSB

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

Apps read USB debugging, wireless debugging and Developer Options as OFF while they stay ON. The "USB debugging enabled" notification is hidden.

| | |
|---|---|
| **Xposed** (`:app`) | APK. Enable in LSPosed, tick the `system` scope, reboot. |
| **Zygisk** (`:zygisk`) | Zip for KernelSU / Magisk plus a manager APK. Flash, reboot. |

## How

One hook in `system_server` on the settings provider's `call` and `query`, rewritten per caller uid. Nothing is injected into the fooled apps. Uids below 10000 and the OS file-transfer components read the truth, so adb, MTP and the Settings toggle keep working.

| | |
|---|---|
| `adb_enabled`, `adb_wifi_enabled`, `development_settings_enabled` | `0` |
| `persist.sys.usb.config` | `mtp` in the property area only; the persisted `adb` survives reboot |
| USB debugging notification | swallowed, boot-time one cancelled |

## Build

```
git submodule update --init --recursive
./gradlew :app:assembleRelease            # Xposed APK
./gradlew :zygisk:zygote:assembleRelease  # Zygisk module zip
./gradlew :zygisk:app:assembleRelease     # Zygisk manager APK
```

JDK 21; Zygisk needs NDK 29 and CMake 3.31.6. Unsigned without signing material.

## Releases

Tags `xposed-vX.Y.Z` and `zygisk-vX.Y.Z` publish each variant independently.

## Tested on

OnePlus 15 (CPH2747), OxygenOS, Android 16, KernelSU Next + ReZygisk + LSPosed. Clean on Duck Detector. Other ROMs untested; the provider is matched by authority and the guards by uid, not OEM package names.
