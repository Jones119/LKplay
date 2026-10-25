# API 19 审计台账（2026-10-26）

本台账记录 `minSdk = targetSdk = 19` 收敛后对平台 API 使用的逐项核查。
目标设备：CS11（Android 4.4.3 / API 19 / Dalvik / 32 位 ARM）。

## 方法

| 范围 | 手段 | 结果 |
| --- | --- | --- |
| `android.*` 平台 API | lint `NewApi`（minSdk=19，覆盖 shared/common/mobile 全部主源码），并已改为阻断 | shared 0、common 0、mobile 0 |
| `java.*` 库 API | `ForbiddenLibraryApiTest` 扫描 `.kt` 与 `.java`（含 concentus 的 124 个文件），以 desugar 白名单为界 | 通过 |
| 版本注解 | `grep @RequiresApi/@TargetApi` | 0 |
| 版本分支 | `grep Build.VERSION` | 仅剩兼容层、车机专用判断与诊断字段（见下） |
| 反射 | 人工清单 | 见下 |
| 清单属性 | `Api19ManifestTest` | 通过 |
| native | NDK r25c（r26 下限 21）、CMake 继承 minSdk 19、`src/main/jni/Application.mk` 指向 android-19 | 通过 |

## 保留的版本判断

| 位置 | 内容 | 理由 |
| --- | --- | --- |
| `compat/Cs11Compat.kt` | `systemService`、`inputBufferCompat`、`getDrawableCompat`、`hasPermissionCompat` 等 | 4.4 走 else 分支；封装让调用方不直接触碰 API 21+ 符号 |
| `network/LegacySoftApControl.kt` | `SDK_INT in 19..20` | 车机专用：4.4 的 softAP 需要先关掉 Wi-Fi 客户端 |
| `AndroidMediaSink` / `NcmUsbBridge` / `IphoneUsbHost` 的日志 | `api=${Build.VERSION.SDK_INT}` | 诊断文本，没有任何分支 |
| `DiagnosticLog` / `DiPlayActivity` | `sdkInt`、`Build.VERSION.RELEASE` | 报告元数据 |

## 反射清单

| 位置 | 目标 API | 4.4 可用性 | 失败回退 |
| --- | --- | --- | --- |
| 多处 `SystemProperties.get` | 隐藏 API（API 1 起） | 是 | `runCatching` → 默认值 |
| `VendorClusterBridge` | `com.ecarx.xui.*` 仪表类 | 车机厂商类，随 ROM 提供 | 类缺失 → 不启用该能力 |
| `CarHotspotStatus` / `LegacySoftApControl` / `ManualHotspotInterfaces` | `getWifiApState`、`getWifiApConfiguration`、`getTetheredIfaces` 等隐藏 API | 是（4.4 上就是这些 API 的出处） | `runCatching` → 能力不可用 |
| `NcmLegacyPolicy` | `SystemProperties.get` | 是 | 默认 AUTO |

## 清单与资源

- 已删除 `common/src/main/res/values-v21/` 与 `shared/src/main/res/mipmap-anydpi-v26/`。
- 三份 manifest 已移除 API 21+ 属性与权限（`resizeableActivity`、`foregroundServiceType`、
  `POST_NOTIFICATIONS`、`NEARBY_WIFI_DEVICES`、`BLUETOOTH_CONNECT`、FGS 权限、`queries`、
  `usesCleartextTraffic`、`dataExtractionRules`、`localeConfig`、`roundIcon`）。
- APK 验证：`targetSdkVersion:'19'`、`native-code: 'armeabi-v7a'`。

## 依赖库带入、无法由本仓库删除的项

| 项 | 来源 | API 19 影响 |
| --- | --- | --- |
| `res/drawable-anydpi-v21/*`（35 个） | androidx.core / appcompat | 资源限定符按版本选择，4.4 不会加载 |
| `com.shihab.diplay.hudtest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core 的 manifest 合并 | 仅为 API 33+ 的 receiver 标记服务；targetSdk 19 下无行为影响 |

## 测试改造

- 删除仅覆盖已移除路径的测试：`MediaSessionBridgeTest`、`P2pConfigBuildDiagnosticsTest`、
  `WifiScanPause*Test`、`ExistingWifiManagerTest`、`ProcessExitDiagnosticsTest`、
  `DiagnosticExportFallbackTest`、`CarHotspotSwitchTest`、`CarHotspotAdbGrantTest`、
  `CarHotspotFallbackTest`、`WifiP2pGroupManagerTest`、`CarPlayHostResolutionTest`。
- 改写为车机语义：`AudioStreamFallbackTest`、`CarPlayWiredTransportDecisionTest`、
  `UsbReadQueueCompatibilityTest`（首次读取固定 16 KiB 上限）、`AppLocaleTest`、
  `CarPlayMediaCallbackTest`、`DiagnosticExportStoreTest`。
- 新增防回归：`Api19ManifestTest`；`ForbiddenLibraryApiTest` 扫描范围扩展到 `.java` 与
  `src/main/kotlin`。
- USB 测试 shadow 补齐 `queue(ByteBuffer, Int)` 与无参 `requestWait()`，与车机实际调用的重载一致。

## 已知限制

- lint 中与本任务无关的既有告警（`MissingTranslation`、`StringFormat*`、`RestrictedApi`、
  `SuspiciousIndentation`、`PropertyEscape`、`MissingPermission`、`ExpiredTargetSdkVersion`、
  `ForegroundServiceType`）被显式 disable；`NewApi` 保持启用并阻断构建。
- 本台账只覆盖静态与构建级证据；连接、音频、视频与热点行为仍需在 CS11 实机上复验。
