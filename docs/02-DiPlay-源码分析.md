# 02 · DiPlay 源码分析报告

> 仓库：<https://github.com/shihabal3amri/DiPlay>（Public，GPL-3.0）
> 本地克隆：`D:\Development\DiAuto-DiPlay\DiPlay` · main @ `e6477e1` 附近（2026-10-06 抓取，含 16 个远端分支）
> 分析日期：2026-10-06 · 克隆方式：gh-proxy 镜像（完整历史）

---

## 1. 一句话概括

DiPlay 是装在 **BYD 安卓车机**上的 **CarPlay 接收端**（有线 USB + 无线）：iPhone 不需要越狱/装 App，车机侧完整自实现了 **iAP2 + AirPlay + MFi 认证 + iOS Lockdown 配对 + USB NCM** 这一整条 Apple 私有协议栈；发布版 APK 内打包了一份**从公开 Carlinkit 固件提取的 Apple MFi 配件身份**（证书 + P-256 私钥）来完成"配件认证"，并对 BYD DiLink 3/4/5/5.1 的仪表/集群做了深度适配。

## 2. 基本信息

| 项 | 值 |
| --- | --- |
| 仓库 | `shihabal3amri/DiPlay`（2026-09-24 创建） |
| Stars / Forks | 约 4.8k / 598 |
| 许可 | **GPL-3.0**（上游 xcertplay）；`DiPlayActivity.kt` 标注 AGPL-3.0-only（界面适配自 DiAuto）；BYD HUD 图标为 PolyForm NC |
| 语言 / 构建 | Kotlin + Jetpack Compose；JDK 25、Android SDK 37、NDK `28.2.13676358` |
| 应用目标 | ① `mobile`（DiPlay 本体）：`com.shihab.diplay` v0.2.12 (vc31)，minSdk 28 / target 37；② `automotive`（AAOS，上游目标）：`com.shilapi.xcertplay` v1.2.1 |
| 提交 / 分支 | 507 commits；16 个远端分支（release/*、fix/*、merge/reviewed-* 等规范化命名） |
| 贡献者 | Shihab Al Amri 系（~300+ commits）、georgiyrr 59、pengchenghui 11、lpcheng1208 10、Alex Loaiza 10、Saeed Safrini 9、baileileicn、Dlochon、jaxie、bandwa、寒叙 等 |
| CI | `android.yml`（先跑 `scripts/check_public_tree.py` 防凭据泄漏，再单测+lint+源码构建）；`build-android-tv.yml`；`pages.yml` |
| 发布 | 9 个 release（v0.1.0 → v0.2.12，2026-09-25 起）；v0.2.12 APK 37.2MB / **下载 54,580 次**；附 source zip 与 SHA256SUMS |

## 3. 功能与定位

- 车机端 CarPlay：**USB（NCM 网络 + iAP2）** 与**无线**（Wi-Fi Direct / 内置热点 / 车机热点自动启动 / 现有 Wi-Fi·同一局域网）四种连接形态；"核心连接不要求 ADB"。
- BYD 仪表/集群集成：DiLink 3（GCC 汉，AMap 适配）、DiLink 4（XDJA 1920×720 集群）、DiLink 5/5.1（主题/悬浮地图/歌曲、通话、转向卡片）——大部分可选功能需要"已授权的网络 ADB"。
- 显示与操控：30–160% 自定义分辨率、实时画面调节（亮度/对比/饱和度/色温）、昼夜外观（系统/光线传感器/固定）、多指手势设置、方向盘按键映射、旋钮/摇杆模拟。
- 虚拟集群（16:9 地图流）、桌面小组件/悬浮地图/启动器卡片（`MapEmbedService` + `NavigationWidget`）。
- 多语言界面与站点（EN/简中/阿/俄/乌/西）；诊断报告手动导出（不自动上传）。
- **明确自我声明**：未经 Apple 认证（"公开预览"）、身份为实验中提取物、未来 iOS 是否继续接受未确定。

## 4. 技术架构

### 4.1 模块与代码规模

| 模块 | 说明 |
| --- | --- |
| `shared/` | 协议库（~436 个文件）。包：`airplay`（≈6,249 行）、`transport`（7,776）、`network`（6,125）、`hud`（4,367）、`orchestration`（3,157）、`media`（2,831）、`iap2`（2,459）、`mfi`（1,094）、`adb`（321）、`glance/location/shared` 等。最大单文件 `CarPlayController.kt` 2,636 行 |
| `common/` | 宿主 UI/设置（`CarPlayHostActivity.kt` 单文件约 224KB；`DiPlayActivity.kt` 为 AGPL 标注的 DiAuto 风格界面） |
| `mobile/` | DiPlay 应用壳（清单、资源、debug HUD 测试件；含身份注入与凭据门控构建逻辑） |
| `automotive/` | Android Automotive OS 目标（`androidx.car.app`） |
| `samples/` | `maphost`（地图嵌入宿主示例）、`home`（桌面卡片示例） |

### 4.2 协议栈总览（从 USB/网络到 UI）

```
iPhone ── USB(NCM 以太网/USBMUX) 或 Wi-Fi(P2P/热点/LAN) ──┐
   ├─ iAP2 (CSM 帧/链路/端点目录, shared/iap2 + transport/Iap2LinkEngine.kt 722 行)
   ├─ AirPlay (RTSP 控制 + 视频流 + 音频流, airplay/AirPlaySession.kt 1,010 行)
   │    · /info 广告: CarPlay features 0x615653aee2 / audio 0x10004540a00 / 双屏(110/111)
   │    · 加密: Ed25519 + X25519 + ChaCha20-Poly1305 (BouncyCastle 1.79); SRP-6a 配对 (PairSetup/PairVerify, Tlv8Codec)
   ├─ MFi 认证 (/auth-setup 的 MfiSapAuthSetup: 交换证书 + 挑战签名)
   ├─ iOS Lockdown 配对 (LockdownPairRecord.kt 433 行: HostID/SystemBUID/证书体系)
   └─ 传输 (NcmUsbBridge.kt: NTB16 以太帧; TlsDuplexChannel; Iap2UsbMuxHost; 无线侧 CarPlayVpnService)
UI: CarPlayHostActivity/CarPlayController ── 解码/渲染(Media3 ExoPlayer + MediaCodec) ── BYD 集群输出
```

- **无线路由**：`WifiP2pGroupManager`（938 行）、`LocalOnlyHotspotManager`（830）、`ManualHotspotManager`、`CarHotspotTethering`（经 WRITE_SETTINGS 启动车机已有热点，默认关）、`ExistingWifiManager`（同一局域网模式）；发现层 `CarPlayBonjour.kt`（jmDNS + NsdManager，按 IPv4/IPv6 解析并 TCP 预探）。
- **VPN 技巧**：`CarPlayVpnService`（`BIND_VPN_SERVICE`）持有 AirPlay TCP 监听，并把 **USB NCM 的 IPv6 桥接**纳入自己的 VPN 通道（本地链路，不提供外网 VPN）。
- **视频**：主屏视频曾用 VideoView/MediaPlayer 被车机 `media.extractor`（`libmmparser_lite.so`）解析崩溃，后改为 **Media3 ExoPlayer** 在 App 内解析；仪表流经 `AndroidMediaSink.kt`（1,513 行）独立管线。
- **音频**：多声道映射（voice/nav/media）、`MicrophoneUplink` + `OpusEncoder`（Siri/通话上行）、`BufferedAudioStream`（可选缓冲音乐播放，默认关）。

### 4.3 MFi 认证的三条路径（`shared/mfi/`）

| 路径 | 实现 | 说明 |
| --- | --- | --- |
| ① 硬件芯片（I2C） | `MfiAuthenticationClient` + `MfiDeviceScanner` + `I2cTransport` | 直接对板载 `/dev/i2c-N` 或 **CH341 USB-I2C 转接板**（上游 xcertplay 的配套硬件）探测 MFi 协处理器，按 LIVI 寄存器时序读证书、做挑战签名 |
| ② 离线身份（软件） | `LocalMfiAuthenticationClient`（`offline-mfi/identity.pk8 + certificate.p7b`） | 用 P-256 私钥做 `NONEwithECDSA` 签名（iAP2 0xAA00/0xAA02 流）；**发布 APK 打包的就是这条路** |
| ③ 远程服务 | `RemoteMfiAuthenticationClient` | HTTP(S) 接口 `GET /mfi/certificate`、`POST /mfi/sign`、`POST /mfi/reset`（可配 URL + Bearer Token）；把签名能力委托给网络另一端。上游文档注明"仅 BAA 认证实测过" |

> 另支持 BAA 证书类型（leaf+intermediate 链）。

### 4.4 本地 ADB 与 `app_process`（权限技巧，`shared/adb` + `hud`）

- `LocalAdb`：**自带 ADB 密钥的纯 Java ADB 客户端**，连接车机自己的 adbd（`127.0.0.1:5555`，"ADB over network"）。首次需车主在"允许调试？"对话框里批准一次密钥；**后台路径永不主动弹窗**（注释：行驶中不能弹窗），被拒则每 30 秒重试（`BydAdbShell`）。
- 用途：切换 BYD 仪表导航模式、热点启动兜底（0.2.12 的 #235：固件暴露命令时经"已授权 ADB"启动车机热点并**观测真实状态**）、Android 10 上暂停 Wi-Fi 扫描（#225，缓解一类卡顿）。
- `CLASSPATH=$apk app_process /system/bin <ToolClass>`：把 App 内的工具类（`BydCarPlayCallTool`、`BydClusterSongTool`、`Byd13CatalogProbeMain`、`HotspotJoinRepairMain` 等）以 **shell 用户**身份在独立进程里执行——典型"借 adbd shell 跑特权代码"手法。

### 4.5 BYD 集成面（`shared/hud/`）

- 组件引用一览（代码中出现的原厂实体）：`com.byd.clusterdebug.BroadcastReceiverCAN`、`com.byd.automap.meter.MeterActivity / MeterSmallScreenActivity`（DiLink3 仪表地图）、`com.byd.cluster.SimpleClusterDynastyActivity`、`com.byd.sr.cluster.ClusterActivity`、`com.byd.launchermap`、`com.byd.carplay.ui`、`com.byd.amapservice`、`com.example.amapservice`（DiLink3 上的 AMap 适配包，包名就叫 com.example）、`com.ts.car.someip.service` 等。
- 功能：仪表地图投送（含"先发全屏 16、再发半屏 17"的 DiLink3 兼容顺序修复）、通话/歌曲/歌词 HUD、电池/车况字段探测（只读、opt-in）、转向卡片（自定义 30–95% 尺寸/透明度）、原厂地图"暂停/恢复"（先记录原状态再改动，失败可回滚）。
- **Usage Access**（`PACKAGE_USAGE_STATS`）：仅用于 DiLink 5.1 "主题/小卡可见性"判定——过滤到 4 个原厂 Activity 本地处理，日志只记推断结果（隐私文档有说明）。
- 强杀进程会影响原厂仪表/Wi-Fi 恢复——文档提示"再次打开 App 后完成恢复"。

## 5. 身份 / 凭据专题（重点）

### 5.1 发布 APK 内打包的 MFi 身份（已实测提取）

| 事实 | 值 |
| --- | --- |
| `assets/offline-mfi/certificate.p7b` | 607 B，SHA-256 `634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e` |
| 证书主体 | `C=US, O=Apple Inc., OU=Apple Accessories, CN=IPA_24ACBB48646F8077F7463523085B60D2`——**Apple 签发的 MFi 配件证书** |
| 签发者 | `C=US, O=Apple Inc., OU=Apple Certification Authority, CN=Apple Accessories Certification Authority - 00000002` |
| `assets/offline-mfi/identity.pk8` | 67 B（极简 **P-256 (secp256r1) PKCS#8**），SHA-256 `bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75` |
| 来源自述 | 中文 README 与 `docs/THIRD_PARTY_NOTICES.md`：**"从公开 Carlinkit C2Air（全志 V821）固件中提取"**；"并非为 DiPlay 新签发的 MFi 身份；私钥可被提取；未来 iOS 是否继续接受及其公开分发适用性尚未确定" |
| 构建门控 | `DIPLAY_AUTH_ASSETS_DIR` 显式外部注入（只允许 `identity.pk8`+`certificate.p7b` 两文件）；`rejectBundledCredentials` 拒绝其余凭据容器；`verifyStandaloneAuthentication` 拒绝缺失；CI/源码构建零身份（`scripts/check_public_tree.py` 拦 `.pk8/.p7b/.pem/.key/.p12/.jks/.keystore/.apk` 与私钥 PEM 块） |
| 跨版本一致性 | `VALIDATION.md` 记录：0.1.0 起每次发布的 APK 内**这两个身份文件逐字节不变**（0.2.12 与 0.2.11 一致） |
| 安全自述 | `SECURITY.md`：公开分发后"任何人都能从 APK 提取它们；本地编译、历史清除、混淆都不能让共享密钥保密或撤销旧副本" |

### 5.2 判定

- 这是**真实 Apple 签发的配件身份**（非自签），被提取后在多个生态项目间流通；Carlinkit 固件本身即含被逆向出的该身份。DiPlay 将其打包进公开分发的预览 APK，作者已充分披露风险与局限（合规、法律与未来 iOS 兼容性均未定）。
- 与 DiAuto 的差异：DiAuto 的身份在**源码仓库里就有**（历史惯性）；DiPlay 只存在于 APK、源码与 CI 不含，且有 CI/构建双重门控——工程上更谨慎，但**发布物本身同样可被任何人提取**。

## 6. 权限与特权面

### 6.1 权限（合并后清单要点）

`INTERNET`、`ACCESS_NETWORK_STATE`、`RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS`、`CHANGE/ACCESS_WIFI_STATE`、`NEARBY_WIFI_DEVICES`、`CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_FINE/COARSE_LOCATION`、`BLUETOOTH_CONNECT`、`FOREGROUND_SERVICE`（connectedDevice/microphone/location）、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`（悬浮地图）、`WRITE_SETTINGS`（可选车机热点启动）、**`PACKAGE_USAGE_STATS`**（DiLink 5.1 主题识别）、**`BIND_VPN_SERVICE`**（本地 USB 链路）、`RECEIVE_BOOT_COMPLETED`。

### 6.2 提权/敏感技术

- **本地 adbd（shell 权限）**：自实现 ADB 协议客户端 + 自持 ADB 密钥（见 §4.4）。威胁模型：车主批准一次后，App 获得相当于 `adb shell` 的执行能力（受 SELinux 限制，但在 BYD 车机上是实际操作路径）。
- **`app_process` 通道**：以 shell 用户运行 App 自带工具类。
- **VpnService**：接管 USB NCM 流量（本地用途）。
- **`usesCleartextTraffic="true"`**：shared 清单一处开启明文流量（用于 AirPlay/局域网握手等本地场景），是发布清单里的显眼项。
- 导出组件：`CarPlayHostActivity`（USB attach 过滤器）、`MapEmbedService`（`exported=true`，自定义 action）、`AdbClusterActivity`（`allowEmbedded`）等——供桌面启动器/集群宿主嵌入。

## 7. 隐私与网络行为

- `docs/PRIVACY.md`：无账号、无远程认证服务、无自动诊断上传；配对/认证材料不进 Android 备份；诊断导出为用户主动、会过滤凭据与常见标识；VPN 权限仅本地用途；Usage Access 结果本地过滤到 4 个原厂 Activity。
- 代码扫描（firebase/sentry/analytics 等）**零命中**；官网静态站无分析脚本。
- 唯一"外联"敏感点：**远程 MFi 服务**（若用户配置）会把挑战签名请求发往所配 URL（可 https + Bearer）。默认路径为本地身份，不联网。

## 8. 发布与签名链（已实测验证）

- 本次下载 `DiPlay-0.2.12.apk`（38,117,441 B），**与官方 `SHA256SUMS.txt` 校验一致**：
  `840f2d62fc2c7d10250555b99444da7e0539be3834f635c6aa22ecbd635f7cf4`
- APK 签名（apksigner 验证）：`CN=DiPlay, OU=Private Beta, O=DiPlay`，RSA-3072，**仅 v2 方案**；证书 SHA-256 `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363`——自 0.1.0 起保持不变（可覆盖升级）；Android 签名密钥不进 Git/源码包（`ANDROID_KEYSTORE_*` 环境变量本地注入）。
- 发布纪律：`VALIDATION.md` 逐版本记录（0.2.12：**1,193 个单测通过**；APK 包名/版本/签名/身份字节与构建输入核对；原生库字节级对比；"未宣称完整实车验证"的诚实边界）。

## 9. 历史事件：凭据重置（2026-09-25）

- 当前公开历史的**最早提交**即 `3b67c80`「**Reset public source after withdrawing credential-bearing release**」（2026-09-25）——建库次日，最初公开推送中带凭据的发布被撤回并重置历史。
- 之后相继建立：CI 防泄漏脚本（`scripts/check_public_tree.py`）、构建期身份门控（见 §5.1）、`SECURITY.md` 的凭据与发布说明。仓库内已无法取到重置前的对象（克隆仅含可达历史）。

## 10. 风险与观察清单

1. **MFi 身份提取物公开分发**：真实 Apple 证书 + 私钥在 APK 内可被任何人提取；法律/合规/未来 iOS 兼容风险自述未定。
2. **远程 MFi 信任模型**：把身份与签名能力放到远端 URL（默认关、需用户自配），若被滥用则挑战明文（challenge 会发给自己配的服务器）——注意只配可信服务。
3. **自 ADB 提权链**：一次授权 → 长期 shell 能力；`app_process` 工具类在 shell 上下文运行；虽无自动提权，但显著放大攻击面（任何调用这些通道的功能被篡改后果更重）。
4. **`usesCleartextTraffic=true`**：本地协议需要，但发布清单整体放开明文流量。
5. **大量导出组件**：MapEmbedService 等 exported 组件需关注跨应用调用面。
6. **对原厂系统的侵入**：集群模式切换/原厂地图暂停会改动原厂状态（有恢复日志与重试），强杀可能延迟恢复。
7. **汽车场景安全**：功能全程要求"停车配置"，文档多处提示"停放后再试"。

## 11. 证据附录（关键路径）

```
DiPlay/settings.gradle.kts                     # 模块结构（rootProject.name = "xcertplay"）
DiPlay/mobile/build.gradle.kts                 # DIPLAY_AUTH_ASSETS_DIR / rejectBundledCredentials / assembleStandaloneDebug
DiPlay/scripts/check_public_tree.py            # CI 凭据门控脚本
DiPlay/SECURITY.md · docs/PRIVACY.md · docs/THIRD_PARTY_NOTICES.md · docs/BUILD.md · docs/VALIDATION.md
DiPlay/shared/src/main/java/com/shilapi/xcertplay/
  mfi/LocalMfiAuthenticationClient.kt · RemoteMfiAuthenticationClient.kt · MfiAuthenticationClient.kt
  adb/LocalAdb.kt · adb/AdbKeys.kt            # 自实现 ADB 客户端与密钥
  hud/BydCarPlayCall.kt · BydClusterSong.kt · BydAdbShell.kt   # BYD 集群 + app_process 通道
  network/CarPlayVpnService.kt · CarPlayBonjour.kt · WifiP2pGroupManager.kt
  airplay/AirPlaySession.kt · MfiSapAuthSetup.kt · AirPlayInfoPlist.kt
  transport/NcmUsbBridge.kt · LockdownPairRecord.kt
_artifacts/DiPlay-0.2.12.apk → apk_assets/assets/offline-mfi/{certificate.p7b,identity.pk8}
```

复现验证命令：

```sh
cd D:/Development/DiAuto-DiPlay/_artifacts
sha256sum -c --ignore-missing DiPlay-SHA256SUMS.txt
unzip -l DiPlay-0.2.12.apk | grep offline-mfi
openssl pkcs7 -inform DER -in apk_assets/assets/offline-mfi/certificate.p7b -print_certs -noout
java -jar "D:/Dev Environment/android-sdk/build-tools/35.0.0/lib/apksigner.jar" verify --print-certs DiPlay-0.2.12.apk
```
