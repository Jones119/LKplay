# HANDOFF · DiAuto-DiPlay 无线 CarPlay 施工交接

> 写给下一个接手工具/会话：**先读本文件，再动代码**。用户中文交流；关键结论写文档；不装第三方改包。
> 日期：2026-10-07（晚）｜工作区：`D:\Development\DiAuto-DiPlay`｜交接时任务：**完善无线连接 CarPlay**（已实机首亮，见下方「0bis」与 doc 27；剩音频/边界验证与代码精简）

> ### ⚠️ 2026-10-08 起：**请先读总交接文档** [`docs/_digest/39-完整交接（2026-10-08）.md`](docs/_digest/39-完整交接（2026-10-08）.md)
> 那一篇是**自包含**的（零上下文可读 ✓）：项目全貌、开发环境与命令、CS11 硬约束、开发历史时间线、
> 功能记分牌、测试基线、**下一步任务（含仪表投屏的接线配方）**、铁律与踩过的坑、文档地图。
> 本文件保留为更早的分批施工记录（部分结论已被 39 取代 ✓）。

## 0bis. 2026-10-07 深夜更新（**无线 CarPlay 已在 CS11 实机跑通**）

> 本节状态**新于**下文 §0~§9（§0~§9 保留为当时的施工图纸与背景，部分结论已过时）。

- **里程碑**：CS11 车机自身当 Wi-Fi AP，iPhone（14,7 / iOS 26.6.2）无线 CarPlay **上屏且触控可用**。实测 `first frame rendered` → `rx=33.9fps shown=16.2fps 3.7Mbps` → 调到 30fps 后 `rx≈shown≈30fps`、`recoveries=0`、触控 `touch2frame avg=78~105ms`。完整记录：`docs/_digest/27-无线CarPlay首亮（CS11）.md`（含机制、A/B 数据、待验证项）。
- **提交链**（分支 `cs11-api19`）：`4654121` 热点自动化 + API19 可达性修复 → `b43ecc8` AirPlay 数据端口绑「控制通道同址」（无线首亮的最后一块）→ `5bbd402` API19 默认 30fps → `c67f094` 触控上报合并（`TouchDispatchGate`）。基线 `d8611cd`。
- **三个卡点（务必记住）**：
  1. `ConnectivityManager.getActiveNetwork` 是 **API23**，4.4 缺失 → `runCatching` 不崩但 `isSuccess` 恒 false → 热点采样永远判「网络在变化」→ 无线永远起不来。**教训：被 `runCatching` 包住 ≠ 安全**；doc 10 把这类当"噪音"是错的。同类第二轴（`allNetworks` API21 → 上游集合不可观测）也已修。
  2. **iPhone 侧残留"有线时期"的 CarPlay 配对记录会阻断无线交接**（本次现象：`0x4300`/`0x4e0d` 都正常、我们已发 `0x4301`，但 iPhone 不弹提示、不连 7000 端口）。**上车调试无线前先让用户在 iPhone 上删掉旧的 CarPlay 记录**（设置 → 通用 → CarPlay）。
  3. `airplay/WildcardAddress.serverBindAddress()` 原为**有线写死**（只找 `usb1` 的 `fe80::2`，否则回落 `::`），而这个 ROM 拒绝无 scope 的 IPv6 绑定 → 无线下 NTP/事件/屏幕/音频全部绑失败、`SETUP` 500。已改为**绑「控制通道被接入的地址」**（有线仍 `fe80::2%usb1`，无线为 `192.168.43.1`）。
- **实测额外结论**：① 手抄车机热点口令不可靠——用户手填 10 位 `1891030057`，固件里其实是 11 位 `18910300575`；「自动采用车机热点配置」是必需的。② CS11 管线只能稳定产出 ~30fps，向 iPhone 请求 60fps 必然积压 → 丢帧 + 每 5 秒重建解码器 → 肉眼卡顿；`AirPlayPersistence.loadFps()` 已在 `SDK_INT<21` 默认 30。③ 触控 `touchSendMax`（写事件通道耗时）偶发 106~384ms，而 `touchSendMax`/`touch2frame` **都从"开始写"计时、不含排队**，排队积压看不见。
- **测试环境已修**：`shared/build.gradle` 把 Android 版 conscrypt 从**单元测试**类路径排除，`:shared` 的 Robolectric 用例终于能在本机跑（doc 26 §4 的阻塞已解除）。
- **已知既存失败（与本次改动无关，HEAD 之前即存在）**：`UsbPermissionSetupTest` 3 例（本机 MSYS `sh` 引号改写）、`CarPlayControllerUsbServiceTest` 4 例（测试模拟 USB 服务缺失，`compat.systemService()` 非空返回遇 null 抛 NPE）。
- **未竟**：① 无线音频（`type=102`）未实测——iOS 要等实际播放才开流；② 热点「关→开」未实测（本次热点是车机自己先开着的，`requestEnable` 只走了 `already up`）；③ 车机重启后自愈回归；④ 触控写阻塞（~200ms 级）与指标口径；⑤ **代码精简**（用户要求删 CS11 用不到的适配）：A CH341 + B I2C MFi + C BYD 全套 + D LocalOnlyHotspot + E MapEmbed，分批做、每批跑构建+单测。
- **环境提醒**：**车机必须处于点火状态才连得上 adb**（费油，长期不用 adb 时要提醒用户熄火）；车机重启后 `adb tcpip 5555` 会丢，只能走 USB。

## 0ter. 2026-10-07 深夜（第二轮：指标、精简、领克品牌、车机系统审计）

> 本节同样**新于** §0bis 与 §0~§9。提交链续接 0bis：`e2cb1af` → `65e500a`。

