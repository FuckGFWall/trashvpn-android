# TrashVPN for Android

第三方 Cloudflare WARP Android 客户端，支持 **AWG / WireGuard / MASQUE HTTP/3** 的设备注册、节点优选和手动连接。内置 Mihomo v1.19.34，经 `libmihomo-android` v0.3.14 JNI 桥接到 Android `VpnService`，不依赖另一款 VPN 应用，不需要 root。

## 构建与安装

需要 JDK 17 和 Android SDK Platform 36；应用最低支持 Android 8.0（API 26）。工程可直接用 Android Studio 打开。先获取固定版本的 AAR（脚本及 Gradle 均校验 SHA-256）：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\fetch-mihomo.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

如果 Gradle 提示找不到 SDK，请在 Android Studio 中设置 SDK 路径，或通过 `ANDROID_HOME` 指定。调试包包括 arm64-v8a、armeabi-v7a 和 x86_64，真机验证使用 arm64-v8a。

本地工程可以用忽略的 `.local/android-debug.keystore` 保持已安装测试包的更新签名一致；没有该文件时使用 Android 的标准调试签名。

### 正式分发

正式包名为 `com.leo.trashvpn`，调试包使用 `com.leo.trashvpn.debug`。正式版和旧的 `com.trashvpn.android` 互不覆盖，Android 不会在不同包名之间迁移 VPN 授权、注册身份或设置。

```powershell
.\tools\sign-release.ps1
```

脚本在第一次运行时生成独立的自签发布密钥，后续复用，输出通过签名验证的 `app/build/outputs/apk/release/TrashVPN-release.apk`。仅分发这个 APK；不要分发私钥、口令或未签名产物。请备份 `.local/trashvpn-release.p12` 及其口令，二者分开安全保存。默认口令文件 `.local/trashvpn-release.password.dpapi` 只能由生成它的 Windows 用户解封；迁移机器前，要将实际口令安全保存至自己的密码管理器。已有密钥可通过进程环境变量 `TRASHVPN_APK_PASSWORD` 提供口令，不要把值写进代码或 Git。

## 第一次连接

从主页右上角进入 **设置**，选择协议（默认 AWG）。返回主页，选择 **自动选择** 后按圆形开关。第一次使用该身份时，阅读并同意 Cloudflare 条款后才发起注册；以后会复用加密保存在本机的注册身份。

- **AWG / WG** 共用一个 X25519 身份。注册后启用 WARP，使用服务器下发的 IPv4、对端公钥及可选的 3 字节 reserved。
- **MASQUE** 使用独立设备记录：先创建设备，再登记 P-256 公钥并切换到 MASQUE；不会修改已注册的 WG 密钥。两个步骤之间在本机加密保存进度，PATCH 失败后重试可继续同一个设备，不会因为切换协议反复注册。
- 注册只请求 `api.cloudflareclient.com`。优先直连；如果在发送正文之前发生 DNS、TCP 连接失败或连接超时，且 Android 为该网址配置了 HTTP 代理，则通过该代理的 HTTPS CONNECT 通道重试一次。代理不替换目标地址，仍使用系统 TLS 校验，不跟随重定向，也不用第三方注册中继。证书错误、HTTP 拒绝或限流、发送后响应超时都不会自动重发，以免重复创建设备。没有可用系统代理时可切换网络；HTTP 429 请稍后手动重试。

首次启动隧道时 Android 会请求 VPN 授权。只有通过 WARP 出口验证，圆形开关才会变为全橙色并显示 **已连接**。连接后，主页显示验证响应中出口 IP 的国家旗帜，以及处理该请求的 Cloudflare 数据中心三字码。注册是进度条四分之一处的独立节点；优选缓存就绪后，进度条显示为绿色。

### 设置项

