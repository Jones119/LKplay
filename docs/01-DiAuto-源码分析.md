# 01 · DiAuto 源码分析报告

> 仓库：<https://github.com/shihabal3amri/DiAuto>（Public，AGPL-3.0）
> 本地克隆：`D:\Development\DiAuto-DiPlay\DiAuto` · HEAD `a2119f4`（v0.3.11，2026-10-01）
> 分析日期：2026-10-06 · 克隆方式：gh-proxy 镜像（完整历史）

---

## 1. 一句话概括

DiAuto 是装在 **BYD DiLink 车机**上的 **Android Auto 接收端**（无线 + USB）：手机不需要装任何配套 App，车机侧自行实现了无线 Android Auto 的蓝牙/Wi-Fi 握手、TLS 通道与音视频解码，并针对 BYD 车机做了风挡 HUD/仪表导航输出与热点/直连恢复等大量适配。它属于 `mikereidis/headunit → andreknieriem/open-headunit` 血统的一个硬核分支。

## 2. 基本信息

| 项 | 值 |
| --- | --- |
| 仓库 | `shihabal3amri/DiAuto`（2026-09-22 创建） |
| Stars / Forks | 约 200 / 25（页面 183、API 抓取 202） |
| 许可 | **AGPL-3.0**（+ `COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`） |
| 语言 / 构建 | Kotlin；Gradle + CMake/NDK；JDK17、Android SDK 36、NDK `29.0.14206865`、CMake 3.22.1 |
| 包名 / 版本 | `com.andrerinas.headunitrevived`（沿用上游包名以保留 DiLink 配对/校准）；v0.3.11，versionCode 111 |
| SDK | minSdk 16（github flavor）/21（playstore flavor）；target/compileSdk 36 |
| 提交 / 分支 / 标签 | 18 commits；`main` + `feat/local-hotspot-5ghz`；7 个 tag（v0.3.1 → v0.3.11） |
| 作者 | Shihab Al Amri（13）、STUkh（3）、Shihab（2） |
| CI | `android-ci.yml`（构建 debug+release，产物不签名、无 secret 对 PR 开放）；`pages.yml`（站点部署） |
| 发布 | 6 个 release：v0.3.1/0.3.2/0.3.3/0.3.9/0.3.10/0.3.11，APK 约 10.7–11.0MB，附 source zip 与 SHA256SUMS |

## 3. 功能与定位

- **车机端 Android Auto**：无线（Wi-Fi Direct / 内置热点 / App 自建 LocalOnlyHotspot，含 5GHz 优选）与 USB（AOA）。无需手机端配套 App、dongle、root 或固件修改；手机需自带 Android Auto。
- 实测范围：**BYD DiLink 5.1 + Android 13**（其余机型不承诺）。
- **BYD 风挡 HUD / 仪表导航输出**（0.3.3+）：转向箭头、距离、路名出现在风挡/仪表，**无需 ADB/root/电脑**。
- **音乐走车机蓝牙**模式（避免音频焦点冲突）；多语言（EN/简中/阿/俄/西 + 社区翻译）；自带诊断导出与设置备份；自建站点（5 语言）。
- 定位口径诚实：README 明示"约 58Hz 面板、无线地图拖动 47–58 FPS、不承诺恒定 60 FPS"。

## 4. 技术架构

### 4.1 模块与代码规模

| 模块/目录 | 内容 |
| --- | --- |
| `app/` | 主应用。`app/src/main/java` 共 **52,805 行 Kotlin**；最大文件：`SettingsFragment.kt` 3,550 行、`aap/AapService.kt` 3,434 行、`aap/AapProjectionActivity.kt` 2,077 行、`decoder/VideoDecoder.kt` 2,021 行、`connection/WifiDirectManager.kt` 1,740 行、`connection/NativeAaHandshakeManager.kt` 1,452 行 |
| `app/src/main/proto/` | 8 个 `.proto`（control/media/sensors/navigation/input/wireless/common/playback）+ `gen.sh`；生成的 Java（Control.java 1.4MB、Sensors.java 1.1MB 等）≈5MB |
| `app/src/main/cpp/` + `jniLibs/` | 原生：**libusb**（4 ABI）与 **FFmpeg 7.1.1**（arm64：libavcodec/libavutil/libswscale，LGPL，用于 HEVC 软解） |
| `contract/` | 小库（`HeadUnitIntent.kt` 179 行）：第三方集成用 intent 契约 |
| `docs/`、`scripts/`、`site/` | 工程文档（含 REVIEW、BSSID 修复记录）、站点生成脚本、5 语言下载站 |