- **性能指标补全（`e2cb1af`）**：`shownP50/P95`（**上屏**间隔分位，用来区分"典型运动"与"突发"）、`dropped`（被 250ms 年龄规则拒绝上屏的帧）、`queueMax`（解码队列峰值）、`touch2frame`**改为从"手指状态被接受"起算（含排队）**＋新增 `touchQueueMax`（`TouchState(contacts, enqueuedNs)` 随状态穿过 `TouchDispatchGate` 到 `AirPlaySession`）。`VideoStats` 的日志出口改为可注入（宿主 JVM 单测断言）。新增 11 例测试全绿。
  - **踩坑（值得单独记）**：我自己的测试里写 `5_000 * 1_000_000`，Kotlin 按 **Int 相乘溢出**成 705,032,704 → "统计窗口未到" → 4 例假失败，排查花了很久。**生产代码同类写法已全仓审计：零命中**；测试改为按毫秒推进（`advanceMillis`）。
  - 环境事实：`:shared` 单测里的 `android.util.Log` **没有被 mock**（未开 `returnDefaultValues`），这是那 16 例既存失败中 13 例的根因；要修可开该开关（可选，未做）。
- **精简完成（两批）**：`2b971f1` 删 A(CH341)+B(I2C MFi)+D(LocalOnlyHotspot)+E(MapEmbed)（65 文件、净 −3845 行）；`65e500a` 删 **C(BYD/DiLink 全套)＋领克品牌**（206 文件、**−14091 行**）。
  - **范围扩张有依据**：整条「仪表簇投影显示」链路**以 BYD 为键**（`ClusterMapPresentation.findDisplay()` 只认 BYD 显示名+fingerprint，源码注释明确禁止选任意 display），**无通用回退** → 整批删除；只保留原代码已有的虚拟 1280×720 流 + 居中地图卡 + 启动器镜像。
  - **3 处必要改名**（通用件被 BYD 命名污染）：`BydHudRouteState`→`RouteGuidanceState`、`ClusterSongState` 从 `BydClusterSong.kt` 拆出、`BydAdbShell/BydParcel`→`AdbShell/AdbParcel`（受保护的 `WifiScanPause` 在用）。
  - **验收（我独立复核，非只信子代理）**：`:shared` **601 例 / 16 失败**、`:common` **534 例 / 6 失败**，**与 `c67f094` 干净副本上的既存基线逐条一致、零新增**；`:mobile:assembleStandaloneDebug` BUILD SUCCESSFUL；构建代码与资源零 BYD 残留（仅 4 处假阳性如 `configuredByDecoder`）。
  - **待你决策的尾料**：① UI 里 `MAP_WITH_CUSTOM_CARD`（在仪表簇地图上画 DiPlay 转向卡）随 `ClusterTurnCardView` 删除而**退化为纯地图**；② `setDashboardMapOutputVisible`/`DashboardMapEligibility`/`dashboardMapRoute()` 现**无生产调用方**（资格恒 false）；③ 已无引用的字符串资源仍在 7 语言 `strings.xml`；④ 一批无 BYD 残留但暂无引用的通用件（`ClusterTurnCardOverlay`、`ClusterSafeAreaPreviewFrame`、`VehicleSpeedLocationProvider`、`Iap2VehicleStatus` 等）。
- **CarPlay 里的图标与名字：BYD → 领克（已上机确认生效）**。定位：**不是** CarPlay 主题资产，而是 **AirPlay `/info` 的 OEM 图标广播** —— 图标字节 `common/src/main/res/raw/ic_car_home.png`（`CarPlayHostActivity.defaultAirPlayIconBytes()`，注释原文 "Shown in CarPlay's app list as the 'back to the car' button."），名字 `AirPlayPersistence.DEFAULT_OEM_LABEL` → `AirPlayInfoPlist` 的 `oemIconLabel`。改后：**黑底白字 LYNK&CO+领克** 图标 + 标签默认 **`领克`**，并对旧默认（`BYD`/`DiPlay`）做一次性迁移。素材溯源写入 `CarPlay-CS11/docs/THIRD_PARTY_NOTICES.md`；构建产物内图标 **SHA256 逐字节自证**。
- **车机系统级审计（不是 app 层）→ `docs/_digest/29-CS11车机系统级审计（点火实测）.md`**：
  - **头号发现**：`ssi@0202c000`（i.MX6 SSI2 / DIRANA 娱乐音频 I2S）**约 48,100 次/秒持续中断、100% 压 CPU0**（实测 cpu0 `irq 16.8% + softirq 6.5%`）。**根因=驱动/硬件级"每 48kHz 采样一次中断"**：音频流 `standby` 时照样 48k/s，强杀常驻采集后仍 48k/s → **用户态不可修，只能隔离**。
  - **常驻语音唤醒坐实**（`com.baidu.che.codriver` 持有 16kHz 单声道采集、约 11% CPU），但**不是**风暴成因；且该 ROM 的 `pm disable[-user]` 返回 rc=0 却不生效、进程强杀即重生（厂商保护）。
  - **已落地（可逆、易失）**：新脚本 `tools/cs11_wireless_tune.sh`（经 rootd 以 root 执行）＝ Wi-Fi SDIO IRQ 55 + USB 72/75 → **CPU1**、`wlan0` RPS 开启(2)、`netdev_max_backlog=4000`/`rmem_max=2MB`/`rmem_default=512KB`、eMMC `read_ahead_kb=512`、`ksoftirqd` renice −5、**app 绑 CPU2-3**（让 CPU0 独吞风暴）。设计＝**CPU0 风暴+杂项 / CPU1 网络 / CPU2-3 app**。
  - 另有**无需 root** 的全局 UX 项已生效：动画缩放三件套 → **0.5**（整个车机 UI 更跟手），可在设置里改回。
  - 已经很好、**不要动**：4 核 `governor=performance`(996MHz)、eMMC `noop`、`/data` 挂载 `noatime,nodiratime,noauto_da_alloc`、45 °C 无降频、`/data` 只用 1%、内存闲 539MB。
  - **root 链本轮重建过**：重启即失，重建＝`MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1 bash tools/cs11_root_fire.sh`（**不加这两个变量，MSYS 会把设备路径 `/data/...` 改写成 Windows 路径，push 全失败**）；成功标志＝rootd 进程在跑；随后 `adb push tools/cs11_wireless_tune.sh /data/.android/rootcmd.sh` 即执行，输出看 `/data/.android/rootout.txt`。
  - **APK 体积之谜（重要教训）**：归档基线 19.6MB 与本轮 13.45MB 的**条目未压缩合计只差 0.8MB**（正合删掉的 50 个 BYD 资源+代码），差出来的 6.15MB 全在 **ZIP 容器开销（31% vs 3%）**＝**增量打包残留的填充/陈旧字节**。→ **判断产物完整性要比条目内容（未压缩大小/哈希），不要比文件大小**；归档前 clean 打包一次。
