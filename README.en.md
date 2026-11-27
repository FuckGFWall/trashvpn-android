<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="112" alt="TrashVPN icon">
</p>

<h1 align="center">TrashVPN</h1>
<p align="center"><a href="README.md">简体中文</a> · English · <a href="README.fa.md">فارسی</a></p>
<p align="center"><a href="https://github.com/FuckGFWall/trashvpn-android/releases">Download</a> · <a href="LICENSE">GPL-3.0</a></p>

**TrashVPN** is a third-party Cloudflare WARP client for Android that uses the Mihomo core and supports automatic registration, endpoint selection, and node export.

Supports **Android 8.0+**.

## Features

- **Multiple protocols:** WireGuard (WG), AmneziaWG (AWG), and MASQUE (HTTP/3).
- **Automatic registration and endpoint selection:** Register a WARP identity, find usable endpoints, or manually select nodes and test their latency.
- **Automatic selection of stable nodes:** Scanning does not start the system VPN. A single Mihomo core handles concurrent screening and repeated HTTPS checks. Ranking favors reliability and stability before P90 and mean latency. After connecting, TrashVPN verifies the tunnel separately and tries the next node if needed.
- **MASQUE (H3) selection:** Tests previously verified nodes and known gateways first, then samples from two WARP IPv4 subnets in rotation. Resume a paused plan, or long-press **Resume scan** and choose **Scan again** to draw a new sample.
- **Node export:** Export valid cached nodes for compatible clients.
- **Connection settings:** Configure endpoint selection intensity, MTU, and DNS.
- **Multiple languages:** Simplified Chinese, Traditional Chinese, English, and Persian.

## Installation and Usage

1. Download the APK from [Releases](https://github.com/FuckGFWall/trashvpn-android/releases) or the [official software website](https://itmanager.top/download.html).
2. Open the app and wait for automatic registration and endpoint selection.
3. Select a node and connect. On first use, allow the VPN connection when Android shows the permission prompt.

## Development and Building

JDK 17 and Android SDK Platform 36 are required. Open the project in Android Studio or run the following on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\fetch-mihomo.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The script downloads the required Mihomo Android dependency and verifies its SHA-256 checksum. The debug APK is output to `app/build/outputs/apk/debug/`.

## Acknowledgements

- [Oblivion](https://github.com/bepass-org/oblivion) for its fast WARP registration approach.
- [TrashVPN for iOS](https://github.com/FuckGFWall/trashvpn-ios) for UI reference.
- [TrashVPN for Windows](https://github.com/FuckGFWall/trashvpn-windows) for endpoint selection ideas.

## License

This project is distributed under the [GNU General Public License v3.0](LICENSE).