### 4.2 连接栈

- **USB**：`UsbAttachedActivity`（USB_DEVICE_ATTACHED）→ `UsbAccessoryMode` 做 AOA（Android Open Accessory）切换，支持走系统 API 或 **native libusb** 两条路。
- **无线**：`NativeAaHandshakeManager` 自实现官方无线 AA 的蓝牙握手——
  - RFCOMM 服务端监听 **AA UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`** 与 HFP UUID（`0000111e…`）；
  - 消息层实现官方 **WPP**（Wi-Fi 凭据交换）流程：`WppStage = NEW → AWAIT_VERSION → AWAIT_CREDENTIALS → AWAIT_INFO_REQUEST → SETTLING → DONE`；
  - 网络服务端口：**5288**（车机 AA 服务监听）、**5289**（WiFi Launcher 探测）、**5277**（探测对端）；
  - 网络三种形态：Wi-Fi Direct P2P（Group Owner）、车机内置热点（实验）、App 自建 LocalOnlyHotspot（`DiAuto-xxxxxx` SSID，WPA2 随机口令，经反射调用 `SoftApConfiguration`）。
- **唤醒"戳"**：`BluetoothWakePolicy` 对手机发起 HFP-AG / HSP-AG 的 ACL 连接（注释确认数值并引用 `WirelessAndroidAutoDongle`、`openautolink` 等社区实现）。
- **BSSID 自动恢复**（0.3.2+，标志性技术点）：DiLink 固件隐藏 P2P MAC 时，从组接口的 **IPv6 link-local（modified EUI-64）反解出 BSSID**（`P2pInterfaceBssid`，依据 RFC 4291 Appendix A），15 秒窗口内重试，无需 ADB/root；保留手动 Static BSSID 覆盖。文档记录了 HiBy R4 等第二设备的验证。

### 4.3 TLS 与 AAP 协议

- TLS 在 AAP 消息层内裸跑：`AapSslContext` 用 `SSLEngine`（优先 Conscrypt）封装，手工驱动 wrap/unwrap；`SingleKeyKeyManager` 从 `res/raw/cert` + `res/raw/privkey` 装载**车机端身份**；`NoCheckTrustManager` 不校验对端证书（与上游一致，属此类非官方实现的常规做法）。
- 媒体管线：H.264/HEVC 视频解码（MediaCodec，arm64 另有 FFmpeg 软解兜底）、音频（AudioTrack/AudioMixer）、触摸与按键回传（`Input` proto）、导航状态（`NavigationStatus` proto）。
- 显示：`AapProjectionActivity`（1920×1080、SurfaceView/GLES 两种输出、触摸坐标映射、帧节拍控制）。

### 4.4 车机集成

- 启动类广播接收器极其齐全：`BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON（含 MTK）/ FYT·GLSX·Microntek 的 ACCON 点火广播 / ACTION_MT_COMMAND_SLEEP_OUT` 等；蓝牙 ACL、Wi-Fi 状态变化也能触发自动启动。
- 按键：`CarKeyReceiver` 覆盖 `MEDIA_BUTTON` 及一堆国产车机私有广播（`com.syu.*`、`com.microntek.*`、`com.nwd.*`、`com.eryanet.*`、酷我/QQ音乐车机版等）；`RemoteControlReceiver` 处理 `com.android.music.*`、`com.syu.ms.action.external_command`。
- **导航角色**：MainActivity 注册 `APP_MAPS/APP_NAVIGATION` 类别并接管 `geo:`、`google.navigation:`、`headunit://` 深链（把车机侧导航意图承接给 AA 会话/HUD）。
- 调试 flavor 带 `.bydhudtest` 后缀包名（HUD 测试件）。