- **本轮未竟（按优先级）**：① **无线音频**——`type=102` 流已证明**开起来且解密/收包无丢序**（`decrypted` 递增、`authFailures=0`、`seqForwardGaps=0`），但字节率仅 ~2 KB/s ＝**静音保活**，**"能听见声音"仍未被证明** → 播一次音乐看字节率是否升到 ~16 KB/s 并人耳确认；② **清晰度：已测并结案 —— 1080p 否决，保持 720p**（`wm size 1920x1080`+`density 240` 下更清晰但 `recoveries=1~2`、`maxGap=1.8s`、`shownP50` 33→50ms，而 `kbps` 仅 3.4~3.7M ⇒ 瓶颈不是带宽；已还原 `1280x720`/`160`；中间档 `1440x810`+`density 180` 未采数据，留作可选）。数据见 doc 27 §6bis；③ 热点「关→开」路径未实测；④ app 绑定在 app 重启后会丢（脚本需补跑一次，或做 root 侧钩子）；⑤ 车机重启自愈回归；⑥ Run B 的"尾料/语义"决策（`MAP_WITH_CUSTOM_CARD` 退化、`setDashboardMapOutputVisible` 等无调用方、无引用字符串、无 BYD 残留的通用尾料）；⑦ 可选：把 root 链+调优做成开机自启。

## 0quater. 2026-10-08 凌晨（第三轮：音乐、Opus、麦克风）

> 提交链续接 0ter：`d7e856b`（PCM 尝试，已回退）→ `86dac82`（回退）→ **`ec16265`（A2DP 释放 + 自带 Opus + 采集源阶梯）**。

- **「音乐点播放 1 秒就停」根因 = 车机主动暂停手机**（与我们的代码无关）：iPhone 同时以 A2DP 连着车机，CarPlay 生效时车机音源不是蓝牙 → `BTMusicPlaybackEngine` 的 `NotToPlay` 策略 → `remoteControl(RC_COMMAND_PAUSE)` → `reqAvrcpPause()` → iPhone 被暂停。**证据链、设备类型差异（盒子显示 CarPlay / 车机显示"汽车音响"）的完整分析见 `docs/_digest/27` §6sexies-A**。
- **修复 = `PhoneMediaAudioWatchdog`**：会话期间用**车机自己的适配 API** 释放手机的 A2DP，会话结束再连回。关键知识：**标准 `BluetoothA2dp` 代理在这台车机上不可用**（`getProfileProxy` 拿不到状态），必须走 `com.ecarx.xui.adaptapi.bt.Bt.create(Context)` → `getA2dp()` → **`reqA2dpDisconnect(地址)`**（签名由 `dexdump` 从 `/system/framework/ecarx.adapterapi.jar` 核实；该 jar 在 **BOOTCLASSPATH** 上，可直接 `Class.forName`）。实机日志 `reqA2dpDisconnect(9C:...)=true` → **音乐恢复正常（用户确认）**。
- **无线 CarPlay 的音频广播里必须有 Opus**（重要教训）：把麦克风输入/语音流改成 PCM-only 会让 iOS **完全不建音频流**（只建 130+110），声音留在手机上播。所以"去 Opus"不是解法；**解法是自带 Opus**。
- **自带 Opus（已落地）**：素材来自 `_analysis/easyplay/`（EasyPlay = DiPlay 的领克 fork，**包名与本项目一致**）→ `shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so`（armeabi-v7a，BSD-3，许可证 `docs/Opus-LICENSE.txt`，溯源已写入 `docs/THIRD_PARTY_NOTICES.md`）+ 新增 `shared/.../media/LegacyNativeOpus.kt`（**类名/方法名必须与 JNI 完全一致**）；`OpusEncoder` 在 MediaCodec 无 Opus 时自动回退。实机：`Opus microphone encoder started on libopus bitrate=64000` ✓
- **麦克风：链路是对的，车机上被"独占"**：
  - **平板（小米 Pad 6 Max 14 / Android 15）实测完全成功** —— Siri 听懂、并**用语音让高德开始了导航**；日志 `microphone probe source=6 result=1920`、`captureBytes=464896 reads=227 readErrors=0 encodedFrames=241 udpSent=241 sendErrors=0` ✓✓
  - **车机上**每种采集都在**第一帧就返回 `-3`（ERROR_INVALID_OPERATION）**，`captureBytes=0 readErrors=1` → 判为**车机麦克风被百度语音助手的常驻 16 kHz 采集独占**（用户也记得社区说法："要用 Siri 语音唤醒就得关掉百度语音助手"）。
  - **已加**：采集源回退阶梯（`VOICE_RECOGNITION → MIC → DEFAULT`，逐源探测并打印 `microphone probe source=X result=N`）——**下次上车第一件事就是看这行**：若有源可用即通；全被拒则需"让出麦克风"（车机设置关语音唤醒；或 root 下循环 `am force-stop com.baidu.che.codriver`，该进程会迅速重生，需实测）。
- **仍未闭环（离线可做）**：**通话/Siri 的下行也是 Opus**（车机日志 `codec=OPUS decoderUnavailablePacketsTotal=457`，即听不到对方/Siri）。`LegacyNativeOpus.Decoder` 已写好，**只差接进播放器**。

