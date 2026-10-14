# 08 · HY Link（com.hyphp.playkeytool）反编译——CS11 适配参考

日期：2026-10-06 · 对象：`_artifacts/cs11_apps/com.hyphp.playkeytool-1.apk`（v26091401，车机实装版）＋ `_upstream/cs11-carplay-dongle/CarPlay apk.apk`（v26082601，对照）＋ 上游已脱壳 dex
方法：jadx 1.5.6 全量反编译 + 定向精读；资源/Manifest 直读比对。**本文不记录任何密钥/证书字节（eCarX AppKey/AppId 只记字段名，不抄值）。**
路径约定（下文简写）：`UP/` = `_analysis/hylink_src/UP_unpacked/sources/`（jadx 常规输出）；`UPB/` = `_analysis/hylink_src/UP_bad/sources/`（jadx `--show-bad-code`，补回常规模式解不出的方法：`MainActivity.J()`、`ch.onTouch()` 等）；`A_RES/`、`B_RES/` = `_analysis/hylink_src/A_26091401/resources/`、`_analysis/hylink_src/B_26082601_res/resources/`；`_analysis/` 均在 `D:\Development\DiAuto-DiPlay\` 下，未被任何 git 仓库跟踪。

## 0. 速览

1. **A 包（26091401）是百度加固**：真实代码不在 `classes.dex`，在加密的 `assets/baiduprotect1.jar`（2.6MB，非 zip 结构）+ `libbaiduprotect.so`；静态无法反编译其自身代码。本笔记的分析主体是**上游已脱壳的 dex（= v26082601，与对照包 B 同期；版本横幅证据 `UP/com/hyphp/playkeytool/ui/MainActivity.java:2656` 的 `"v26082601"`）**。
2. **A 与 B 的 Manifest 差异仅 2 处**（版本号 26082601→26091401；A 新增 `com.ecarx.permission.CAR_CONTROL`、`com.ecarx.permission.ADAPT_API` 两条权限），`usb_device_filter.xml` 解码后 md5 一致 ⇒ **脱壳代码可作为 A 包的高置信代理**，但引用行号均属 26082601 版。
3. HY Link 是一套"USB 盒子投屏 + 车机深度集成"的成熟实现：**28 字节帧协议 + 每秒心跳 + 停滞自愈**（USB 层）／**MediaCodec 直渲 SurfaceView + EGL 双路镜像**（显示）／**AudioTrack 三层构造回退**（音频）／**ECARX EAS SDK + XUI adaptapi（含专用 `CS11CarSignalReceiver`）**（车机集成）。对 DiPlay 移植最有价值的是它的 **USB 权限免弹窗链路、API19 兼容手法清单、保活/自启组合**。

## 1. 材料与可复现路径

| 产物 | 位置 |
| --- | --- |
| A 包 jadx 输出（壳+资源） | `_analysis/hylink_src/A_26091401/{sources,resources}` |
| **脱壳代码（分析主体）** | `_analysis/hylink_src/UP_unpacked/sources`（3073 个 java）+ `UP_bad/sources`（fallback 补全） |
| B 包资源（对照） | `_analysis/hylink_src/B_26082601_res/resources` |
| 上游脱壳 dex 原件 | `_upstream/cs11-carplay-dongle/apk_unpacked/dex/classes{,2}.dex`（md5 备查 `_analysis/unpacked_dex_md5.txt`） |

复现命令（jadx 脚本在 git-bash 下不可用，用 java -cp 直调）：`java -cp C:/Users/gaoji/Tools/jadx/lib/jadx-1.5.6-all.jar jadx.cli.JadxCLI -d <out> [--show-bad-code] <dex|apk>`

## 2. APK 概览（来自 A_RES/AndroidManifest.xml）

- 包名 `com.hyphp.playkeytool`；versionCode/Name `26091401`；**minSdk 19 / targetSdk 29**；compileSdk 34；abi 仅 `armeabi-v7a`；`extractNativeLibs=true`。
- 加固：百度加固（`assets/baiduprotect*.{dex,jar}`、`libbaiduprotect.so`、`libfg.so`、`libwm.so`）；壳 Application = `com.sagittarius.v6.StubApplication`（Manifest L43）。另有厂商自有 native：`libu.so`（鉴权，`UP/com/u/j/U.java` loadLibrary("u")）。
- 权限要点（L13-34、L192-194）：`usb.host`(required) / `MANAGE_USB` / `SYSTEM_ALERT_WINDOW` / `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` / `FOREGROUND_SERVICE(+MEDIA_PLAYBACK)` / `POST_NOTIFICATIONS` / `RECEIVE_BOOT_COMPLETED` / `REQUEST_INSTALL_PACKAGES` / `WAKE_LOCK` / `com.ecarx.permission.CAR_CONTROL` / `com.ecarx.permission.ADAPT_API` / `android.car.permission.CAR_VENDOR_EXTENSION` / `music.intent.action.source`。
- 组件清单：`ui.MainActivity`（singleTask, fullSensor, configChanges 屏幕尺寸/方向）＋ **activity-alias `ui.UsbAttachAlias`** 与 **`com.juyou.app.activity.UsbActivity`**（后者挂 `USB_DEVICE_ATTACHED` intent-filter + `@xml/usb_device_filter`，L84-98）；`ui.SettingsActivity` / `ui.UpdateInstallerActivity` / `ui.OemIconActivity` / `ui.MicRecordActivity` / `ui.SWKeyActivity`（方控）；`service.CoreService`（foregroundServiceType=mediaPlayback）；`service.BootReceiver`（BOOT_COMPLETED+LOCKED_BOOT_COMPLETED）；`car.SwcMediaButtonReceiver`（MEDIA_BUTTON）；`com.hyphp.sdk.ArtworkContentProvider`；`app.MultiDexBootstrapProvider`。
- meta-data：`eCarX_OpenAPI_AppKey` / `eCarX_OpenAPI_AppId`（值略，见上文件）/ `HY_CAR_DEBUG=1`。
- 版本横幅：运行时状态串拼装自代码字面量 `"v26082601"`（`UP/com/hyphp/playkeytool/ui/MainActivity.java:2656`）——脱壳 dex 的版本锚点。启动含**联网激活校验**（`HyCheck` + `NativeConfigPages`，含厂商激活/下单接口域名 `carplay-admin.hyphp.cn`）；DiPlay 无需该链路，仅作了解。
- **USB DeviceFilter（`A_RES/res/xml/usb_device_filter.xml`，4 组）**：VID `1317`(0x0525) × PID `42153`(0xA4A9) / `42149`(0xA4A5) / `42151`(0xA4A7) / `42161`(0xA4B1)。代码侧同样过滤：`UP/com/hyphp/playkeytool/ui/MainActivity.java:357-363`。

## 3. 适配相关功能地图

| 层 | 关键类（UP/ 前缀省略） | 一句话 |
| --- | --- | --- |
| USB 传输 | `a/af0.java`（UsbTransport）、`a/c8.java`（打开/claim/模式切换） | 28B 头+payload 帧收发，bulk 512B 包，16KB 读 |
| USB 权限/插拔 | `UPB/com/hyphp/playkeytool/ui/MainActivity.java:J()`、`a/j4.java`、`a/bw.java:256-320` | hasPermission→反射 grantPermission→弹窗/前台跳过；插拔重枚举 |
| 协议会话 | `a/ca.java`（CarLink）、`a/v9.java`（读/心跳/停滞）、`a/q20.java`（帧构造） | 握手突发、1s 心跳、停滞两级自愈、触控队列 |
| 视频 | `video/H264Decoder.java`、`video/DualSurfaceRenderer.java`、`ui/AspectRatioSurfaceView.java`、`video/VideoCodecCatalog.java` | MediaCodec→Surface 直渲；EGL 双路镜像；解码器枚举 |
| 音频 | `audio/AudioPlayer.java`、`audio/AudioSessionController.java`、`audio/AudioConvert.java` | AudioTrack 三层构造；焦点 ≤19 兼容；厂商音量 |
| 车机集成 | `a/t9.java`、`com/hyphp/sdk/EcarxGN.java`、`com/hyphp/sdk/ecarx/sdk.java`、`com/hyphp/sdk/api.java`、`car/SwcMediaButtonReceiver.java` | EAS/XUI SDK、CS11 车信号、方控、HUD、日夜 |
| 启动/保活 | `a/bw.java:55-110`（极简页 bootstrap）、`service/CoreService.java`、`service/BootReceiver.java` | 首帧后再全量初始化；前台服务+WakeLock；开机自启 |
| 更新 | `ui/UpdateInstallerActivity.java` + `a/c8.java:363-407` | ≤19 用 file:// URI 装 APK，>19 FileProvider |
| 兼容工具 | `AudioPlayer$Api21/Api24`、`a/c7.java`（主题）、`a/z20.java`（权限/资源） | 版本隔离类与反射兜底 |

## 4. 逐项分析

### 4.1 USB 主机用法（重点）

**权限申请流程（4.4 可直接照搬）**——`UPB/com/hyphp/playkeytool/ui/MainActivity.java:971-1092`（`J(Intent)`）：
1. `carplayEnabled` 开关 + **开机禁用窗口**：`elapsedRealtime < W.c()*1000` 时提示"车机启动中"并 3s 内重试（:977-992）——等待车机 USB 服务就绪。
2. 设备来源：intent extra（`USB_DEVICE_ATTACHED` 附带）→ 否则 `L()` 全量扫描 `UsbManager.getDeviceList()`（`UP/.../MainActivity.java:1356-1396`），用 `S()` 过滤（:357-363）。
3. `usbManager.hasPermission(dev)` 通过 → 后台线程 `z(dev)`（:4667-4675）打开。
4. **免弹窗特权授权（CS11 关键）**：偏好 `skipUsbDialogGuideShown` 打开时，反射调隐藏 API `UsbManager.grantPermission(UsbDevice)`（:1013-1026，需系统应用/`MANAGE_USB`），成功则彻底免弹窗。
5. **前台插盒等待**：若 Activity 前台且是 attach intent，则**不主动 requestPermission**，500ms 轮询等待"USB 服务授权"（:1027-1045）——部分车机（含 ECARX）在前台 attach 时由系统直接授权。
6. 常规路径：`PendingIntent.getBroadcast(ctx, 0, Intent("com.hyphp.playkeytool.USB_PERMISSION").setPackage(pkg), SDK≥31?FLAG_MUTABLE(33554432):0)` + `requestPermission`（:1046-1064）；结果由 `a/j4.java:36-47` 的 receiver 处理（成功→`z()`；失败→提示）。`enhanceUsb` 偏好下对重复 attach intent 重申请（:1065-1090）。

**打开/声明接口/传输**：
- `a/c8.java:137-283`（case 6）：`openDevice`（null→"无权限"日志）→ 遍历 interface/endpoint 找 **bulk(2)** 的 IN(0x80)/OUT(0x00)；**优先 maxPacketSize≥512 的接口**，`claimInterface(iface,true)` 失败回退 `false`（:162-178）；找不到 bulk 且设备类≠8(mass-storage) 时，发 **模式切换控制传输 `controlTransfer(0x40, 0xA7, 0,0, null,0, 1000ms)`，最多 3 次、间隔 1s**，成功则盒子重枚举（:204-249）；成功后等 `usbSettleDelayMs`（默认 3000ms）再启动链路（:272-274）。
- `a/af0.java`（UsbTransport）：写=28B 帧头+payload 循环 `bulkTransfer(OUT,…,1000ms)`（:26-46）；读=`bulkTransfer(IN, buf, 16384, 500ms)`（:88-100）；帧构造见 `a/q20.java`（type=0xAA55AA55=-1437226411 文本命令；`carplay_report_hid`+3×int16 触控 :60-68）；**0xAA55AA59 音频帧带 5 个 int 参数**的特殊封装（:114-126）；连续失败达阈值（约 5 次）触发 transport-dead 回调（:127-141）。
- 收发会话：`UP/.../MainActivity.java:841-956`（`I0()` 启动：读线程 `carlink-read`、**1000ms 心跳**、1500ms 监控、超时 8s 首连/15s 重连）；读循环与解帧 `a/v9.java:31-182`（64KB 累积、16384 读；**帧合法性 `(type>>>8)==0xAA55AA`**；payload≤8MB；错位时扫 `AA 55 AA` 重同步）；**心跳 0xAA55AA5A 空帧每秒**，连失 3 次断链（`a/v9.java:183-210`）；停滞检测：达阈值先"链路内会话复位"（`carplay_stop`+`carplay_start(8)`）再升级为重建链路（`a/v9.java:211-250`、`a/c8.java` case 5 / `v9` case 5）。
- 插拔与重连：`a/j4.java:49-110`（DETACHED：无活链路→清场；`exitOnDisconnect=true` 直接退出，否则进入 **2000ms 有界等待重枚举**；ATTACHED：按 deviceId 去重）。
- 接收方注册（含新旧 API 差异）：`a/bw.java:267-275`——IntentFilter 含自定义 `com.hyphp.playkeytool.USB_PERMISSION` + `USB_DEVICE_{ATTACHED,DETACHED}`；**SDK≥33 用 `registerReceiver(r,f,4)`（RECEIVER_NOT_EXPORTED）**。

### 4.2 显示 / 视频 / 音频

- **视频显示**：`ui/AspectRatioSurfaceView.java:9-13`（纯 SurfaceView 子类）+ `video/H264Decoder.java`——`MediaFormat.createVideoFormat("video/avc",w,h)` + `csd-0/csd-1`(SPS/PPS) + `max-input-size`，**`codec.configure(format, surface, null, 0)` 直渲 SurfaceView（无 EGL、无 TextureView）**（:193-256）；`getInputBuffer` 兼容分支 **API<21 走 `getInputBuffers()` 遗留数组**（:441-449）；单帧超输入缓冲时：API≥26 走 `queuePartialFrame`，否则丢帧并请求关键帧（:427-433）；QTI 芯片走 vendor `vendor.qti-ext-dec-low-latency.enable`，标准 `low-latency` 键仅在 API≥30/31 设置（:206-214）；解码线程拆分 `splitCodecThreads=SDK≥24`（:1429）；输入缓冲下限 512KB/上限 32MB（:451-453）。
- **双路镜像（主屏+仪表副屏）**：`video/DualSurfaceRenderer.java`——EGL14 + GLES20，OES 外部纹理从解码出的 SurfaceTexture 采图，分别 blit 到 main/second 两个 EGLSurface；GL 初始化失败则回退"双解码"方案（`a/c8.java:128-136` 调 `o0("GL 初始化失败回退双解码",…)`）。
- **分辨率/帧率协商**：`a/ca.java:1371-1459`（`t()` 配置突发送 `carplay_report_resolution`、`carplay_report_physical_size`（按 1920/1280 分档的 DPI 系数 :1395-1405）、`set_video_fps`、cluster 系列 `CLUSTER_*`）；主画面分辨率变化触发解码器重建+关键帧请求（`UP/.../MainActivity.java:2601-2617`）；cluster 尺寸/安全区钳制 `a/ca.java:1295-1367`；配置对象 `a/z9.java:6-31`（默认 1280x720@30）。
- **解码器枚举**：`video/VideoCodecCatalog.java:37-72`——**API<21 反射 `MediaCodecList.getCodecInfos()`** 静态法，API≥21 用 `new MediaCodecList(0).getCodecInfos()`；过滤 secure/software 标记，供设置页选择。
- **音频**：`audio/AudioPlayer.java`——`getMinBufferSize`+三层构造回退：API≥26 `AudioTrack.Builder().setPerformanceMode(1)`（:537/:644）、API≥23 `AudioTrack(AudioAttributes, AudioFormat, buf, MODE_STREAM, 0)`（:541/:544）、**API<21 老构造 `new AudioTrack(streamType, rate, channelMask, encoding, buf, MODE_STREAM)`**（:528 判定、:530/:659/:670/:861/:949）；**32bit float 输入在 <21 强制降为 16bit**（:351）；写入用 `Api21.writeBlocking(ByteBuffer,…)`（:111-113、:2136）；欠载统计 `Api24.getUnderrunCount`（:117-124、:1319-1324）。音频帧参数随 0xAA55AA59 帧 5 参数下发（`a/af0.java:114-126` → `AudioPlayer$AudioParams`）。
- **音频焦点/车机音量**：`audio/AudioSessionController.java`——**API≤19 导航播报时保留媒体焦点**（:1345-1366）；"兼容唤醒私有导航音量面板"用 `setStreamVolume(+1)→adjustStreamVolume(stream,0,FLAG_SHOW_UI)→setStreamVolume(回原)`（:1384-1419）；厂商参数 `AudioManager.setParameters`（日志前缀 "820A"，:1337-1343）。
- **投屏数据入口速查（按帧类型）**：视频帧 `0xAA55AA58`→`ca.java:804-814`→`MainActivity.g0(len,data)`（:2132）→`bc` 管线→H264Decoder；音频帧 `0xAA55AA59`→`ca.java:815-817`→`MainActivity.d0(ct)`（:1986，含"媒体暂停时丢弃尾帧"防抖）；元数据 JSON `0xAA55AA5B`→`ca.java:820-870+`（NowPlaying/Call 状态）；鉴权 `0xAA55AA57`→`ca.java:797-802`→`w9`→`libu.so U.p()`。
- **触控回传（含副屏手势）**：坐标映射 `MainActivity.W0()`（`UP/.../MainActivity.java:1938-1963`，view 坐标→流分辨率线性映射并钳制）；事件源 `setOnTouchListener`（:253）→ `UPB/sources/a/ch.java:41-240`（单击 down/up 成对发送；**多指左/右滑 = 显示/隐藏仪表副屏**——指数量=偏好 `cluster_gesture_fingers`(0-4)+1，旧键 `cluster_three_finger`（默认即"三指"），触发阈值 `max(60, min(width×0.18, 320))`）；入队 `a/ca.java:1539+`（`y(x,y,action)`）→ 发送 `a/v9.java:310-343`（`carplay_report_hid`，int16 x/y/action）。方控/媒体键 → `media_key` 帧（`a/t9.java:131-154`）。

### 4.3 Android 4.4（API19）兼容手法清单

| 手法 | 例证（UP/ 或 UPB/ 前缀省略） |
| --- | --- |
| 新 API 放到独立静态类，防旧 Dalvik 校验失败 | `audio/AudioPlayer.java:106-124`（`Api21`、`Api24`） |
| 反射兜底被隐藏/新增 API | `MediaCodec.getName`（`video/H264Decoder.java:136-152`）；`MediaCodecList.getCodecInfos`（`video/VideoCodecCatalog.java:41`）；`UsbManager.grantPermission`（`UPB/.../MainActivity.java:1015`） |
| 老缓冲数组路径 | `H264Decoder.java:441-449`（API<21 `getInputBuffers`） |
| 安装 APK 的 URI 方案 | `a/c8.java:377-387`：**SDK≤19 `Uri.fromFile`**，>19 FileProvider+`setClipData` |
| 通知（无通道时代） | `service/CoreService.java:139-143`：≥26 用带 channel 的 Builder，否则 `new Notification.Builder(ctx)`；**<21 `setSound(uri, audioStreamType)`**；:151-241 一大段 ≥20/≥21/≥24/≥26/≥28/≥29 的逐级 setter（androidx 风格链） |
| 前台服务启动方式 | `CoreService.java:31-38`：≥26 `startForegroundService` 否则 `startService`；`startForeground(1, n)` + `START_STICKY`(:287) |
| 动态广播注册标志 | `a/bw.java:271-275`（≥33 加 `RECEIVER_NOT_EXPORTED=4`） |
| PendingIntent 可变性 | `UPB/.../MainActivity.java:1050-1055/1077-1082`（≥31 `FLAG_MUTABLE=33554432`） |
| 主题日夜在 4.4 的降级 | `a/c7.java:63-105`：`SDK≤19` 的 auto 主题**强制夜间模式(1)**、`g()` 直接返回 1（不读 uiMode） |
| 运行时权限数组 | `a/bw.java:256-266`：始终含 `RECORD_AUDIO`；≥33 加 `POST_NOTIFICATIONS`；`z20.j()` 返回值语义按 API 分支（`a/z20.java:1282`） |
| **Android 4.4 专属：静态会话接管** | `a/bw.java:280-315`：`SDK≤19` 且进程内已有后台保留的 CarLink 会话（静态 `ca.d0`）时，新 Activity **直接接管链路**（"Android4.4新Activity接管USB投屏链路,准备重建Surface并请求关键帧"） |
| 保活组合 | `CoreService`：前台通知+`PARTIAL_WAKE_LOCK("HYCarplay:link"、setReferenceCounted(false))`（:273-285）；`BootReceiver`：开机启动服务与/或 `startActivity(NEW_TASK)`（`service/BootReceiver.java:16-38`，偏好 `backgroundPrepareEnabled`/`autoBootMode`） |
| 屏幕常亮/全屏 | `UP/.../MainActivity.java:803`（`addFlags(128)`=KEEP_SCREEN_ON）；:2900-2909 按 `showStatusBar` 用 `clearFlags(1024/2048)`+`addFlags(1024)` 切全屏 |
| 崩溃兜底 | `a/bw.java:215-242`：UncaughtException 时释放全部音频、复位会话、注销车机 SDK，再 `Process.killProcess` |
| 日志落盘 | `a/q6.java:612-620`：`getExternalFilesDir("logs")`（失败回退 `a(ctx)/logs`），文件名 `log-<日期>-pid-<pid>.txt` |

### 4.4 车机集成（ECARX / CS11）

- **ECARX 官方 SDK 直接打包**（`com.ecarx.eas.*`、`com.ecarx.xui.adaptapi.*`、`com.ecarx.sdk.*`，数百类）：`com/hyphp/sdk/EcarxGN.java` 用 **XUI adaptapi** 的 `ICar`、`ISensor`（车速）、`Device`、`IDayNightMode`、`ICarSignalManager`、`CarSignalReceiver`/`SignalsToFilter`（:57-75）；其中 **`public class CS11CarSignalReceiver extends CarSignalReceiver`**（:77-81，`onSignalReceive(Bundle)` :663+）——按 CS11 车型注册车信号过滤表（信号 ID/回调均被字符串加密+控制流平坦化，语义需运行期日志对照）。
- **HYPHP 自研 SDK 适配层** `com/hyphp/sdk/{api,ecarx/sdk,EcarxGN}.java`（3 个大文件，混淆重）；接入面 `a/t9.java:97-232`（CarIntegration）：`api.getInstance()` 异步初始化、`initInput`（方控/媒体键→`q20.d(key,2,"media_key")` 发给盒子 :131-154）、`downloadUrl`（OTA 推送→拉起 UpdateInstaller :155-162）、`initPhoneCall`（通话状态）、`setDayNight`、`needStopMusic`、`init_hud(Activity)`、`onCarplayApp()/onConnectCarplay()/unConnectCarplay()`。
- **方控**：`car/SwcMediaButtonReceiver.java:15-31`（MEDIA_BUTTON→媒体会话）；`ui/SWKeyActivity` 提供按键映射（设置项 `swKey*`）。
- **HUD/副屏**：`com/hyphp/sdk/hud/ElementPresentation.java:23-34` 用 `android.app.Presentation` 绑外部 `Display`；悬浮窗/日志球 `com/hyphp/sdk/LogFloatingBall.java`（配合 `SYSTEM_ALERT_WINDOW`）、亮度 `ScreenBrightnessUtil`。
- **鉴权黑盒**：`com/u/j/U.java`（`loadLibrary("u")`，native `a()`=签名、`p()`=处理 0xAA55AA57 鉴权帧）+ 回调 `com/juyou/p/P.java`（`s(String,byte[])`/`m(byte[])`，JNI 按名绑定）——**类名/方法名不可改**。
- 启动期校验：`HyCheck` + `NativeConfigPages.showCheckWindow`（激活校验失败弹窗，`a/bw.java:59-63`）。

### 4.5 启动 / 保活（4.4 慢车机优化）

- **极简页两段式启动**：先 `setContentView(2131492893)`（极简页）+ `KEEP_SCREEN_ON`（`UP/.../MainActivity.java:801-814`），待**首帧回调或开机延后窗口到期**后做"完整初始化"：激活校验→`setContentView(2131492892)` 全量布局→SDK/音频/车机初始化（`a/bw.java:55-110` 起，日志串"完整初始化开始(极简页首帧回调或开机延后窗口到期)"）。
- 保活：`CoreService`（前台常驻+WakeLock）；`backgroundPrepareEnabled` 控制后台预备；`BootReceiver` 开机自启；`onPause` 的"keepVideo"分支在后台**保留 Surface/解码器不销毁**（`UP/.../MainActivity.java:3251-3282`）。
- 设置面（prefs 键，`h40` 封装）。对 DiPlay 有对照意义的键：

| 键 | 作用（结合调用点推断） |
| --- | --- |
| `carplayEnabled` | 总开关；false 时"仅设置可用"（`UPB/.../MainActivity.java:977`） |
| `autoBootMode`（默认 "1"） | 开机自启策略；=0 时 BootReceiver 直接拉起 MainActivity |
| `backgroundPrepareEnabled` | 后台预备：开机即启动 `CoreService` 保活 |
| `usbSettleDelayMs`（默认 3000） | USB ready 后稳定等待再启动链路（`a/c8.java:272`） |
| `enhanceUsb` | 增强 USB：对重复 attach intent 重新申请授权 |
| `exitOnDisconnect`（默认 true） | 拔线即退出；false 则有界等待重枚举（`a/j4.java:64`） |
| `skipUsbDialogGuideShown` | 启用 `grantPermission` 反射静默授权（免弹窗） |
| `swMediaButton` / `disableMediaSession` | 方控媒体键接管 / 彻底关闭系统媒体声明 |
| `audioFocus` / `audioDuck*` | 音频焦点总开关 / 闪避策略 |
| `micSource` `micEncoding` `micGainX` `micChannels` | 麦克风采集参数（通话上行） |
| `preferredDecoder` / `qtiLowLatencyDecode` | 指定解码器 / QTI 低延迟开关 |
| `highAudioApi` | 高版本音频 API 使用开关 |
| `mirrorSingleDecode` | 镜像（副屏）单解码模式 |
| `cluster_fps/safe/bounds/overlay/auto_enter/dark_mode…` | 仪表副屏（第二路流）全部参数 |
| `showStatusBar` / `showNavigationBar` | 全屏切换（`UP/.../MainActivity.java:2900-2909`） |
| `hiddenDebugModeEnabled` / `saveLogEnabled` | 隐藏调试模式 / 文件日志（`a/q6.java`） |

## 5. 对"DiPlay 移植到 CS11"的可复用点清单

**P0（直接照抄级）**
1. `UPB/.../MainActivity.java:1013-1026`——USB 免弹窗三段式：`hasPermission`→反射 `grantPermission`→回退系统弹窗；CS11 若无 `MANAGE_USB` 则第 3 段兜底。
2. `UPB/.../MainActivity.java:1027-1045`——前台 attach intent 时**跳过 requestPermission**、轮询等系统授权；对照 CS11 实测（07d 清单里的 USB 授权项）。
3. `a/j4.java` + `a/bw.java:256-275`——USB_ATTACHED/DETACHED 播报处理：deviceId 去重、2000ms 重枚举窗口、`exitOnDisconnect` 策略；注册时 ≥33 的 receiver flag 写法。
4. `a/c8.java:137-283`——USB 打开模板：双 pass 端点匹配（优先 512B 包）、claim 强制/非强制回退、**类≠8 时的 0xA7 模式切换控制传输**、`usbSettleDelayMs` 稳定等待。
5. `a/af0.java` 全文——UsbTransport 最小实现（读写锁+超时+失败计数回调），可直接作为 DiPlay 4.4 分支的 USB IO 层参考。
6. `video/H264Decoder.java:441-449` 与 `video/VideoCodecCatalog.java:41`——API<21 的 `getInputBuffers()` 与 `MediaCodecList` 反射两处硬兼容。
7. `audio/AudioPlayer.java:106-124/351/511-544`——Api21/Api24 类隔离 + float→16bit 降级 + 三层 AudioTrack 构造（4.4 走老构造）。

**P1（按需裁剪）**
8. `a/v9.java:31-182`——带重同步与长度校验的流解帧循环（网络/USB 通用）。
9. `a/v9.java:183-250`——1s 心跳保活 + 停滞两级自愈（对应盒子看门狗）。
10. `a/ca.java:1371-1459`——握手配置序列（分辨率/DPI/FPS/日夜/右手驾驶）与**固件版本门槛**写法（`O>=20250620` 等）。
11. `service/CoreService.java` + `service/BootReceiver.java`——前台服务+WakeLock+开机自启（保持 DiPlay 后台存活）。
12. `audio/AudioSessionController.java:1345-1419`——≤19 焦点保留与"唤醒私有音量面板"技巧。
13. `a/bw.java:55-110` + `UP/.../MainActivity.java:801-814`——两段式启动（先出画面再慢初始化），4.4 机必用。
14. `a/c8.java:363-407`——≤19 `Uri.fromFile` 安装 APK（DiPlay 自更新用）。
15. `a/bw.java:215-242`——崩溃清理（音频/会话/车机）防御。

**P2（了解即可）**
16. `video/DualSurfaceRenderer.java`——EGL 双表面镜像（DiPlay 单屏可不用）。
17. `a/t9.java` + `EcarxGN.java` + `CS11CarSignalReceiver`——ECARX 车信号/方控/HUD/日夜接入面（取决于 DiPlay 是否要"像原车应用"）。
18. `a/q6.java:612-620`——日志落 `getExternalFilesDir("logs")`，方便车机现场取证。

## 6. 局限与注意

- **版本偏差**：分析代码=26082601，目标=26091401。Manifest 仅差 2 权限；但代码级差异未逐字节核对（A 为加固包）。若需精确限定，须在设备上动态脱壳（frida-dexdump 等）后再比对；本文结论对"适配手法"层面向后兼容。
- **加固**：`assets/baiduprotect1.jar` 为加密数据（非 zip），静态解壳需逆向 `libbaiduprotect.so`（本次未做）。A 包 `classes.dex` 仅壳代码（`com.sagittarius.*` + androidx）。
- **混淆**：`EcarxGN/sdk.java/api.java` 存在字符串加密与控制流平坦化，涉及具体信号 ID/命令语义的结论多为结构推断；引用行号以 `_analysis/hylink_src/` 下 jadx 输出为准（重新反编译行号可能漂移）。
- **依赖外部条件**：ECARX EAS/XUI 需要车机侧服务与授权（AppKey/AppId 已在 Manifest，但在 CS11 上是否可用需实机验证）；`libu.so` 鉴权绑定包名/签名，DiPlay 不可直接复用（详见 07e）。
- **未覆盖**：未见屏幕旋转处理（车机横屏固定，`screenOrientation=fullSensor` 仅适配器层面）；HiCar 分支（`MainActivity:2661-2665` 的 A2==3）与 CarPlay 共用链路但未展开；通知栏媒体会话、ArtworkContentProvider 细节未深挖。
- **合规**：本文未复制任何密钥/证书/身份材料的字节内容；也不建议在笔记中粘贴 `eCarX_OpenAPI_AppKey/AppId` 实际值。

## 7. 方向盘媒体键移植（已实施 2026-10-06 深夜）

**问题**：CS11 上方向盘 播放/暂停/上一首/下一首 无效。

**根因（代码实锤）**：`CarPlayMediaKeys.start()` 有一道 `< API21 → "media keys unavailable before API 21" → return` 的闸——BYD 系媒体键的路走 `MediaSession`（API21+），CS11 移植时对 4.4 **整块停用**，未实现遗留路径。

**参照**：本文 §2 组件清单里的 **`car.SwcMediaButtonReceiver`（MEDIA_BUTTON）**——HYLink 在同一台 CS11 上正是靠 `android.intent.action.MEDIA_BUTTON` 广播拿方向盘键（其接收器实现：校验 action + `EXTRA_KEY_EVENT`，取出 KeyEvent 送进自家链路）。其方控设置页文本亦佐证：*"默认并不需要学习，会默认接入本车型的方控功能"*（车机系统按车型投递标准媒体键）。

**实施（提交 `d8611cd`）**：
1. 新增 `com.shilapi.xcertplay.MediaButtonReceiver`（common 模块；manifest 注册 `MEDIA_BUTTON` filter，exported=true）。
2. `CarPlayMediaKeys`：SDK<21 时改走 `startLegacyKeys()`——请求遗留音频焦点 + `AudioManager.registerMediaButtonEventReceiver(ComponentName)`（4.4 的"最后注册者胜"机制），持有至会话结束；`releaseLocked()` 增加 `unregister` + <21 的 `abandonAudioFocus`。
3. KeyEvent → 复用既有映射 `CarPlayMediaButton.forKeyCode` → `send()`（内含 CarPlay 视频播放器拦截）→ iAP2/HID 媒体报。**未识别的键码会打日志**（`legacy media key unmapped code=…`），供实机补码。

**待实机验证（下一登车日）**：
- 装新 APK → 播放 CarPlay 音乐 → logcat 查 `media keys active (legacy MEDIA_BUTTON) focusGranted=…`；
- 按方向盘四个键 → 查 `media key … -> iPhone` 发送日志 + iPhone 实际响应；
- 若见 `unmapped code=…`：记录该码补进 `CarPlayMediaButton.forKeyCode`（对照其 N 值是否为 ECARX 自定义键）。
- 竞争提示：HYLink 装机版也在车机上活过;两者都注册时"最后注册者胜"；CarPlay 音频开始时我们注册，互不冲突时以实机为准。