- **连接协议 / 外观**：分别用下拉菜单选 AWG、WireGuard、MASQUE，以及浅色、深色、跟随系统（默认）。应用语言支持简体中文、繁體中文、English 和 فارسی；首次打开按系统主语言匹配，不支持的语言使用英文。优选中或已连接时也可以切换协议，应用会先停止原任务；新协议没有缓存则先优选，已有缓存时，原先已连接的 VPN 会切换到新协议。
- **优选**：**优选完成后自动连接**默认关闭，可在优选进行中修改。关闭时，包括切换协议触发的优选在内，都需要在优选结束后手动连接。优选强度滑块会跟随手指连续移动，松手后吸附到最近的低、中、高档位；也可直接点击档位文字。探测超时为 1–60 秒，默认 7 秒，限制初筛和精筛的单次探测，仍受档位的总扫描预算约束。输入后按完成或离开输入框保存，无效值不覆盖旧设置。
- **网络**：MTU 可输入 576–9000，或填 0 自动。自动值参考当前底层链路并预留封装空间，最高 1280；无法取得链路 MTU 时用 1280。VPN 与 Mihomo 使用同一生效值。
- **DNS**：自定义字段支持单个 IPv4 地址，留空用 1.1.1.1。默认开启 **DNS 防泄漏**，捕获进入 VPN 的普通 53 端口 DNS 请求，并通过 WARP 查询配置的解析器；这不是对第三方应用自带加密 DNS 的全面拦截。开启 **不向隧道下发 DNS** 后，不为 VPN 配置 DNS，使用 [Android 的默认网络 DNS 行为](https://developer.android.com/reference/android/net/VpnService.Builder#addDnsServer(java.net.InetAddress))；自定义地址和防泄漏配置会保留，但暂不生效。此时客户端通过 IP 验证 WARP 出口，不把系统 DNS 查询是否成功当成隧道连通的条件；如果应用无法解析域名，可关闭这个选项。修改网络设置后，下次启动扫描或连接时读取新配置。

### 自动优选与缓存

在 **设置 → 优选强度** 选择低、中、高三档，默认 **中**，重启保留上次选择。主页进度条旁的 **优选 / 重新优选** 启动筛选，是否在成功后连接取决于设置。注册和优选时开关与节点选择不可用，但仍可进入设置；步骤和计数显示在进度条上方，结束后隐藏下方的进度说明。

| 强度 | WG/AWG 初筛池上限 | 并发数 | 本轮目标可用数 | 每节点精筛次数 | 扫描预算 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 低 | 48 | 6 | 2 | 1 | 30 秒 |
| 中 | 112 | 8 | 4 | 2 | 65 秒 |
| 高 | 224 | 10 | 8 | 3 | 130 秒 |

筛选不请求 VPN 权限、不创建系统 TUN。初筛借鉴 Windows 的协议专属候选池、历史种子、跨网段轮换与有界并发；MASQUE 当前只有 4 个白名单网关和 7 个端口，因此最多 28 对。每批在同一内核中创建独立的候选出站，通过各自的回环入口并发完成到 Cloudflare 的 TLS 握手，按批内耗时安排精筛。不是探测网关的 TCP 443，也不把握手成功当成 WARP 验证。Android 没有移植 Windows 的隧道内 ICMP 丢包计算。

精筛逐个通过该候选获取 HTTPS trace；只认 HTTP 200 且正文有 `warp=on` 或 `warp=plus`，按成功率和成功请求平均耗时排名。达到目标、预算或候选耗尽就暂停本轮，不保证测满整池。在途操作可略超扫描预算。

按 Home 或切换应用后，带通知的短时前台服务继续本轮优选；可从通知暂停。下次点 **重新优选** 会从断点继续。断点按协议和档位隔离，从本轮候选池创建起 **7 天**有效；保存候选顺序、已完成初筛的批次位置以及待精筛队列。每批初筛和每个精筛节点完成后保存，进程被杀时在途的一批或一个节点可能重测。整池完成后下一次生成新池。系统强行停止后不会自行唤醒。

**自动选择** 按缓存排名直接连接并验证出口，最多试前五个；失败后移出排名并自动尝试下一项。没有缓存或首轮缓存均未连通时，进行一次无 TUN 优选；启用自动连接才会用筛出的节点再连接一次，不无限循环。后台筛选完成时不抢占前台，自动连接会等用户返回应用。手动选定端点不经过两阶段筛选，也不会自动切到其他节点。

暂不实现 IPv6 隧道、后台持续健康监控、自动网络切换或完整 Windows 设置页。

### 热切换与连通性测试

- 已连接时，在同一协议的主页直接点选另一节点；应用先通过目标出站验证 WARP，再调用 [Mihomo 选择组](https://wiki.metacubex.one/config/proxy-groups/select/)切换路由，不重建 Android VPN 或 TUN。目标不可用时保留原节点。「自动选择」卡片右侧会显示当前连接实际使用的节点名。切换成功后旧连接会关闭，新连接使用新节点；正在传输的会话可能需要重连。切换协议仍需重建隧道。
- **选择节点 → 延迟** 测列表中当前协议的所有具体节点，包括内置节点和当前档位缓存；「自动选择」不是具体节点，不测。请求经各自的候选出站访问 HTTPS trace；WARP 验证通过显示绿色耗时，超时或验证失败显示红色 `-1`，测试中显示转圈。不重排或删除缓存。未连接时不创建 TUN；本应用连着 VPN 时使用 [Android 的 socket 保护](https://developer.android.com/reference/android/net/VpnService#protect(int))让候选连接直接走底层网络，不会再套一层当前节点；如果保护失败不会报告成功。数值是实际请求总耗时，不是 ping，会随握手和网络负载波动。

v0.7.1 在 LG LM-G710N 上复测：WG 的 4 个内置节点加 1 个额外缓存节点，断开和已连接时各收到 5 条探测结果；在线测试未重建 VPN 网络。红色 `-1` 只表示该次未能完成验证，不代表节点永久失效。

### 连接诊断

打开 **设置 → 关于 → 连接诊断**，可以查看近期的节点优选、VPN 连接和延迟测试记录。右上角的分享菜单可以复制当前日志，也可以导出为 UTF-8 文本文件。日志仅保存在本机，不自动上传，不包含设备凭据或 WARP 返回的出口 IP；记录会保留已探测的节点端点，分享前可先查看。

## 真机复测

连接 USB 调试后运行 `adb logcat -s TrashVPN`。成功连接会记录 `PASS <协议> <端点> warp=on`（或 `warp=plus`）；热切换记录 `HOT_SWITCH`，节点测试记录 `NODE_DELAY`；`STOPPED` 表示已关闭接口。日志不记录凭据或 Cloudflare 返回的出口 IP。

APK 已安装、手机已解锁、所测协议的身份已注册且 VPN 已授权时，可用以下命令复测。先把界面语言设为简体中文并关闭 **优选完成后自动连接**；脚本不会注册账户或点击条款确认。使用调试包时加 `--package com.leo.trashvpn.debug`，只测单个协议可加 `--protocol MASQUE`。

```powershell
python tools/smoke-selection.py --mode fresh
python tools/smoke-selection.py --scan-only --intensity low
python tools/smoke-selection.py --scan-only --home --intensity low
# 仅用于测试设备：在一批初筛落盘后强行停止本应用，重新打开并核对续扫位置。
python tools/smoke-selection.py --scan-only --restart-after-wave --intensity medium --protocol WG
python tools/smoke-selection.py --mode fresh --protocol MASQUE --intensity high
python tools/smoke-selection.py --mode cache --protocol MASQUE --intensity high --require-cache-hit
```

第一阶段基线（v0.1.0）：2026-11-25，在 LG LM-G710N（Android 10，Wi-Fi）上，用同一份 Debug APK 测试：

| 协议 | 结果 |
| --- | --- |
| AWG | 4 个节点均返回 `warp=on` |
| WG | 4 个节点均返回 `warp=on` |
| MASQUE H3 | 两个节点均曾返回 `warp=on`；8443 在首轮触发一次 HTTPS 握手异常，同一 APK 随后连续两次通过 |

测试结束后已断开，系统没有活动的测试 VPN 接口。Gradle 构建和 6 项单元测试通过，Android Lint 无错误。

### 第二阶段实测（v0.2.0）

2026-11-25，在同一台 LG 上向 Cloudflare 实际注册 WG/AWG 共享身份及独立的 MASQUE 身份，保留原导入记录。选择 **本机注册** 而非导入来源，三种协议的重新优选均取得真实 WARP 出口：

| 协议 | 当次优选后连通的端点 | Cloudflare trace |
| --- | --- | --- |
| AWG | `8.35.211.113:854` | `warp=on` |
| WG | `8.39.214.182:854` | `warp=on` |
| MASQUE H3 | `162.159.199.2:8443` | `warp=on` |

MASQUE 注册兼容服务端实际返回的 PEM 对端公钥；修复后复用已有待完成记录继续 PATCH，未创建额外设备。端点表现以当时网络为准，并非固定推荐。11 项 JVM 单测和 2 项 Android 仪器测试通过，Android Lint 无错误；自动化注册用例使用模拟响应，不创建外部账户。

### 三档优选复测（v0.2.1）

2026-11-25，同一台 LG 复用上述已注册的身份，选择 **重新优选并连接**：

| 强度 / 协议 | 这轮测得的可用节点 | 全设备 VPN 复验 |
| --- | ---: | --- |
| 低 / AWG | 2（每个测 1 次） | `8.35.211.66:880`，`warp=on` |
| 中 / WG | 4（每个测 2 次） | `8.39.214.178:859`，`warp=on` |
| 高 / MASQUE | 4（每个测 3 次，扫描预算用尽） | `162.159.199.2:8095`，`warp=on` |

重启后沿用高档 MASQUE 缓存，升级前的 AWG 缓存也在中档直接命中，两者均在没有重新扫描的情况下再次验证通过。18 项 JVM 单测、4 项真机仪器测试通过，Android Lint 无错误。注册代理用真机上的本地测试代理验证了 HTTPS CONNECT 目标及凭据不随 CONNECT 头发送；直连失败后的重试判定使用模拟故障验证。本轮未重复注册，也没有更改手机的系统代理。

### 后台筛选与断点实测（v0.4.0）

LG LM-G710N 上，按 Home 后 AWG、WG、MASQUE 均取得了通过精筛的候选，筛选期间未启动系统 TUN。WG 低档的一轮用约 10.5 秒初筛 12 个、精筛通过 2 个；这只是当时 Wi-Fi 上的一次测量。另用 WG 中档验证强停恢复：第一批 8 个初筛结果落盘后强停应用并重新打开，日志从 `cursor=8` 开始，先消化已持久化的待精筛队列，4 个节点通过精筛。

### 主页与浅深色实测（v0.5.0）

LG 上分别切换了深色和浅色：WG 用已有缓存直接连接并返回 `warp=on`，未触发重新扫描；MASQUE 中档验证了 4 个节点，筛选期间没有系统 TUN。主页仪器测试使用模拟进度验证了注册和精筛时禁用开关及节点列表、完成后隐藏进度说明并显示“重新优选”。缓存回退单测覆盖了首项失败后连接次项，以及最多尝试五项。

## 后续参考与许可证

- 注册：[Oblivion](https://github.com/bepass-org/oblivion) 所用的 [Aether 注册实现](https://github.com/CluvexStudio/aether/blob/577a4c8dd18b08ddfdc3ad28a92e356d68cfa27c/aether/src/account.rs)。客户端请求使用系统 HTTPS；X25519 和 SEC1 编码使用 [Bouncy Castle](https://www.bouncycastle.org/)。
- 优选和设置：[trashvpn-windows](https://github.com/FuckGFWall/trashvpn-windows)。
- 源码及许可证：本项目使用 [GPL-3.0](LICENSE)。预编译桥接对应 [libmihomo-android v0.3.14 源码及构建说明](https://github.com/oviron/libmihomo-android/tree/v0.3.14)；内核对应 [Mihomo v1.19.34 源码](https://github.com/MetaCubeX/mihomo/tree/v1.19.34)。APK 中的桥接和内核均来自上述 AAR。