## 0. 30 秒版

- 项目：为领克03 CS11 车机（i.MX6Q / Android 4.4.3 / API19）自研 CarPlay 接收端。仓库 = `D:\Development\DiAuto-DiPlay\CarPlay-CS11`，分支 `cs11-api19`，HEAD `d8611cd`，**工作区有未提交的无线改动**。**有线 CarPlay 已完整上屏**（画面/音频/触控、零丢包调优完毕），已封存（恢复手册 `docs/_digest/25-有线连接资料封存与恢复手册.md`）。
- **当前焦点：无线 CarPlay 补全**。平板上（Android 15）无线已跑通；**CS11 上唯一缺口 = 热点自动化**（Android 4.4 隐藏 API 反射开热点）。
- 侦察全部完成（结论见 §2，施工图纸见 §3，核心文件草稿见 §4）。
- **§3 步骤 1~7 编码已完成、步骤 8 构建通过**（2026-10-07 晚）：APK `mobile-debug.apk` = 14,545,834 B / SHA-256 `F3478671…30D22C`；新增纯 JUnit 测试 12/12 绿。落地记录与偏差见 `docs/_digest/26-无线CarPlay热点自动化编码.md`。
- **剩余**：① CS11 上车验证（§5）；② 修复 shared 的 Robolectric 环境（conscrypt 类路径冲突，HEAD 既存，本机跑不了回归）；③ 改动尚未提交。
- 车机侧 root 链已重建完毕（rootd pid 3299、全能力、恢复出厂后重建手册已固化），但 **无线开发不需要 root**——无线走 WiFi AP + BT，只需 adb 装 APK。

## 1. 任务与背景

- 目标（跨阶段不变）：不换车机、不用 CarPlay 盒子、不信第三方重打包——纯软件直连。用户明确"我们的软件目标是不使用盒子，直连车机，使用carplay"。
- 无线原理链：车机当 Wi-Fi AP → iPhone 经 **蓝牙** 与车机做 iAP2 握手（0x5702 读 / 0x5703 下发 Wi-Fi 配置）→ iPhone 加入车机热点 → Bonjour 发现 → AirPlay 无线会话（我们 fork 已完整具备这条栈，平板 bench 实测 35fps/3.5Mbps 零丢包，见 doc 20）。
- 为什么缺"热点自动化"：CS11 的 MANUAL 模式要求"热点已由车机开启且用户把 SSID/口令填进 app"。4.4 上车机热点默认不开启、也没法用现代 API 开——所以必须补 4.4 反射路径（参考物 = 领克版 EasyPlay，**只作逆向参考不安装**，分析见 doc 24；本节及 §2-§4 即其机制落到我们 fork 的施工图）。

## 2. 无线栈现状（代码地图，行号以交接时磁盘为准）

### 2.1 编排入口 — `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`

- `startWirelessHotspot(generation)` ≈ **L2089**：
  - L2092-2112：自动开启前置块——`CarHotspotSettings.shouldEnable()`（要求 MANUAL 模式 + 开关开）→ `CarHotspotTethering.enable()`；非 READY 时抛 `WirelessStartupFailure.HOTSPOT_NOT_READY / HOTSPOT_CONFIGURATION`（`manualFallback` 例外：UNSUPPORTED 且状态不可读时放行）。
  - L2113-2118：MANUAL 且 `CarHotspotStatus.isEnabled()==false` → 抛 "The car hotspot is off…"。
  - L2119-2138：manager 工厂 —— `WIFI_P2P→WifiP2pGroupManager`、`LOCAL_ONLY_HOTSPOT→LocalOnlyHotspotManager`、`EXISTING_WIFI→ExistingWifiManager`、`MANUAL→ManualHotspotManager(ssid=config.manualHotspotSsid ?: throw, passphrase=config.manualHotspotPassphrase.orEmpty(), band/channel/security=config.*)`。
- 无线 BT 段 ≈ **L1215-1220**：`val adapter = bluetoothAdapter ?: throw; if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")` ← 拟加"尽力开启"。
- 之后：`attachWireless`（L1237）→ Bonjour 接口绑定（L1266）→ RFCOMM（L1299，UUID=`IAP2_IPHONE_UUID`）→ iAP2 控制（`Iap2WirelessControlClient`）。

### 2.2 热点后端（`shared/.../network/`）

| 文件 | 作用 | CS11(4.4) 现状 |
|---|---|---|
| `CarHotspotTethering.kt` | 车机热点自动开启：①反射 `IConnectivityManager.startTethering(4参)`（现代）；②adb 回退（`cmd connectivity start-tethering wifi`，需 loopback adb） | **两者都不可用**：4 参 startTethering 4.4 不存在；`cmd` 4.4 不存在 → `UNSUPPORTED` ← **本次要补的缺口** |
| `CarHotspotStatus.kt` | 读 AP 状态：反射 `getWifiApState`/`isWifiApEnabled` + sticky 广播 | ✅ 4.4 可用 |
| `CarHotspotAdbFallback.kt` | dumpsys 读状态 + 固件广告命令开热点（"never configures a replacement AP"） | 状态段可读；开热点命令段 4.4 无 |
| `CarHotspotSettings.kt` | 开关持久化（`auto_enable` = "自动开启车机热点"总闸）+ `visible(bydAvailable, access)` 门控（当前只对 BYD 放开） | 开关可存；**可见性被 BYD 门控挡住** ← 需放开 |
| `ManualHotspotManager.kt` | "附着到已在运行的热点"：读配置（`readLegacyApConfiguration` L288 = `getWifiApConfiguration` 反射）、读接口（`getTetheredIfaces`+`getTetherableWifiRegexs`）、算 channel、产出 `WirelessHotspotInfo` | ✅ 读路径全可用；**约束**：L75 实读 SSID ≠ 预期 SSID 即抛（→ 采用实读可解，见 §3 步骤 6） |
| `ManualHotspotInterfaces.kt` | legacy AP 接口枚举 | ✅ |
| `WirelessHotspotManager.kt` | 接口定义 + `WirelessHotspotBackend` 枚举（WIFI_P2P/LOCAL_ONLY_HOTSPOT/MANUAL_HOTSPOT/EXISTING_WIFI）+ 频道换算工具 | — |