## 5. BYD 专项集成（逆向色彩最重的部分）

### 5.1 车机属性服务（`hud/BydCarServerTransport.kt`）

用 `PathClassLoader` 直接加载车机自带的 BYD CarServer APK 类（`com.byd.car.server.provider.CarServiceProvider`、`com.byd.car.property.ICarPropertyService/CarPropertyValue`），通过 ContentProvider 查询**把 Binder 塞在 cursor extra 里走私出来**（借助自带的 `com.byd.spi.ipc.cursor.BinderCursor` 类反序列化），再反射调用 `setProperties(...)` 向属性服务写键值；调用前先 `setPackageInfo(pkg, pid)`，注释明确"服务端会按调用方权限校验"。这是与 BYD 私有中间件对接的完整链路（含失败码日志）。

### 5.2 风挡 HUD / 仪表导航输出

- 向原厂 `com.byd.clusterdebug.BroadcastReceiverCAN` 接收器发送**仅导航**广播；另有 SOME-IP（`com.ts.car.someip.service`）与 AMap 车机服务（`com.byd.amapservice`）路径。
- **固件白名单**：仅对特定 DiLink5.1 固件（`…eng.build20260722.221155:user/release-keys`）+ 固定接收器版本（10601004）/签名证书启用；路名文本走 HAL 的 **UTF-16LE 分块协议，48 个 UTF-16 单元上限且不拆代理对**；日志不打路名字面。
- 清理策略：路线结束/断开/关闭输出/指引过期触发清理；强杀进程后由"恢复日志"在下次启动时收尾（文档如实说明无法保证进程无关到期）。
- 相关文件：`hud/` 共 16 个文件（`BydCarServerNavigationOutput`、`BydClusterOutput`、`BydFactoryNavigationOutput/TurnCode`、`BydStandaloneHudOutput/Packets/Session`、`BydHudProtocol` 等，合计约 1,063 行）。

### 5.3 热点与直连适配（0.3.9–0.3.11 主线）

- 车机热点晚于 App 启动时：自动选口错误（把 `eth0`/`ccmni`/`rmnet`/BYD `briotgw` 桥当热点）导致手机 `WifiConnectStatus -11`——0.3.11 全部修复：排除这些接口、等待"手动指定接口真正就绪"、热点的 ENABLED/DISABLED 广播使旧凭据失效、手机上报 join 失败后**先刷新凭据**再重试。
- 无线通道对齐：`NativeGroupFrequencyPolicy` 优先使用车机 station 的**非 DFS 5GHz 主信道**建组（实测把卡顿从 35FPS/p95≈140ms 提升到 47–58FPS/p95≈34ms，REVIEW.md 有全套数据，也明确"不构成通用基准"）。

### 5.4 测试桥（`hud/BydStarterBridge.kt`）

仅供 debug 包（`com.andrerinas.headunitrevived.bydhudtest` / `com.shihab.diplay.hudtest`）连接的本地 TCP 桥（magic `0x42594431`「BYD1」+ 64 位十六进制 token），配合"用户显式 ADB 启动的导航专用桥"做 HUD 喂数测试；生产包不启用。

## 6. 密钥 / 凭据专题（重点）

### 6.1 事实清单

