# 07d · UI / 生命周期 / 系统集成 / 构建与 API 19 阻塞清单

范围：`common/` 全量（72 main kt + 98 test kt＝170，含 `settings/` 4 个、`ui/theme/` 3 个）、`mobile/` 全量（manifest、config.xml、debug 3 个 kt）、`automotive/`（清单级）、`shared/` 的 `hud/`（40 kt）与 `adb/`（3 kt）清单、构建体系（`settings.gradle.kts`、根与各模块 build、`gradle/libs.versions.toml`）、文档（BUILD / INSTALL / COMPATIBILITY / RELEASE-NOTES-0.2.13 / README.zh-CN）。只读分析：未修改仓库、未运行 gradle。
基线：`main` = b26cd54（v0.2.13）。路径相对仓库根；所有结论附 `文件:行号`；依赖 minSdk 为**实测值**（从 Google Maven / Maven Central 下载对应 AAR 并读其内 `AndroidManifest.xml` 的 `uses-sdk`，临时副本在 scratch `api19/`），无法实测处标 **待核实**。

**先纠正任务书的四处假设**
1. 不存在独立的 `hud/` 模块：40 个 HUD 文件位于 `shared/src/main/java/com/shilapi/xcertplay/hud/`；`mobile/` 的 main 源集没有 Kotlin（只有 manifest/资源），Kotlin 仅 `mobile/src/debug/` 3 个文件。
2. `common/` 170 kt = 72 main + 98 test；main = 根包 65 + `settings/` 4 + `ui/theme/` 3。
3. 语言是 **7 种**（英语默认 + zh-CN、zh-TW、ar、ru、es、uk），AppLocale 另有 "system" 项；不是 6 语言。
4. 全部 Activity/Service 在 `common`（namespace `com.shilapi.xcertplay.host`）；`:mobile`（applicationId `com.shihab.diplay`）只是壳。

---

## ① 模块与界面清单

### 1.1 模块与构建地图
| 模块 | 构建文件 | 角色 | 关键点 |
| --- | --- | --- | --- |
| `:common` | `common/build.gradle.kts` | 全部 UI/Activity/Service/设置页 | library；`minSdk = 28`（:13）；Compose 插件+依赖但仅 4 文件使用；单测 Robolectric 4.17 / Mockito 5.20 |
| `:shared` | `shared/build.gradle`（Groovy） | 协议核心（airplay 39 / transport 33 / network 42 / media 17 / iap2 9 / hud 40 / orchestration 9 / mfi 7 / adb 3 等，main 204 kt）＋JNI＋全局 manifest | `minSdk = 28`（:9）、abiFilters 含 armeabi-v7a（:13）、`APP_PLATFORM=android-28`（:18）、ndkVersion `28.2.13676358`（:24） |
| `:mobile` | `mobile/build.gradle.kts` | 应用壳：合并 manifest + 少量资源 | applicationId `com.shihab.diplay`（:17）、minSdk 28（:18）、targetSdk 37（:19）、versionCode 32 / 0.2.13（:20-21）；debug 后缀 `.hudtest`（:42） |
| `:automotive` | `automotive/build.gradle.kts` | AAOS 壳（无 Kotlin 代码） | 仅 manifest + config.xml；`minSdk 28 / targetSdk 37` |
| `:maphost` / `:home` | `samples/*/build.gradle.kts` | 示例宿主（地图嵌入、替代桌面） | 非发布物 |
| 根 | `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml` | AGP 9.3.0、Kotlin 2.2.10、compileSdk `release(37)`；JDK 25（`docs/BUILD.md:3`） | rootProject.name = `xcertplay` |

### 1.2 Compose vs 传统 View（精确统计）
- **Compose：全仓仅 4 个文件，全在 common**：`MainActivity.kt`（122 行，I2C 自检页，`setContent` 于 :39）、`ui/theme/Color.kt`、`ui/theme/Theme.kt`、`ui/theme/Type.kt`。`mobile/`、`automotive/`、`shared/`、`samples/` 无任何 `androidx.compose` 引用。
- **传统 View：主界面全部为代码构造布局**，直接 `import android.widget.*` 的 main 文件 14 个（CarPlayHostActivity、DiPlayActivity、CarPlayVideoActivity、CarPlayPicturePanel、CenterMapOverlay、ClusterMapPresentation、ClusterSafeAreaPreviewFrame、AdbClusterActivity、ImageCropActivity、MapEmbedService、NavigationWidget、HotspotJoinControls、settings/SettingsWidgets、WheelKeyService）；全仓 XML 布局只有 1 个（`common/src/main/res/layout/widget_navigation.xml`）。
- 结论：改造 UI 栈到 API 19 主要不是"砍 Compose"问题（只有 4 个文件），而是依赖降级与系统 API 兼容问题。