### 2.3 UI（`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`）

- `bydAdbSettings()` ≈ **L912**：`if (!CarHotspotSetup.isBydHeadUnit(this)) return` ← CS11 开关被隐藏；内部 `CarHotspotSettings.visible(true, access)`（L930/L933）、`visible(true, access)`（L945）。
- `renderBydAdbControls()` ≈ **L941**：渲染"自动开启车机热点"toggle（strings: `auto_car_hotspot_title/description`）+ BYD 的 ADB 状态与权限按钮。
- `startCarHotspotOnLaunch()` / `startupHotspotEligible()` ≈ **L1042/L1060**：启动时自动准备热点；`CarHotspotSetup.shouldStartOnLaunch(ctx, hasSession)` 要求**已填凭据**（`ManualHotspotValidation.error(ssid, pass)==null`）← 拟加"无凭据也允许（legacy 自读）"。

### 2.4 权限（已齐，无需改 manifest）

`shared/src/main/AndroidManifest.xml`：L7-8 `CHANGE_WIFI_STATE`/`ACCESS_WIFI_STATE`（反射 `setWifiApEnabled` 只需 CHANGE_WIFI_STATE，安装即授予）；L15-22 `BLUETOOTH_CONNECT` + `BLUETOOTH(maxSdk30)` + `BLUETOOTH_ADMIN(maxSdk30)`（4.4 BT enable 可用）。`CarHotspotTethering.permitted()` 对 SDK<23 直接 true。

### 2.5 参考物（EasyPlay 反编译，机制精确口径）

`_analysis/easyplay/src/sources/com/shilapi/xcertplay/legacy/LegacyHotspotController.java`：

- SDK 门：`19 <= sdkInt < 30`；仅 19/20 走"Wi-Fi 客户端互斥"分支（`z = 19<=sdk<21`）。
- 读：`getWifiApConfiguration()` → `storedConfiguration(config)`（L1022）：**WEP 优先**（`wepKeys` 任一非空）；否则 `allowedKeyManagement.get(1)||get(4)`（WPA_PSK/WPA2_PSK）→ WPA_PSK；空 或 {0} → OPEN；其他 → UNSUPPORTED。
- 可用性（`usableConfiguration` L870）：SSID 非空、无 NUL、UTF-8 ≤32 字节；OPEN 直接可用（空口令）；WPA_PSK 口令须 **8..63 可打印 ASCII**（code 32..126）或 **64 位 hex（裸 PSK）**；WEP/UNSUPPORTED → 不可用。
- 开：`setWifiApEnabled(WifiConfiguration, boolean)` 反射（L1117）= `WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, Boolean.TYPE).invoke(wifi, config, true)`；返回 Boolean==true 才算受理。命中已存配置时**原样复用同一个 WifiConfiguration 对象**；重建路径用 `allowedKeyManagement.set(4)`（WPA2_PSK）+ `preSharedKey`（不要用 set(1)）。
- 4.4 前置：AP 与 Wi-Fi 客户端互斥——若 `getWifiState()` 为 ENABLED/ENABLING → `setWifiEnabled(false)` 并等待落定；**失败路径恢复** `setWifiEnabled(true)`（其 z2 语义；恢复前注意若 AP 已 ENABLED/ENABLING 则不要再开客户端）。
- 轮询：250ms；状态映射 10 DISABLING/11 DISABLED/12 ENABLING/13 ENABLED/14 FAILED；超时文案 "热点开启超时…"。
- 兜底建配置（我们 v1 **不做**，留 v2）：随机 `DiPlay-Lynk-XXXX` + 16 位口令 WPA2。我们的等价兜底方案 = 用户设置里的 `manualHotspotSsid/passphrase`。
- BT：`BluetoothAdapter.enable()` 直调（BLUETOOTH_ADMIN）。

## 3. 施工计划（精确到文件；建议按序执行）

> 设计原则：**只沿用原车热点配置**（读出的对象原样交回 `setWifiApEnabled(cfg,true)`，不新建/不改写）；**受理 ≠ 就绪**（仍由既有轮询确认 13=ENABLED）；失败恢复被关的 Wi-Fi 客户端；legacy 链只在 `supported()` 时启用，现代设备行为零变化。

1. **新建** `shared/src/main/java/com/shilapi/xcertplay/network/LegacySoftApControl.kt`（草稿见 §4）。注意：**必须是 `object` 且 public**（`common` 模块的 DiPlayActivity 要用）；反射读 `preSharedKey`/`wepKeys`（compileSdk=37 下这两个字段有移除风险；`SSID`/`allowedKeyManagement` 直读已被本仓库使用，无风险）。
2. **改** `CarHotspotTethering.kt` 的 `enable(context, …)`：fallback 链改为 `{ startLegacy() || startAdb() }`（**legacy 先、adb 后**：4.4 无 `cmd`、legacy 无需 shell；BYD 场景 startTethering 成功时根本不进 fallback，无回归面）。末尾：`if (result != Result.READY) LegacySoftApControl.restoreWifiClientIfNeeded(context, log)`。**不要动** `enableUntil` 与 internal 重载签名（既有测试靠它们注入 startFallback）。
3. **改** `CarHotspotSettings.kt` `visible()`：加第三参 `legacySupported: Boolean = false`，返回 `legacySupported || (bydAvailable && (access==READY || access==NOT_APPROVED))`。默认值保证既有测试（`CarHotspotSetupTest`）不破。
4. **改** `common/.../DiPlayActivity.kt`：
   - `bydAdbSettings()`：`val byd = CarHotspotSetup.isBydHeadUnit(this); val legacy = LegacySoftApControl.supported(); if (!byd && !legacy) return`；thread 内 visible 改 `CarHotspotSettings.visible(byd, access, legacy)`；`renderBydAdbControls(controls, access, byd, legacy)`——`!byd` 时隐藏 ADB 状态标签与"自动应用权限"按钮（保留热点开关卡片本身）。
   - `startupHotspotEligible()`：改调三参重载（下述）。
