# DuckUSB

**[English](README.md)** | 简体中文

> 本页仅为简要说明。构建方法等完整内容，以 [英文 README](README.md) 为准。

让应用读到的 USB 调试、无线调试和开发者选项均为“关闭”，实际上保持开启。同时隐藏“已启用 USB 调试”通知。

| | |
|---|---|
| **Xposed**（`:app`） | APK。在 LSPosed 中启用，勾选 `system` 作用域，重启。 |
| **Zygisk**（`:zygisk`） | 适用于 KernelSU / Magisk 的模块 ZIP，附带管理器 APK。刷入后重启。 |

两个版本分别以 `xposed-vX.Y.Z` 和 `zygisk-vX.Y.Z` 标签发布。

## 原理

只在 `system_server` 中 hook 设置提供程序的 `call` 与 `query`，按调用方 uid 改写结果。不向被欺骗的应用注入任何内容。uid 小于 10000 的进程和系统文件传输组件读到的是真实值，因此 adb、MTP 和设置中的开关照常可用。

| | |
|---|---|
| `adb_enabled`、`adb_wifi_enabled`、`development_settings_enabled` | `0` |
| `persist.sys.usb.config` | 仅在属性区中为 `mtp`；持久化的 `adb` 重启后保留 |
| USB 调试通知 | 拦截，开机时的那条会被取消 |