### 1.3 界面清单
| 界面 | 文件 | 行数 | 技术 | 关键行 |
| --- | --- | --- | --- | --- |
| CarPlay 主宿主 | `common/.../CarPlayHostActivity.kt` | 4826 | View：FrameLayout + TextureView + 手写设置菜单 | 类 :115；`onCreate` :498；`setContentView(buildContentView())` :527；内建设置菜单 `buildSettingsMenu` :1487；侧栏 :1407-1486；菜单内 MFI/热点/分辨率等分区 :2100+ |
| 设置/主页 | `common/.../DiPlayActivity.kt` | 3246 | View：ScrollView + LinearLayout，`page=home/settings/connection/about` | `render()` :297；`languageSettings()` :3154；BYD 车辆数据 :1697-2330 |
| 视频回放 | `common/.../CarPlayVideoActivity.kt` | 352 | View + Media3 `PlayerView` | media3 导入 :27-43 |
| DiLink 仪表投影 | `common/.../AdbClusterActivity.kt` | 323 | View + SurfaceView，独立 task | 类 :20；沉浸式 :37-40 |
| 图标裁剪 | `common/.../ImageCropActivity.kt` | 400 | View | 类 :33 |
| I2C 自检（诊断） | `common/.../MainActivity.kt` | 122 | **Compose Material3** | `enableEdgeToEdge()` :38；`setContent` :39 |
| 控件/组件库 | `settings/SettingsWidgets.kt` 等 | 303+ | View | Switch/RadioGroup/SeekBar 封装 |
| 悬浮与嵌入 | `CenterMapOverlay.kt`(272)、`ClusterMapPresentation.kt`(269)、`ClusterTurnCardView.kt`(280)、`SafeAreaEditorView.kt`(196)、`NavigationWidget.kt`(135) | — | View/AppWidget | — |
| 调试（非发布） | `mobile/src/debug/.../StandaloneHudDemoActivity.kt` 等 3 文件 | — | View | 仅 debug 变体 |

`common` main 合计 16716 行；最大的两个文件（CarPlayHostActivity + DiPlayActivity = 8072 行）占了近一半。

---

## ② Manifest 全表

### 2.1 `common/src/main/AndroidManifest.xml`（核心，随所有变体合并）
权限：
| 行 | 权限 | 用途 |
| --- | --- | --- |
| :4 | RECEIVE_BOOT_COMPLETED | 开机自启（BootReceiver） |
| :6 | WRITE_SETTINGS | 车机热点开关（CarHotspotSetup HOTSPOT） |
| :8-11 | FOREGROUND_SERVICE / _CONNECTED_DEVICE / _MICROPHONE / _LOCATION | DiPlaySessionService 前台类型 |
| :12 | POST_NOTIFICATIONS | 连接常驻通知（13+ 运行时申请） |
| :14 | SYSTEM_ALERT_WINDOW | 中央屏悬浮地图卡（CenterMapOverlay） |
| :16 | PACKAGE_USAGE_STATS（tools:ignore） | 检测前台是否桌面（DiLink5.1/中央地图自动隐藏） |

queries：`com.byd.carsettings` + HOME intent（:19-24）。
组件：
| 类型 | 名称 | 关键属性 | 行 |
| --- | --- | --- | --- |
| provider | `DiagnosticReportProvider` | authority `${applicationId}.diagnostic-reports`，grantUriPermissions，FILE_PROVIDER_PATHS=@xml/diagnostic_report_paths | :26-33 |
| activity | `DiPlayActivity` | exported，LAUNCHER，singleTop，resizeable，configChanges 全量 | :35-44 |
| service | `DiPlaySessionService` | `foregroundServiceType="connectedDevice\|microphone\|location"` | :45-46 |
| activity | `CarPlayHostActivity` | affinity `${applicationId}.carplay`，singleTask，exported，fullSensor，USB_DEVICE_ATTACHED filter + `@xml/usb_device_filter`（CH341 VID 0x1A86/PID 0x5512 + Apple 0x05AC） | :48-66 |
| activity | `AdbClusterActivity` | affinity `.cluster`，excludeFromRecents，allowEmbedded，exported，Theme.AdbCluster | :68-78 |
| activity | `CarPlayVideoActivity` | not exported | :80-84 |
| activity | `MainActivity` | not exported | :86-90 |
| activity | `ImageCropActivity` | not exported，fullSensor | :92-97 |
| receiver | `NavigationWidget`（AppWidgetProvider） | APPWIDGET_UPDATE + `@xml/navigation_widget_info` | :99-110 |
| service | `MapEmbedService` | exported，action `com.shihab.diplay.action.EMBED_MAP`（启动器嵌入地图） | :112-120 |
| service | `WheelKeyService` | 无障碍服务（BIND_ACCESSIBILITY_SERVICE），`@xml/wheel_key_service`（仅 key events） | :122-134 |
| receiver | `BootReceiver` | exported，BOOT_COMPLETED | :136-143 |
| service | `UsbAutoConfirmService` | 无障碍服务，`@xml/usb_auto_confirm_service_config`（限 com.android.systemui/android） | :145-156 |

### 2.2 `shared/src/main/AndroidManifest.xml`
- 权限：INTERNET :3、ACCESS_NETWORK_STATE :4、RECORD_AUDIO :5、MODIFY_AUDIO_SETTINGS :6、CHANGE/ACCESS_WIFI_STATE :7-8、NEARBY_WIFI_DEVICES :9、CHANGE_WIFI_MULTICAST_STATE :12、ACCESS_COARSE/FINE_LOCATION :13-14、BLUETOOTH_CONNECT :15、BLUETOOTH/ADMIN（maxSdkVersion=30）:16-21。
- uses-feature `usb.host`（required=false）:23；queries 含 `com.byd.carsettings/appmgr/deviceinfo/service/clusterdebug/ts.car.someip.service/amapservice/automap` + WIFI_SETTINGS/WIFI_TETHER 意图 :27-42。
- application `usesCleartextTraffic="true"` :44；meta `androidx.car.app.minCarApiLevel=1` :46-49。
- service `MyCarAppService`（Car App，exported，category POI）:50-58；service `CarPlayVpnService`（BIND_VPN_SERVICE，exported=false）:60-63。
- 注：`mobile` 把 MyCarAppService 与 minCarApiLevel **移除**（`mobile/src/main/AndroidManifest.xml:26-27`），只有 `automotive` 保留 Car App 生态。