5. **改** `shared/.../CarHotspotSetup.kt` `shouldStartOnLaunch`：**加重载、保留原两参行为**：
   `fun shouldStartOnLaunch(context, hasSession, allowCredentiallessStart: Boolean = false)`，其中凭据校验改为 `allowCredentiallessStart || ManualHotspotValidation.error(...)==null`；DiPlayActivity 传 `LegacySoftApControl.supported()`。
6. **改** `CarPlayController.kt`：
   - BT（L1215-1220）：`if (!adapter.isEnabled && !requestWirelessBluetoothOn(adapter)) throw IOException("Bluetooth is not enabled")`；新私有函数 = `adapter.enable()`（`@Suppress("DEPRECATION")` + runCatching）+ ≤6s 轮询 `isEnabled`（250ms，可中断）。
   - 采用实读配置：在 L2119 `val manager = when (hotspotMode)` **之前**计算：
     ```kotlin
     val adoptedManual = if (hotspotMode == WirelessHotspotMode.MANUAL) {
         com.shilapi.xcertplay.network.LegacySoftApControl.readUsableStored(appContext)?.also { adopted ->
             debugLog("generation=$generation manual hotspot adopts live AP configuration " +
                 "ssid='${adopted.ssid}' security=${adopted.security}")
         }
     } else null
     ```
     MANUAL 分支改用：
     ```kotlin
     ssid = adoptedManual?.ssid ?: config.manualHotspotSsid
         ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
     passphrase = if (adoptedManual != null) adoptedManual.password.orEmpty() else config.manualHotspotPassphrase.orEmpty(),
     // security = adopted: OPEN→ManualHotspotSecurity.OPEN，WPA_PSK→WPA2；adopted==null→config.manualHotspotSecurity
     ```
     ⚠️ `passphrase` 别写成 `adoptedManual?.password ?: config.…`——OPEN 时 password=null 会被错误回落成旧密码（ManualHotspotManager 对 OPEN 要求口令为空）。
7. **测试**：新增 `shared/src/test/java/com/shilapi/xcertplay/network/LegacySoftApControlTest.kt`（纯函数：`storedSecurity` 映射、`usable` 规则；plain JUnit 即可，见 §5）。回归跑：`CarHotspotTetheringTest`、`CarHotspotSetupTest` 必须全绿（默认参数/重载设计就是为了不动它们）。
8. **构建 + 上车验证**（§5 命令）。

## 4. `LegacySoftApControl.kt` 草稿（按此写，可微调）