| 事实 | 值 |
| --- | --- |
| `app/src/main/res/raw/cert` | 1,168 B，SHA-256 `c9b8de2ba8b7064988708cbc0b85dc2cdcdb40efdba82b1b0b90c7fbf62c80cd` |
| `app/src/main/res/raw/privkey` | 1,730 B，SHA-256 `88e548c15656c4464cf748da5b41366d96ff2696e264bc9d225670eb04a0f1c3` |
| 证书主题/签发者 | subject `C=US, ST=CA, L=Mountain View, O=Google-Android-Reference`；issuer `O=Google Automotive Link`；序列号 7653；有效期 2014-07-08 → **2044-07-07** |
| 私钥性质 | **真实可用的 RSA-2048 PKCS#8 私钥**（openssl 解析成功；其 modulus SHA1 `ff63888902ca175c232b2745230522fca39f04c0` 与证书完全匹配） |
| 发布 APK 中 | `DiAuto-v0.3.11.apk` 内同样包含二者：资源名被 shrink 混淆为 `res/6k`（证书，1,150B，CRLF 归一化）与 `res/wM`（私钥，1,703B）；modulus 哈希与仓库一致 |
| 上游关系 | 与 `andreknieriem/open-headunit`（HEAD `7e9d813`）的 `res/raw/{cert,privkey}` **逐字节相同**（SHA-256 全同）——身份是从上游血脉继承的，非本 fork 新引入 |
| 同级历史 | 上游还带 `usertrust_ecc_root.pem`/`usertrust_rsa_root.pem` 与 `BundledRootTrustManager`（为 API<21 兜底）；DiAuto 已将其移除，TLS 初始化精简 |

### 6.2 判定与影响

- 这对证书+私钥是这整个开源车机项目家族自 2014 年起使用的**"参考身份"**（组织名即 `Google-Android-Reference`）。任何下载仓库或用 APK 解包的人都能拿到私钥——这是**有意公开分发**的既有状态（上游同样如此），并非本 fork 的疏忽；但它同时意味着：同身份可被任意第三方复刻使用，且无法被撤销（证书有效期至 2044）。
- 用途：Android Auto 会话建立时车机侧出示该身份完成 TLS 握手（手机端校验策略见 Google Gearhead 侧，该项目不涉及）。
- 与合规风险的关系：以非 Google 认证的身份实现 AA 车机端属于灰区（Google 官方要求车机厂商走认证），README 也声明与 Google/BYD 无关联。**若用于商业用途风险自负。**

## 7. 权限与特权面

### 7.1 Manifest 权限（节选）