### 2.3 `mobile/`（应用壳）
- main：allowBackup=false :6、localeConfig :11、resizeableActivity :14、meta `android.allow_multiple_resumed_activities=true` :18、BootReceiver `tools:replace android:enabled` :22-24、移除 Car App service :26-27。
- debug：`BYDAUTO_INSTRUMENT_COMMON` 权限 :2、queries `com.byd.clusterdebug` :3、`StarterBridgeReceiver` :5 与 `StandaloneHudDemoActivity` :6（均 `android:permission="android.permission.DUMP"`，仅 shell 可触发）。

### 2.4 `automotive/`
- `uses-sdk tools:overrideLibrary="androidx.car.app.automotive"` :7（注释说明该库按 API29 声明、此处不使用其 29+ 组件）；`uses-feature android.hardware.type.automotive required=true` :9-11；meta `com.android.automotive` :26-28；appCategory=audio :15；无自有 Activity。

### 2.5 samples
- `samples/home`：QUERY_ALL_PACKAGES + HomeActivity（HOME 类别，clearTaskOnLaunch/resumeWhilePausing 等桌面属性）。
- `samples/maphost`：queries EMBED_MAP + MainActivity。

---

## ③ 权限与特殊能力、生命周期与保活

### 3.1 特殊能力清单（用在哪、怎么申请）
| 能力 | 使用点（文件:行） | 申请/授权路径 |
| --- | --- | --- |
| VpnService | `shared/.../network/CarPlayVpnService.kt:34`（`VpnService.prepare` :357）；有线链路必需（承载 IPv6 NCM） | `CarPlayHostActivity.kt:182-191` registerForActivityResult + `requestVpnConsent` :656（无线不走 VPN） |
| SYSTEM_ALERT_WINDOW | `CenterMapOverlay.kt:46`（canDrawOverlays）、`:99`（TYPE_APPLICATION_OVERLAY，API 26+） | 设置页 `DiPlayActivity.kt:222-227`（ACTION_MANAGE_OVERLAY_PERMISSION）；或 ADB `appops set … SYSTEM_ALERT_WINDOW allow`（`UsbPermissionSetup.kt:113`、`CarHotspotSetup.kt:21,25`） |
| WRITE_SETTINGS | `CarHotspotSetup.kt:24`（`Settings.System.canWrite`） | ADB appops WRITE_SETTINGS（`CarHotspotSetup.kt:41`）；`shared/.../CarHotspotTethering.kt:28` 同 |
| Usage Access | `DiLink51ClusterMonitor.kt:54`、`HomeScreenMonitor.kt:79`（UsageStatsManager，API 21+） | `UsbPermissionSetup.kt:21`（校验）+ `:112` `appops set GET_USAGE_STATS allow` |
| 无障碍服务 | `WheelKeyService.kt:40`（方向盘键→地图缩放/摇杆）；`UsbAutoConfirmService.kt:18`（自动点 USB 弹窗） | 用户在系统设置启用，或 ADB `settings put secure enabled_accessibility_services`（`UsbPermissionSetup.kt:114-125`，保留其它服务） |
| 通知 | `DiPlaySessionService.kt:25-34` 常驻通知；13+ 运行时申请 `DiPlayActivity.kt:2787`（带 notification_asked 标记） | — |
| 前台服务 | `DiPlaySessionService.kt:35-47`（SDK29+ 传 types；30+ 依权限加 microphone；按位置开关加 location）；启动点 `CarPlayHostActivity.kt:3942` `startForegroundService` | — |
| 蓝牙 | `CarPlayHostActivity.kt:690-704`、`DiPlayActivity.kt:2804`（31+ BLUETOOTH_CONNECT；≤30 用旧 BLUETOOTH） | — |
| 位置 | `CarPlayHostActivity.kt:641-654`（FINE+COARSE 多权限）、`DiPlayActivity.kt:153-160` | — |
| 麦克风 | `CarPlayHostActivity.kt:549-558`（RECORD_AUDIO） | — |
| ADB over network（BYD 专属提权） | `shared/.../adb/LocalAdb.kt:15-27`（127.0.0.1:5555，明文 ADB 协议，不支持 11+ TLS 无线调试）；用途：appops 授权、集群启动（AdbClusterRouter）、车辆数据读取、热点开关 | 车机"允许调试"对话框（`mayAsk=true` 才会弹；后台路径不会） |