```kotlin
package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.compat.systemService
import java.util.BitSet

/**
 * CS11（Android 4.4）车机热点自动开启——对齐 EasyPlay（领克版）反编译口径（LegacyHotspotController）。
 * 只沿用原车热点配置；4.4/4.4W AP 与 Wi-Fi 客户端互斥（开前关客户端、失败恢复）；
 * 反射仅需 CHANGE_WIFI_STATE（安装即授予），无 root、无 shell；受理后仍须轮询 getWifiApState 至 13。
 */
object LegacySoftApControl {
    private const val TAG = "DiPlay-LegacyAp"
    private const val AP_STATE_ENABLING = 12
    private const val AP_STATE_ENABLED = 13
    private const val WIFI_STATE_DISABLING = 0
    private const val WIFI_STATE_DISABLED = 1
    private const val WIFI_STATE_ENABLING = 2
    private const val WIFI_STATE_ENABLED = 3
    private const val CLIENT_SWITCH_TIMEOUT_NANOS = 6_000_000_000L
    private const val POLL_MILLIS = 250L

    enum class StoredSecurity { OPEN, WPA_PSK, WEP, UNSUPPORTED }
    data class StoredApConfiguration(val ssid: String?, val password: String?, val security: StoredSecurity)
    data class UsableApConfiguration(val ssid: String, val password: String?, val security: StoredSecurity)

    /** 平台是否具备 4.4 系 setWifiApEnabled 反射面（现代设备 false，不影响既有回退链）。 */
    fun supported(): Boolean = runCatching { setWifiApEnabledMethod() }.isSuccess

    private fun setWifiApEnabledMethod() = WifiManager::class.java.getMethod(
        "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
    )

    // ---- 读取 ----
    fun readStoredConfiguration(context: Context): StoredApConfiguration? =
        context.systemService(WifiManager::class.java)?.let(::readStored)

    private fun readStored(wifi: WifiManager): StoredApConfiguration? {
        val raw = runCatching {
            WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        }.getOrNull() ?: return null
        return storedConfiguration(raw)
    }

    /** 读取且通过可用性校验的原车配置（供 MANUAL 流程采用真实 SSID/口令/安全类型）。 */
    fun readUsableStored(context: Context): UsableApConfiguration? =
        usable(readStoredConfiguration(context))

    internal fun storedConfiguration(config: WifiConfiguration): StoredApConfiguration =
        StoredApConfiguration(
            ssid = unquote(config.SSID),
            password = preSharedKey(config),
            security = storedSecurity(config.allowedKeyManagement, hasWepKey(config)),
        )

    // preSharedKey/wepKeys 在较新 SDK 有被移除风险：走反射最稳（SSID/allowedKeyManagement 直读已被本仓库使用）
    @SuppressLint("PrivateApi")
    private fun preSharedKey(config: WifiConfiguration): String? = runCatching {
        WifiConfiguration::class.java.getField("preSharedKey").get(config) as? String
    }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun hasWepKey(config: WifiConfiguration): Boolean = runCatching {
        (WifiConfiguration::class.java.getField("wepKeys").get(config) as? Array<*>)
            ?.any { it is String && it.isNotEmpty() } == true
    }.getOrDefault(false)

    internal fun storedSecurity(keyManagement: BitSet?, hasWepKey: Boolean): StoredSecurity {
        if (hasWepKey) return StoredSecurity.WEP
        val km = keyManagement ?: return StoredSecurity.UNSUPPORTED
        return when {
            km.get(1) || km.get(4) -> StoredSecurity.WPA_PSK
            km.isEmpty || (km.cardinality() == 1 && km.get(0)) -> StoredSecurity.OPEN
            else -> StoredSecurity.UNSUPPORTED
        }
    }

    internal fun usable(stored: StoredApConfiguration?): UsableApConfiguration? {
        val ssid = stored?.ssid ?: return null
        if (ssid.isBlank() || '\u0000' in ssid || ssid.encodeToByteArray().size > 32) return null
        return when (stored.security) {
            StoredSecurity.OPEN -> UsableApConfiguration(ssid, null, StoredSecurity.OPEN)
            StoredSecurity.WPA_PSK -> {
                val password = stored.password ?: return null
                val passphrase = password.length in 8..63 && password.all { it.code in 32..126 }
                val rawPsk = password.length == 64 &&
                    password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                if (passphrase || rawPsk) UsableApConfiguration(ssid, password, StoredSecurity.WPA_PSK)
                else null
            }
            StoredSecurity.WEP, StoredSecurity.UNSUPPORTED -> null
        }
    }

    // ---- 开启 ----
    @Volatile private var clientDisabledByUs = false

    /** true = 平台已受理；调用方仍须轮询状态确认。失败路径自动恢复被关的 Wi-Fi 客户端。 */
    fun requestEnable(context: Context, log: (String) -> Unit = { Log.i(TAG, it) }): Boolean {
        val wifi = context.systemService(WifiManager::class.java)
        if (wifi == null) { log("legacy AP: WifiManager unavailable"); return false }
        if (!supported()) { log("legacy AP: setWifiApEnabled unavailable"); return false }
        return try {
            requestEnable(context, wifi, log)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            restoreWifiClientIfNeeded(context, log)
            false
        } catch (error: Throwable) {
            log("legacy AP: enable failed ${error.javaClass.simpleName}: ${error.message}")
            restoreWifiClientIfNeeded(context, log)
            false
        }
    }

    private fun requestEnable(context: Context, wifi: WifiManager, log: (String) -> Unit): Boolean {
        val initial = apState(wifi)
        if (initial == AP_STATE_ENABLED || initial == AP_STATE_ENABLING) {
            log("legacy AP: already up (state=$initial)")
            return true
        }
        if (Build.VERSION.SDK_INT in 19..20) {
            val clientState = wifiState(wifi)
            if (clientState == WIFI_STATE_ENABLED || clientState == WIFI_STATE_ENABLING) {
                log("legacy AP: switching the Wi-Fi client off (softAP needs the radio on 4.4)")
                clientDisabledByUs = true
                if (runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false) != true) {
                    log("legacy AP: the platform refused to switch Wi-Fi off")
                    clientDisabledByUs = false
                    return false
                }
                val deadline = System.nanoTime() + CLIENT_SWITCH_TIMEOUT_NANOS
                while (System.nanoTime() < deadline) {
                    val state = wifiState(wifi)
                    if (state != WIFI_STATE_DISABLING && state != WIFI_STATE_ENABLING &&
                        state != WIFI_STATE_ENABLED
                    ) break
                    Thread.sleep(POLL_MILLIS)
                }
                log("legacy AP: Wi-Fi client state now ${wifiState(wifi)} (1=disabled)")
            }
        }
        val raw = runCatching {
            WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        }.getOrNull()
        val usableConfiguration = usable(raw?.let(::storedConfiguration))
        if (raw == null || usableConfiguration == null) {
            log("legacy AP: saved hotspot configuration missing or unsupported; leaving it to the car settings")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        val accepted = runCatching {
            setWifiApEnabledMethod().invoke(wifi, raw, true) as? Boolean
        }.getOrElse { error ->
            log("legacy AP: setWifiApEnabled rejected ${error.javaClass.simpleName}: ${error.message}")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        if (accepted != true) {
            log("legacy AP: platform declined to start the hotspot")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        log("legacy AP: started saved hotspot ssid='${usableConfiguration.ssid}' security=${usableConfiguration.security}")
        return true
    }

    /** 开启最终未成功时恢复被我们关掉的 Wi-Fi 客户端（AP 已起则保持现状）。 */
    fun restoreWifiClientIfNeeded(context: Context, log: (String) -> Unit = { Log.i(TAG, it) }) {
        if (!clientDisabledByUs) return
        clientDisabledByUs = false
        val wifi = context.systemService(WifiManager::class.java) ?: return
        val state = apState(wifi)
        if (state == AP_STATE_ENABLED || state == AP_STATE_ENABLING) {
            log("legacy AP: hotspot is up; keeping the Wi-Fi client off")
            return
        }
        val restored = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        log("legacy AP: Wi-Fi client restore requested success=$restored")
    }

    fun apState(context: Context): Int? = context.systemService(WifiManager::class.java)?.let(::apState)

    private fun apState(wifi: WifiManager): Int? = runCatching {
        WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int
    }.getOrNull()

    private fun wifiState(wifi: WifiManager): Int = runCatching { wifi.wifiState }.getOrDefault(-1)

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"')
            value.substring(1, value.length - 1) else value
    }
}
```

## 5. 构建 / 测试 / 验证

- 构建（git-bash；本机 `JAVA_HOME` 已指向 `D:\Dev Environment\Java\jdk-21.0.12+8`；**环境变量路径也要用 `D:/` 形式**——本环境不做 MSYS 路径转换）：
  ```
  cd D:/Development/DiAuto-DiPlay/CarPlay-CS11
  DIPLAY_AUTH_ASSETS_DIR="D:/Development/DiAuto-DiPlay/_artifacts/apk_assets/assets" ./gradlew :mobile:assembleStandaloneDebug
  ```
  输出：`mobile/build/outputs/apk/debug/mobile-debug.apk`（`assembleStandaloneDebug` = 身份资产校验 + assembleDebug；该 APK 即含 MFi 身份的装机包）。
