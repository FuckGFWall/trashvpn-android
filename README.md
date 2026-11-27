<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="112" alt="TrashVPN 图标">
</p>

<h1 align="center">TrashVPN</h1>
<p align="center">简体中文 · <a href="README.en.md">English</a> · <a href="README.fa.md">فارسی</a></p>
<p align="center"><a href="https://github.com/FuckGFWall/trashvpn-android/releases">下载</a> · <a href="LICENSE">GPL-3.0</a></p>

**TrashVPN** 是一款第三方 Cloudflare WARP Android 客户端，使用 Mihomo 内核，支持自动注册、节点优选和节点导出。

支持 **Android 8.0+**。

## 功能

- **多种协议**：WireGuard（WG）、AmneziaWG（AWG）和 MASQUE（HTTP/3）。
- **自动注册与节点优选**：注册 WARP 身份，筛选可用节点，也可以手动选择节点并测试延迟。
- **自动选择稳定节点**：优选不会启动系统 VPN。并发粗筛后，在同一个 Mihomo 内核中做多次 HTTPS 精筛及复测，优先考虑成功率和稳定性，再比较 P90 和平均耗时。真正连接后仍会单独验证，不可用时会尝试下一节点。
- **MASQUE (H3) 优选**：先测试历史成功节点和已知网关，再交错抽样两个 WARP IPv4 网段。暂停后可继续原计划；长按「继续优选」也可选择「重新优选」，从新样本开始。
- **节点导出**：将有效缓存节点导出到兼容的客户端。
- **连接设置**：支持调整优选强度、MTU 和 DNS。
- **多语言**：简体中文、繁體中文、English 和 فارسی。

## 安装与使用

1. 前往 [Releases](https://github.com/FuckGFWall/trashvpn-android/releases) 或 [软件官网](https://itmanager.top/download.html) 下载APK。。
2. 打开应用，等待软件自动完成注册和优选。
3. 选择节点并连接；首次连接时，请在 Android 系统提示中允许 VPN 连接。

## 开发与构建

需要 JDK 17 和 Android SDK Platform 36。可使用 Android Studio 打开项目，或在 Windows 上运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\fetch-mihomo.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

脚本会下载所需的 Mihomo Android 依赖并校验其 SHA-256。调试 APK 输出在 `app/build/outputs/apk/debug/`。

## 鸣谢

- [Oblivion](https://github.com/bepass-org/oblivion) 提供的快速WARP注册思路
- [TrashVPN for iOS](https://github.com/FuckGFWall/trashvpn-ios) 提供的UI参考
- [TrashVPN for Windows](https://github.com/FuckGFWall/trashvpn-windows) 提供的优选思路

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 发布。