### 3.2 生命周期与保活
- **屏幕常亮**：`CarPlayHostActivity.kt:514` `FLAG_KEEP_SCREEN_ON`；`AdbClusterActivity.kt:37` 同。
- **后台会话**：进程内单例 `CarPlayBackgroundSession`（`CarPlayHostActivity.kt:4755`，含 isOwner/store/clear 机制，Activity 销毁后 controller/sink 存续）；`DiPlaySessionService` 常驻通知让连接在别的车机 App 前台时保持；`onTaskRemoved`（`DiPlaySessionService.kt:50-55`）先 `BydNavigationOutputs.endNow()` 再 stopSelf（BYD recents 强杀 ~10ms 特性）；全部 START_NOT_STICKY。
- **开机自启**：`BootReceiver.kt:9-29`（BOOT_COMPLETED→ 若 `loadAutoStartOnBoot`（`AirPlayPersistence.kt:401`）则 NEW_TASK+CLEAR_TOP+SINGLE_TOP 启动 DiPlayActivity）；配合 SYSTEM_ALERT_WINDOW（别名 BOOT_LAUNCH）使用。
- **任务栈**：CarPlayHostActivity singleTask+affinity `.carplay`（`:50,53`）；DiPlayActivity singleTop（`:36`）；AdbClusterActivity affinity `.cluster`+excludeFromRecents+allowEmbedded（`:70-77`，供 `am start-activity --display` 嵌入，见 `AdbClusterRouter.kt:39-46`）。
- **配置变更自处理**：`configChanges=density|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode`，`onConfigurationChanged` 直接重排（`CarPlayHostActivity.kt:1178`、`DiPlayActivity.kt:221`）。
- **多窗口**：resizeableActivity=true；`onMultiWindowModeChanged`（`CarPlayHostActivity.kt:1190`）；`isInMultiWindowMode` 以 SDK>=N 保护（`:4027`）。
- **沉浸全屏**：`WindowInsetsControllerCompat`（`CarPlayHostActivity.kt:4646-4663`、`CarPlayVideoActivity.kt:83`、`DiPlayActivity.kt:186`）；`AdbClusterActivity.kt:39` 直接用 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`。
- **轮询**：`DiPlayActivity.kt:147-149` 1s tick；`CarPlayHostActivity.kt:735` pollConfiguration。
- **屏幕尺寸适配**：`isCompactLayout`（`DiPlayActivity.kt:291-295`，按窗口宽<550dp/高<450dp）。

---

## ④ 设置存储与多语言

### 4.1 存储机制
- **全部 SharedPreferences，无 DataStore / Room / SQLite**（全仓无 `androidx.datastore`、无 kotlinx.coroutines）。prefs 文件 10 个：
  | prefs 名 | 内容 | 位置 |
  | --- | --- | --- |
  | `xcertplay_airplay` | 主设置 + 配件身份/配对（60+ 键） | `AirPlayPersistence.kt:27` |
  | `diplay` | 手机地址/自动连接（DiPlayPreferences）、语言、通知已询问 | `DiPlayBootstrap.kt:52`、`DiPlayActivity.kt:2786` |
  | `carplay_picture` | 亮度/对比度等画质 | `CarPlayPicture.kt:45` |
  | `carplay_wifi_p2p` / `carplay_wifi_p2p_success` | P2P 组归属 / 成功配置记忆 | `WifiP2pGroupManager.kt:60`、`P2pConfigurationMemory.kt:8` |
  | `diplay_car_hotspot` | 车机热点开关记忆 | `CarHotspotSettings.kt:8` |
  | `diplay_oem_cluster` / `diplay_dilink3_cluster` | OEM 占用/D3 集群恢复日志 | `BydOemClusterNavi.kt:90`、`BydDiLink3ClusterOutput.kt:107` |
  | `byd_standalone_hud` / `hud_starter` | 独立 HUD 输出 / 启动桥 token | `BydStandaloneHudOutput.kt:16`、`BydStarterBridge.kt:30,41` |
- 敏感文件：MFi 身份拷入 `noBackupFilesDir/offline-mfi` 并 chmod（`DiPlayBootstrap.kt:17-39`）；备份规则整目录排除（`common/src/main/res/xml/data_extraction_rules.xml`），`allowBackup=false`（`mobile/src/main/AndroidManifest.xml:6`）。

### 4.2 多语言（7 语言同步机制）
- 语言全集：`AppLocale.ALL`（`AppLocale.kt:25`：system, en, zh, zh-TW, ar, ru, es, uk）。
- **Android 13+（SDK>=33）**：系统 `LocaleManager.applicationLocales` 为唯一真源（读 :33-47，写 :55-63，迁移旧 prefs 键 :66-79）；系统负责重建 Activity；`locales_config.xml` 注册 7 locale。
- **Android <13**：prefs `diplay/app_language` + `createConfigurationContext`（:81-87）+ `activity.recreate()`（:101）；每个 Activity `attachBaseContext` wrap（`DiPlayActivity.kt:175`、`CarPlayHostActivity.kt:131`），并在 onResume 检测语言变更后 recreate（`DiPlayActivity.kt:244`、`CarPlayHostActivity.kt:765-769`）。
- 资源覆盖：`common` 7 组 values*（条数：values 684 / ar·es·ru 623 / uk 635 / zh-rCN·zh-rTW 684）；`shared` 每语言 2 条（如 existing_wifi_title）；繁体为 0.2.13 新增（`docs/RELEASE-NOTES-0.2.13.md:55`）。
- 切换入口：设置页 `languageSettings()`（`DiPlayActivity.kt:3154`）→ `AppLocale.showPicker`（:89-106）。

---

## ⑤ BYD / DiLink 专属清单（CS11 适配需剥离或替换）

### 5.1 `shared/.../hud/` 40 文件（全量清单；本任务只做概述，不深入协议）
| # | 文件 | 作用概述（据文件头注释/类型） |
| --- | --- | --- |
| 1 | Byd13CatalogProbeMain.kt | shell uid 下 `app_process` 运行的一次性探测入口（反射 Byd13 目录），调试用 |
| 2 | BydAdbAccess.kt | 设置页显示的 ADB 链路状态（blocking，须离开主线程；mayAsk 控制是否弹批准框） |
| 3 | BydAdbShell.kt | 懒打开的 adb shell，后台读取用，从不请求批准 |
| 4 | BydAmapAdapter.kt | 车机 AMap 适配器：AUTONAVI_STANDARD_BROADCAST_SEND 广播 → 集群导航 |
| 5 | BydBattery.kt | 通过 adb shell（autoservice binder）读动力电池（电量/续航），供 iPhone vehicle status |
| 6 | BydCarPlayCall.kt | iAP2 CallStateUpdate(0x4155) 通话信息 → 仪表/车机通话卡 |
| 7 | BydClusterBridge.kt | 把 CarPlay 箭头/距离经车机原生通道发布到仪表 |
| 8 | BydClusterFrame.kt | AMap 广播词汇的仪表帧数据类 + Apple→AMap 图标映射 |
| 9 | BydClusterMapPause.kt | 需网络 ADB：会话期间让 iPhone 画集群地图并停掉原生地图 |
| 10 | BydClusterNaviMode.kt | 仪表导航模式（INSTRUMENT_NAVI_TYPE），adb shell 写入 |
| 11 | BydClusterSong.kt | iAP2 NowPlayingUpdate(0x5001) → 仪表音乐卡 |
| 12 | BydDiLink3ClusterOutput.kt | DiLink 3 集群输出（含独立恢复日志；设置关闭时也要能恢复中断输出） |
| 13 | BydFactoryNavigationOutput.kt | 用已安装的出厂 SDK 做临时 HUD 测试输出 |
| 14 | BydFactoryTurnCode.kt | 出厂导航 NEW_ICON → 仪表码映射（按实测固件） |
| 15 | BydHudBridge.kt | 把 CarPlay 箭头/距离/道路发布到 BYD 原生挡风 HUD（含 AIDL 回调保活） |
| 16 | BydHudPayload.kt | BYD HUD DirectTbtPayload 线格式（字段序对固件敏感，UTF-8 截断保护） |
| 17 | BydHudProtocol.kt | 共享线协议常量（与 DiAuto 副本保持一致） |
| 18 | BydHudRouteState.kt | 悬浮卡片跨会话保留最后指令（2 分钟过期窗口） |
| 19 | BydManeuverCodes.kt | Apple RouteGuidanceManeuverType → 高德 maneuver 码 |
| 20 | BydNavigationOutputs.kt | 手机控制消息与厂商服务之间的非阻塞边界（含开机恢复日志） |
| 21 | BydOemClusterHold.kt | 把车机自带地图挡在仪表盘之外（DiLink 4.0 单 activity） |
| 22 | BydOemClusterNavi.kt | 已验证私有显示路径的 stock-map hold（状态属于 worker） |
| 23 | BydOutputSettings.kt | BYD 输出总开关（HUD/仪表共用）与车辆数据开关 |
| 24 | BydParkedState.kt | 是否 P 挡（adb shell/autoservice binder） |
| 25 | BydStandaloneHudOutput.kt | 普通应用 IPC 到真实 stock receiver（无 shell/socket/授权） |
| 26 | BydStandaloneNavigationBridge.kt | 独立 HUD 导航桥（文本模式先清 maneuver 记录） |
| 27 | BydStandalonePackets.kt | 单行文本 HUD 包（道路名特性） |
| 28 | BydStandaloneSession.kt | 单行文本写入（如歌词行） |
| 29 | BydStarterBridge.kt | HUD 测试桥（token 校验；shell 预置） |
| 30 | BydVehicleCapabilityProbe.kt | 用户发起的只读车辆能力探测（controller-13 / DiLink 3） |
| 31 | BydVehicleFields.kt | 最近一次成功探测的持久化（含固件诊断元数据） |
| 32 | BydWheelSpeed.kt | 车轮速度/挡位（adb shell），供隧道内航位推算 |
| 33 | CarPlayCallKeyPolicy.kt | 通话时方向盘按键策略（按 BYD 自家 CarPlay 行为） |
| 34 | CarPlayCallWatchOwnership.kt | 通话写入所有权 token（防旧进程重置新通话） |
| 35 | CarPlayCallWrites.kt | 通话写入 + 尽力补偿（缺失功能跳过） |
| 36 | ClusterTurnGuidance.kt | 悬浮卡片下一转向指令（AMap NEW_ICON 词汇） |
| 37 | DiLink3ClusterModeSession.kt | DiLink 3 全/半屏模式序列（单串行 worker；先落恢复日志） |
| 38 | DiLink3ClusterRecoveryJournal.kt | 恢复日志（只认 commit 成功的写入） |
| 39 | NavigationOutputWorker.kt | 每个输出独立 worker（被阻塞的厂商服务不阻塞他人） |
| 40 | OemClusterHoldSession.kt | OEM 占用会话（单 worker；先持久化再写） |

### 5.2 其它 BYD/DiLink 相关文件
- `shared/.../adb/`：`LocalAdb.kt`（127.0.0.1:5555 明文 ADB）、`AdbKeys.kt`（adbkey 存储于 noBackupFilesDir）、`AdbPacket.kt`。
- `common` 含 byd/dilink 的 24 个 main 文件，按类分：
  - 车辆数据/设置：`BydVehicleSettingsBackend.kt`、`BydAdbSettingsUiTest`、`BydVehicleSettingsBackend`（+测试 BydSettingsReconnectTest / BydVehicleDataSettingsTest）；
  - 集群：`AdbClusterActivity.kt`、`AdbClusterRouter.kt`、`ClusterActivityState.kt`、`ClusterMapPresentation.kt`、`ClusterTurnCardView.kt`、`ClusterVideoTexture.kt`、`ClusterSafeAreaPreviewFrame.kt`、`DiLink4ClusterDisplay.kt`（虚拟屏名 `fission_bg_xdjaVirtualSurface`，1920x720 判定 :9-13）、`DiLink51ClusterLayout.kt`、`DiLink51ClusterMonitor.kt`；
  - 交互/其它：`WheelKeyService.kt`、`CarPlayCallKeys.kt`、`CarPlayMediaKeys.kt`、`HomeScreenMonitor.kt`、`NavigationWidget.kt`、`CarHotspotSetup.kt`、`LocalizedOptions.kt`、`CarPlayRotation.kt`、`CarPlayViewAreas.kt`、`CarPlayVideo.kt`、`CarPlayVideoActivity.kt`、`DiagnosticExportStore.kt`、`AirPlayPersistence.kt`。
- `mobile/src/debug` 3 文件 + debug 清单（BYDAUTO_INSTRUMENT_COMMON、com.byd.clusterdebug）；`shared` manifest 的 BYD 包名查询（见 2.2）。

### 5.3 剥离风险（重要）
BYD 代码不是"可选插件"，而是被主流程直接调用（无条件执行）：
- `DiPlaySessionService.kt:52` 在 `onTaskRemoved` 中无条件 `BydNavigationOutputs.endNow()`；
- `CarPlayHostActivity.kt:161-163` 构造 identification 时读 `BydOutputSettings.batteryToIphoneActive/chargingConnectors/wheelSpeedToIphoneActive`，`:791` 注册 `BydNavigationOutputs.setTurnOverlayListener`，`:1167` `setClusterMapShown`；
- `DiPlayActivity.kt:182` `BydNavigationOutputs.onAppOpened()`。
→ 适配 CS11 时需要先以接口/空实现替身解耦（而不是直接删除 hud 包），否则 common 无法编译。判定：`airplay/ transport/ iap2/ network/ media/ orchestration/ mfi/` 主体与 BYD 无关（07a/07b/07c 已证）；`hud/ adb/` 与 common 的 Cluster*/Byd* 属 BYD 层。

---

## ⑥ 依赖全表（minSdk 实测；来源 `gradle/libs.versions.toml`）

| 依赖 | 版本（行） | 用途/引用点 | 实测 minSdk | API19 |
| --- | --- | --- | --- | --- |
| AGP | 9.3.0（:2） | 构建；compileSdk `release(37)` | — | 见 ⑦ |
| Kotlin | 2.2.10（:6） | 语言 + Compose 插件 | —（stdlib 字节码 Java8） | 一般可用，待核实 |
| androidx.core:core-ktx | 1.19.0（:3,18） | 全局基础库 | **23** | ✗ |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.6.1（:4,19） | 生命周期 | **14** | ✓（本表唯一） |
| androidx.activity:activity-compose | 1.8.0（:5,20） | MainActivity | **21** | ✗ |
| androidx.activity:activity | 1.8.0（:21） | registerForActivityResult 等 | **14** | ✓ |
| Compose BOM 2026.02.01 → ui / ui-graphics / ui-tooling-preview | 1.10.4（:7,22-26） | 自检页 | **23** | ✗ |
| Compose material3 | 1.4.0（:27） | 自检页 | **21** | ✗（foundation/runtime 1.10.4 同 23） |
| androidx.appcompat | 1.8.0（:8,28） | 仅 automotive 使用（仓库无代码 import） | **23** | ✗ |
| androidx.car.app:app-projected | 1.4.0（:9,29） | shared `MyCarAppService` | **21** | ✗ |
| androidx.car.app:app-automotive | 1.7.0（:10,30） | 仅 automotive | **29** | ✗ |
| androidx.car.app:app | 1.4.0（:11,31） | shared 直接依赖 | **21** | ✗ |
| androidx.media3（exoplayer / exoplayer-hls / ui） | 1.11.1（:15,35-37） | 仅 2 文件：`CarPlayVideoActivity.kt:27-43`、`IphoneResolvingDataSource.kt:6-10` | **23** | ✗ |
| org.bouncycastle:bcprov-jdk18on | 1.79（:13,33） | shared（MFi/TLS 证书） | jar；类文件 Java8(52)，含 META-INF/versions/11（MRJAR） | 风险见 ⑧ |
| org.jmdns:jmdns | 3.6.3（:14,34） | Bonjour（common 亦 testImplementation） | jar；类文件 Java8，但引用 java.util.function/stream（API24+） | 需 desugaring |
| junit 4.13.2 / robolectric 4.17 / mockito 5.20.0 | （common build） | 仅 JVM 单测 | 不随 APK | — |
| NDK 28.2.13676358 | `shared/build.gradle:24`、`docs/BUILD.md:3` | 2 个 so：xcertplay_i2c、local_hotspot_radio（`shared/src/main/jni/Android.mk`） | **r27+ 最低 API 21** | ✗ |

已回退候选（本次实测，供 ⑦ 用）：core 1.13.1 = **19** ✓（1.15.0 = 21）；appcompat 1.6.1 = **14** ✓；activity 1.7.2 = **14** ✓；ExoPlayer2 2.19.1 = **16** ✓（media3 替代）。

---

## ⑦ API 19 阻塞清单（逐条）

### 7.1 构建/工具链级（不解决则连 APK 都产不出）
1. **依赖 manifest 合并下限**：Manifest merger 规则为"库 minSdk 高于 app 即失败"。实测清单：core-ktx 1.19→23、appcompat 1.8→23、compose ui 1.10.4→23、media3 1.11.1→23、car-app(app/projected)→21、car-automotive 1.7→29。改 minSdk 19 会直接报 `uses-sdk:minSdkVersion 19 cannot be smaller than version 23 …`。出路：把上述依赖降级到支持 19 的版本（core ≤1.13.x 实测 19；appcompat 1.6.1 实测 14；activity 1.7.2 实测 14；media3 整体替换）；compose/material3/car-app 直接删或用旧版（Compose 基线 21，删更实际——全仓只有 MainActivity+主题 4 文件）。
2. **NDK r28 最低 API 21**（官方 changelog：r27 起 KitKat 19/20 不再支持）：`shared/build.gradle:18` `APP_PLATFORM=android-28`，需改用 NDK r26 及以下并将 APP_PLATFORM 降到 android-19，或去掉 JNI（两个 so 的 C 文件仅 `linux_i2c_jni.c`、`local_hotspot_radio.c`）。abiFilters 已含 armeabi-v7a（CS11 是 32 位 ARM，需保留）。
3. **multidex**：AGP 要求 minSdk ≤20 时必须 `multiDexEnabled=true`，且 API<21 需运行时 `MultiDex.install`。当前无 Application 类、无 multidex 依赖 → 需新增（自定义 Application.attachBaseContext 或 androidx.multidex）。
4. **未启用 core library desugaring**：全仓构建脚本无 `isCoreLibraryDesugaringEnabled` / `desugar_jdk_libs`，代码里的 `java.time`、`java.util.Base64`、`CompletableFuture` 等在 19 上全部无兜底（见 7.2）。
5. **AGP 9.3 / R8 对 minSdk 19 的支持状态**：AGP 9.0 发布说明未给 minSdk 下限，但明确"接口 default 方法 + minSdk<24"的库合并玩法不再支持；R8/D8 是否仍支持 min-api 19 未见权威说明 → **待核实**。`docs/BUILD.md:3` 要求 JDK 25，若为 19 降级构建链需同步评估。
6. **lint NewApi 扫描**：把 minSdk 临时降到 19 跑 `:common:lintDebug` 可一次性拿到全部 API>19 引用（本清单 7.2 为静态等价物）。

### 7.2 代码级 API>19 直用（数量级：按 grep main 源码；不含 test）
| 模式 | 数量/文件 | 最低 API | 代表位置 |
| --- | --- | --- | --- |
| `getSystemService(Class<T>)` | **39 处**（common 21 / shared 18） | 23 | `DiPlaySessionService.kt:25`、`HomeScreenMonitor.kt:79`、`ClusterMapPresentation.kt`、`CarPlayController.kt`、`WifiP2pGroupManager.kt`（→ 需改 `ContextCompat.getSystemService` 或 String 版） |
| `java.util.Base64` | 8 文件 | 26 | `AdbKeys.kt`、`VideoInCar.kt`、`BydCarPlayCall.kt:364`、`BydClusterSong.kt:328`、`RemoteMfiAuthenticationClient.kt`、`LockdownPairRecord/ LockdownPlistChannel/ LockdownTlsEngineFactory` |
| `java.time`（Instant/ZoneOffset） | 1 文件 | 26 | `Iap2LocationClient.kt:122-123` |
| `CompletableFuture` | 1 文件 | 24 | `CarPlayVideo.kt:15,167,178` |
| `Map.computeIfAbsent` / `removeIf` | 2 文件 | 24 | `AndroidMediaSink.kt:295,371`；`VideoDecodeQueue.kt:47` |
| `AudioTrack.Builder` / `AudioRecord.Builder` / `AudioAttributes.Builder` | 4 文件 | 23/21 | `AndroidMediaSink.kt:978,999,1059-1084`、`MicrophoneUplink.kt:80`、`AudioChannelPreview.kt:44-46`、`CarPlayMediaKeys.kt:167` |
| `NotificationChannel` / `Notification.Builder(ctx,id)` / `createNotificationChannel` | 1 文件（无 SDK 分支，**必崩**） | 26 | `DiPlaySessionService.kt:25-29` |
| `startForegroundService` | 1 处 | 26 | `CarPlayHostActivity.kt:3942` |
| `startForeground(id,notif,type)` + `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` | 1 文件（有 SDK>=29 分支，但低版本分支先过 26 的 Channel 构造） | 29 | `DiPlaySessionService.kt:35-47` |
| `Context.noBackupFilesDir` | 3 文件 | 21 | `DiPlayBootstrap.kt:17,19`、`AdbKeys.kt:35`、`CarPlayController.kt:760` |
| `Settings.canDrawOverlays` / `Settings.System.canWrite` / `ACTION_MANAGE_OVERLAY_PERMISSION` | 5 文件 | 23 | `CarHotspotSetup.kt:24-25`、`CenterMapOverlay.kt:46`、`DiPlayActivity.kt:223`、`UsbPermissionSetup.kt:22`、`CarHotspotTethering.kt:28` |
| `TYPE_APPLICATION_OVERLAY` | 1 文件 | 26（19 需 TYPE_PHONE） | `CenterMapOverlay.kt:99` |
| `UsageStatsManager` | 2 文件 | 21 | `DiLink51ClusterMonitor.kt:54`、`HomeScreenMonitor.kt:79` |
| `checkSelfPermission` / `requestPermissions` | 11 处 / 5 文件 | 23 | `CarPlayHostActivity.kt:550`、`DiPlayActivity.kt:2787,2804` 等（4.4 是安装期权限，这些可退化为直接通过） |
| 无线栈：`startLocalOnlyHotspot`(26)、`TetheringManager`(30)、`setGroupOperatingFrequency`(29) | 3 文件 | 26/30/29 | `LocalOnlyHotspotManager.kt`、`ManualHotspotInterfaces.kt`、`WifiP2pGroupManager.kt` |
| 已正确保护（不计阻塞，但需 lint 豁免）：LocaleManager(33+)、registerReceiver 带 flag(33+)、isInMultiWindowMode(N+)、dynamicColor(S+)、FGS types(29+)、ProcessExitDiagnostics(R+ 返回 UNSUPPORTED) | — | — | `AppLocale.kt:33-79`、`CarPlayCallKeys.kt:51`、`CarPlayHostActivity.kt:4027`、`ui/theme/Theme.kt:44`、`ProcessExitDiagnostics.kt:22` |

另：`PendingIntent.FLAG_IMMUTABLE`（`DiPlaySessionService.kt:27-28`）与 `IntentCompat.getParcelableExtra`（`CarPlayHostActivity.kt:727`）在 19 上运行时无害（未知 flag 被忽略/兼容层），不计阻塞，但依赖 androidx core（见降级）。

### 7.3 平台行为级
- **权限模型**：4.4 无运行时权限与 appops 细分（POST_NOTIFICATIONS / NEARBY_WIFI_DEVICES / BLUETOOTH_CONNECT 等不存在）→ 3.1 的申请路径大段作废，建议整体走"安装期声明 + 直接调用"分支。
- **前台服务/通知**：4.4 无 startForegroundService、无渠道、无类型；`startForeground` 需降级为两参调用并去掉 Channel 构造。
- **悬浮窗**：无 canDrawOverlays/ACTION_MANAGE_OVERLAY_PERMISSION；19 上用 `TYPE_PHONE` + 用户手动或系统预置授权（CS11 无 root → 走不通 ADB appops 时此功能可能整体不可用）。
- **多窗口/PIP/allowEmbedded/resizeableActivity/targetCell* 等属性**：全部被忽略（不崩），但 AdbClusterActivity 的嵌入启动（`am start-activity --display`）依赖 Android 8+/BYD 特性，CS11 上不可用。
- **无线链路**：Wi-Fi Direct 基础 API 从 API 14 起可用，但 `LocalOnlyHotspot`、`TetheringManager`、信道固定等不可用 → 无线 CarPlay 只剩 P2P/手动热点/同 LAN 路径，且 4.4 Wi-Fi P2P 固件实现差异大（**待实测**）。
- **BYD 集成层**：依赖网络 ADB（5555）与 BYD 私有服务/固件，CS11 上大概率整体失效（5.3 解耦后按需启用）。

### 7.4 缓解路径（不改仓库，仅方案）
- **方案 A（推荐）**：UI 全 View（现状 96%）、删 Compose（4 文件）与 car-app；core→1.13.x、appcompat→1.6.1、activity→1.7.2、lifecycle 2.6.1 保留；media3→ExoPlayer2.19.1（实测 minSdk 16）或 `MediaPlayer`；NDK 降 r26 + APP_PLATFORM=android-19；开 multidex + 加 Application；逐个 7.2 表替换（Base64→android.util.Base64；java.time→SimpleDateFormat/Calendar；CompletableFuture→自建回调/Future；computeIfAbsent→手写 get/put；NotificationChannel→分支；getSystemService(Class)→compat）。
- **方案 B（补丁式）**：保留较新库 + 启用 core library desugaring（jdk11 nio 版）兜底 java.time/stream/Base64(待核实覆盖)，其余同 A；仍需处理 23+ 的 getSystemService(Class) 与 NotificationChannel。
- **方案 C（不推荐）**：`tools:overrideLibrary` 强制合并高 minSdk 库 → 运行时 NoSuchMethodError/资源缺失风险高，仅限无法降级的单库。

---

## ⑧ 未决问题（需实测/查证）
1. 各 androidx 库"最后支持 minSdk 19"的精确版本（core 1.13.1 已实测 19、1.15.0 已实测 21，1.14.x 未测）；appcompat/activity 的对应窗口已有 1.6.1/1.7.2 备选，但需核对 API 兼容性差异。**待核实**
2. AGP 9.3 / R8 是否对 minSdk 19 有硬性限制或需关闭新特性 → **待核实**；如不可行需降 AGP 版本，引发 compileSdk/DSL 连锁调整。
3. core library desugaring（desugar_jdk_libs）对 `java.util.Base64`、`CompletableFuture`、`Map` 默认方法的覆盖边界 → **待核实**（决定 7.2 表里哪些能不手改）。
4. bcprov-jdk18on 1.79 在 API 19 的运行兼容性（MRJAR + 官方建议 Android 用 jdk15to18）→ **待核实**。
5. jmdns 3.6.3 依赖 java.util.function/stream：降级 3.5.x 或靠 desugaring → **待核实**。
6. CS11 车机是否有可用 ADB（127.0.0.1:5555 / 已授权）与 appops 支持——决定 3.1 中"ADB 提权"路径与全部 BYD 输出能否迁移；无 ADB 时悬浮窗/写入设置只能人工授权。
7. Android 4.4.3 上 Wi-Fi P2P/热点实际可用性（CS11 ROM 行为）→ 需真机实测。
8. 1920x720 之外的适配遗留：CS11 是 1280x720，`DiLink4ClusterDisplay`（1920x720 判定）、`DiLink51ClusterLayout`、`CarPlayDock/ViewAreas` 的尺寸假设需单独复核（本次仅截图级确认，未展开）。
9. `hud/` 40 文件与 common 的解耦改造工作量（5.3 的依赖点至少 6 处）→ 需在 adapter 层设计后估算。