- 单测（分模块跑，`--tests` 过滤对每个 task 独立生效）：
  ```
  ./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.network.LegacySoftApControlTest" \
                                      --tests "com.shilapi.xcertplay.network.CarHotspotTetheringTest"
  ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarHotspotSetupTest"
  ```
  新测试用例（纯函数，plain JUnit）：`storedSecurity` 映射（WEP 优先 / {1}{4}→PSK / 空{0}→OPEN / 其余→UNSUPPORTED）；`usable` 规则（名字非空无 NUL ≤32B；OPEN 无口令；PSK 8..63 可打印 ASCII 或 64-hex 通过；"short"/64 非 hex/含非 ASCII/含 NUL → null）。
- 车机验证（adb serial `0123456789ABCDEF`；USB 调试口）：
  1. `adb -s 0123456789ABCDEF install -g -r mobile/build/outputs/apk/debug/mobile-debug.apk`
  2. app 里：无线 → MANUAL 模式；打开"自动开启车机热点"（本次改动后 CS11 可见）
  3. 观察 logcat（filter：`DiPlay-LegacyAp` / `DiPlay-Hotspot` / `xcertplay`），期待链：
     `legacy AP: started saved hotspot ssid=…` → AP 状态 13（`CarHotspotStatus`）→ `manual hotspot adopts live AP configuration …` → RFCOMM → 0x5703 → iPhone 入热点 → 首帧
  4. iPhone 先与车机蓝牙配对（无感连接优先）。
- 已知待观察点：4.4 读不到 channel → 0x5703 报 channel 0（auto，属正常）；若 CS11 保存的热点配置不可用（读出 null / WEP）→ v1 明确失败并留日志，**v2 再实现"用户手动凭据兜底建配置"**（等价 EasyPlay `newConfiguration()`，它用 `DiPlay-Lynk-XXXX`）。
- 平板回归（可选）：A15 上 `supported()==false` → 行为与现状完全一致，无回归面。

## 6. 车机侧与有线封存（另一条线，已冻结）

- root 链：恢复出厂后**已重建**（`/data/.android/{mp.sh, rootd.sh, trig}` + `/data/local/tmp/{root_cmd.sh, fake/pppd}`；`rootd` pid 3299、CapEff 全 37 位）。**`trig` 必须是二进制魔数**（如 `DE AD BE EF 00…`）——纯文本会被内核"首 4 字节全可打印则不请求 modprobe"检查静默拦下。
- `adb tcpip 5555`：**每次车机重启后需重开**（adb-ec 链路前提；一键脚本已含此步）。
- 有线恢复手册（含一键点火 `tools/cs11_root_fire.sh`、三坑修正、未竟四项 To-Do）：`docs/_digest/25-有线连接资料封存与恢复手册.md`。
- 归档 APK（无线改造前基线）：`_artifacts/cs11_apk/mobile-debug-d8611cd-20261007.apk`，SHA-256 `38a78d00591782c3535baad5b597119728702c3d4f37fc735bbbb2304ae79726`。
- 有线封存后未竟事项（勿丢）：① hot-replug 黑屏修复（参考 EasyPlay v33/34"重建窗口先显示连接页→等首帧→全屏"，反编译在 `_analysis/easyplay/src`）；② lossprobe A/B/C 裁定（`tools/cs11_lossprobe.sh` + `lossprobe_summarize.py`）；③ 方向盘媒体键实机验证（提交 `d8611cd`）；④ 帧池等优化。

## 7. 关键坐标速查

- 仓库：`D:\Development\DiAuto-DiPlay\CarPlay-CS11` @ `cs11-api19`；提交链 `5b0f577`（上屏）→ `dd79bcd`（零丢包）→ `d8611cd`（媒体键，HEAD）。
- 反编译参考：`_analysis/easyplay/src/sources/com/shilapi/xcertplay/legacy/LegacyHotspotController.java`（L1117 反射开热点；L870 可用性；L1022 配置映射；L963 兜底建配置；L539/L592 客户端互斥与恢复；轮询 250ms）。
- 文档总索引：`docs/INDEX.md`；最相关：20（平板无线首亮）、23（有线协议对照）、24（EasyPlay 逆向+无线对齐计划）、25（有线封存与恢复手册）。
- 既有测试（别破坏）：`shared/src/test/.../network/CarHotspotTetheringTest.kt`（startFallback 注入的语义矩阵）、`common/src/test/.../CarHotspotSetupTest.kt`（visible/shouldStartOnLaunch 语义）。

## 8. 红线与用户偏好

- 中文交流；命令/路径英文。**关键结论必须写进 `docs/`**（工作区 `docs/` = 报告平铺 + `INDEX.md` 总索引；新增报告编号顺延）。
- 不安装/运行第三方改版 APK（EasyPlay 只作逆向参考）；不换车机、不用 CarPlay 盒子；纯软件直连。
- 环境内不留任何密钥/凭据明文；DiPlay 系 APK 不盲装（官方签名指纹 `CN=DiPlay`，SHA-256 `87b38b12…`）。
- 用户取向：0 丢包、不做音频修补；UI 细节遵循既有实现。

## 9. 给下一个工具的第一句话（可直接粘贴）

> 你在继续 DiAuto-DiPlay 项目（工作区 `D:\Development\DiAuto-DiPlay`）。请先读根目录 `HANDOFF.md`（无线 CarPlay 施工交接），然后按其 §3 从步骤 1 开始编码（新建 `CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/network/LegacySoftApControl.kt`，完整草稿在 §4），逐步完成 §3 的 8 步并在每一步后按 §5 构建/测试。用户要求：中文交流、关键结论写入 `docs/`。车机 adb serial=`0123456789ABCDEF`。