`INTERNET`、`ACCESS_WIFI_STATE/CHANGE_WIFI_STATE`、`NEARBY_WIFI_DEVICES`、`BLUETOOTH*（含 SCAN/ADVERTISE）`、`ACCESS_FINE/COARSE_LOCATION`、`RECORD_AUDIO`、`SYSTEM_ALERT_WINDOW`、`WRITE_SETTINGS`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE(connectedDevice/mediaPlayback)`、`POST_NOTIFICATIONS`、`READ_PHONE_STATE`、`WRITE_EXTERNAL_STORAGE`、**`ACCESS_SUPERUSER`**（第三方约定）等。

### 7.2 提权组件

- **Shizuku**：`rikka.shizuku.ShizukuProvider`（provider 带 `INTERACT_ACROSS_USERS_FULL` 权限声明）——配合 Shizuku 框架可执行系统级操作。
- **libsu（root）**：`utils/SUExecutor.kt` 用 `Shell.cmd("id")`、`su` 弹窗等；主要给 **FYT 车机**路径使用。
- **反编译产物**：`connection/carkey/fyt/CarFYTReceiver.kt` 注释直言「based of decompilation of `com.syu.steer_HD.apk`」，通过绑定 `com.syu.ms/app.ToolkitService` 与私有 IPC 对接方向盘按键（`isSUNeeded = true`，启动即申请 root）。
- 其他：`Runtime.exec("ip link show …")` 读接口 MAC、`ProcessBuilder("logcat", …)` 导出诊断、`scripts/install.sh` 用 ADB 批注权限/加白名单（文档里的官方配套脚本）。

## 8. 隐私与网络行为

- `PRIVACY.md`：声明**无广告/无分析服务**；设置与配对信息本地存储；诊断日志可含设备标识/网络细节，分享前需自查；站点为静态、无分析脚本；Telegram 频道（`t.me/byd_localized`）自有其策略。
- 代码扫描（`firebase|sentry|crashlytics|analytics|…`）**零命中**；对外网络仅文档/issue 链接；诊断导出为用户手动操作（`DiagnosticReport`/`LogExporter`），无自动上传。

## 9. 发布与签名链（已实测验证）

- 本次下载 `DiAuto-v0.3.11.apk`（11,256,985 B），**与官方 `SHA256SUMS.txt` 校验一致**：
  `c2ba6c2697dbff33f52deb8d6c78829dd6294d69fc1573535adb9de22d31ee3b`
- APK 签名（apksigner 验证）：
  - `CN=DiAuto, OU=Community Project`（自签），RSA-4096；
  - 证书 SHA-256 `fe52bd853eafc107cd7ea4789dad4e7fdda71ce2180ac430067b1ea60ead45cb`；
  - 方案：**v1 + v2**（无 v3）。
- 签名策略（README/INSTALL）：公开版用专属发布密钥；私有预览版不同密钥→升级需先导出设置再卸载重装；CI 产物不签名、维护者私下签名。

## 10. 相对上游 fork 的差异（抽样）

- `app/src/main/java` 下 diff 有 **277 个差异条目**：新增大量 `aap/*Policy.kt` 策略类、`hud/`（BYD）、`contract` 模块、站点/脚本文档；上游独有文件集中在 Mic（麦克风上行）与部分音频策略类（fork 调整了实现位置/取舍）。
- 移除了 `ssl/BundledRootTrustManager.kt` 与 res/raw 下 usertrust 根证书；`ConscryptInitializer` 简化。
- 基准：fork 自上游 commit `562c8dc` 起家，README 明确"仅支持 BYD"。

## 11. 风险与观察清单

1. **私钥公开**：仓库+APK 均含 2014 参考身份私钥（上游同款、非新增）；同身份可被任何人提取/复用，属已知公开状态。
2. **非官方协议实现**：无 Google 授权，商用/量产部署存在合规灰区。
3. **反编译代码**：FYT 交互代码源自反编译厂商 APK，若上游主张权利存在争议空间。
4. **BYD 私有接口**：BinderCursor 走私 Binder、属性服务反射、ClusterDebug 广播——固件白名单+自证声明降低风险，但仍属对原厂系统的深层 hook，可能影响保修/触发风控。
5. **提权面**：Shizuku/root/exec 通道齐全，恶意利用面取决于运行车机环境；APK 仅签名保护、无完整供应链审计。
6. **资产许可**：`assets/byd-hud-icons`（及 DiPlay 同款）来自 BYDMate，**PolyForm Noncommercial** 许可与 AGPL 主许可混搭（文档已注明）。

## 12. 证据附录（关键路径）

```
DiAuto/app/src/main/AndroidManifest.xml                     # 权限/组件/自启
DiAuto/app/build.gradle.kts                                  # 包名/flavor/签名逻辑
DiAuto/app/src/main/java/com/andrerinas/openheadunit/
  aap/AapSslContext.kt  · ssl/SingleKeyKeyManager.kt         # TLS 与身份装载
  connection/NativeAaHandshakeManager.kt · WifiDirectManager.kt
  aap/WppHandshakeSession.kt · BluetoothWakePolicy.kt
  hud/BydCarServerTransport.kt · hud/BydStarterBridge.kt     # BYD 集成
  connection/carkey/fyt/CarFYTReceiver.kt                    # 反编译来源标注
  utils/SUExecutor.kt                                        # root
DiAuto/docs/REVIEW.md · WIRELESS_BSSID_FIX.md · HOTSPOT_RECOVERY.md · BYD_NAVIGATION.md
DiAuto/app/src/main/res/raw/{cert,privkey}                   # 身份（哈希见 §6）
_artifacts/DiAuto-v0.3.11.apk（+ res/6k、res/wM 对应其内身份文件）
```

复现验证命令：

```sh
cd D:/Development/DiAuto-DiPlay/_artifacts
sha256sum -c --ignore-missing DiAuto-SHA256SUMS.txt
java -jar "D:/Dev Environment/android-sdk/build-tools/35.0.0/lib/apksigner.jar" verify --print-certs DiAuto-v0.3.11.apk
unzip -p DiAuto-v0.3.11.apk res/6k | openssl x509 -noout -subject -issuer   # APK 内证书
```
