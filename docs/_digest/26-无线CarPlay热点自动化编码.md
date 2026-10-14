# 26 · 无线 CarPlay 热点自动化（CS11 / Android 4.4）编码落地

> 日期：2026-10-07 · 工作区 `CarPlay-CS11` @ 分支 `cs11-api19` · 基线 HEAD `d8611cd`（本次改动尚未提交）
> 依据：`HANDOFF.md` §3 施工计划（8 步）· 机制来源：doc 24（EasyPlay 领克版逆向）

## 0. TL;DR

- **HANDOFF §3 步骤 1~7 全部编码完成**：新增 `LegacySoftApControl.kt`（4.4 隐藏 API 反射开热点）+ 其纯函数单测；改 4 个主源码文件；同步 4 个既有测试（反射调用签名）；顺带修 1 处 HEAD 既存编译错误。
- **步骤 8 构建通过**：`mobile-debug.apk` = 14,545,834 B，SHA-256 `F3478671EE76E6FA052DE284785752F3E4C7603571933F0A0C4B03568330D22C`。
- **新增纯 JUnit 测试 12/12 全绿**；`shared` / `common` 两个测试源集编译通过。
- **未跑通既有 Robolectric 回归**（`CarHotspotTetheringTest` / `CarHotspotSetupTest` 等）：本机 `conscrypt` 原生库加载失败，**属 HEAD 既存环境问题、与本次改动无关**。见 §4。
- **上车验证未做**（需真实 CS11）：§5 的 logcat 链路待观察。

## 1. 交付物清单（文件级）

| 文件 | 类型 | 说明 |
|---|---|---|
| `shared/src/main/java/com/shilapi/xcertplay/network/LegacySoftApControl.kt` | **新增** | 4.4 反射开热点：`getWifiApConfiguration` → `setWifiApEnabled` → `getWifiApState`；AP/Wi-Fi 客户端互斥与失败恢复；`object` 且 public（供 `common` 调用） |
| `shared/src/test/java/com/shilapi/xcertplay/network/LegacySoftApControlTest.kt` | **新增** | 12 例纯 JUnit（无 Robolectric）：`storedSecurity` 映射 + `usable` 规则 |
| `shared/.../network/CarHotspotTethering.kt` | 改 | fallback 链改为 **legacy 优先、adb 兜底**；最终未 READY 时恢复被关的 Wi-Fi 客户端 |
| `shared/.../network/CarHotspotSettings.kt` | 改 | `visible()` 加第三参 `legacySupported`（默认 `false`，既有调用不受影响） |
| `common/.../DiPlayActivity.kt` | 改 | CS11 放开热点开关可见性；`!byd` 时隐藏 ADB 状态与授权按钮；`renderBydAdbControls` 改 4 参；启动资格传 legacy 支持位 |
| `shared/.../CarHotspotSetup.kt` | 改 | `shouldStartOnLaunch` 加 `allowCredentiallessStart`（默认 `false`，保留原两参行为） |
| `shared/.../orchestration/CarPlayController.kt` | 改 | BT 尽力开启（`requestWirelessBluetoothOn`，≤6s 可中断）；MANUAL 分支**采用实读原车热点配置** |
| 4 个既有测试 | 改 | `CarHotspotSwitchTest` / `BydAdbSettingsUiTest` / `BydVehicleDataSettingsTest` / `BydSettingsReconnectTest`：反射调用同步 4 参签名（传 `byd=true, legacy=false` 复现原语义） |
| `shared/src/test/.../media/AudioTrackAttributesCompatibilityTest.kt` | 改 | 修 HEAD 既存编译错误：`audioTrackAttributesForFocus` → `AudioApiBridge.attributesForFocus` |

## 2. 关键设计与对 HANDOFF §4 草稿的偏差

1. **`km.isEmpty` → `km.cardinality() == 0`**（[LegacySoftApControl.kt](file:///d:/Development/DiAuto-DiPlay/CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/network/LegacySoftApControl.kt#L73-L81)）
   规避 `java.util.BitSet.isEmpty` 在 API19 上的可用性风险；语义与 EasyPlay 的 `isEmpty()` 等价。
2. **新增 SSID `unquote`**：EasyPlay 直接用 `config.SSID`（带引号），**但我们的 `ManualHotspotManager` 走的是另一条路**——它在 [ManualHotspotManager.kt](file:///d:/Development/DiAuto-DiPlay/CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt#L293) 读取原车配置时 `unquote(SSID)`，并在 L76 用**去引号后**的 SSID 与预期值比对。若我们不 unquote，采用实读配置后 L76 必然抛 `HOTSPOT_CONFIGURATION`。**此偏差是必须的。**
3. **口令（`preSharedKey`）不 unquote**：与 EasyPlay 口径一致（`storedConfiguration` 直接取 `config.preSharedKey`），即视为裸口令/裸 PSK。
4. **MANUAL 分支口令回落写法**（[CarPlayController.kt](file:///d:/Development/DiAuto-DiPlay/CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L2142-L2156)）
   用 `if (adoptedManual != null) adoptedManual.password.orEmpty() else config.manualHotspotPassphrase.orEmpty()`，**而不是** `adoptedManual?.password ?: config…`——后者在 OPEN 热点（password=null）会错误回落到旧密码，而 `ManualHotspotManager` 对 OPEN 要求口令为空。
5. **降级原则**：legacy 链只在 `supported()` 为真时启用；现代设备（平板 A15）`supported()==false`，行为与改动前完全一致，**无回归面**。

## 3. 构建与测试结果

- 构建（PowerShell 等价，环境变量用 `D:/` 形式）：
  ```
  DIPLAY_AUTH_ASSETS_DIR="D:/Development/DiAuto-DiPlay/_artifacts/apk_assets/assets"
  ./gradlew.bat :mobile:assembleStandaloneDebug
  ```
  结果 **BUILD SUCCESSFUL**（含 `verifyStandaloneAuthentication` / `rejectBundledCredentials` 门控通过），产物 `mobile/build/outputs/apk/debug/mobile-debug.apk`，14,545,834 B，SHA-256 `F3478671…30D22C`。
- 测试源集编译：`:shared:compileDebugUnitTestKotlin`、`:common:compileDebugUnitTestKotlin` 均 **BUILD SUCCESSFUL**（说明 4 个既有测试的反射签名同步正确）。
- 新增单测：`LegacySoftApControlTest` **12/12 绿**（纯 JUnit，未受 §4 环境问题影响）。

## 4. 已知阻塞：shared 的 Robolectric 用例无法在本机运行（HEAD 既存）

- **现象**：`:shared:testDebugUnitTest` 中所有 Robolectric 用例失败，栈为
  `AndroidTestEnvironment.createConscryptProvider` → `org.conscrypt.OpenSSLProvider.<init>` → `NativeCrypto.<clinit>` → `NativeCryptoJni.init` → `System.loadLibrary("conscrypt_jni")` → `UnsatisfiedLinkError: no conscrypt_jni in java.library.path`。
- **根因**：`shared/build.gradle` 直接 `implementation libs.conscrypt`（`conscrypt-android:2.5.2`，CS11 打包 TLS1.2/1.3 用），而 Robolectric 4.17 传递引入 `conscrypt-openjdk-uber:2.6.2`。二者同提供 `org.conscrypt.*`，**AAR 版胜出**，于是到宿主 JVM 上加载 Android 的 `.so` —— 宿主并无该库。
- **判定为既存问题**：未改动的 `CarHotspotStatusTest` 同样失败；且 HEAD 上 `shared` 测试源集原本**无法编译**（§1 末行那处悬空引用），即这批 Robolectric 用例在本分支从未真正跑过。
- **候选修法（本次未实施，未改 build 文件）**：仅测试作用域排除 android 版，让 uber 版生效，例如
  ```gradle
  configurations.configureEach {
      if (name.startsWith("test")) exclude group: "org.conscrypt", module: "conscrypt-android"
  }
  testImplementation "org.conscrypt:conscrypt-openjdk-uber:2.6.2"
  ```
  生产依赖与打包产物**零影响**；改后需重跑 §3 的回归矩阵。

## 5. 待验证：CS11 上车链路（未执行）

按 HANDOFF §5，装上 §3 产物后：

1. app：无线 → **MANUAL** 模式；打开"自动开启车机热点"。
2. logcat 过滤 `DiPlay-LegacyAp` / `DiPlay-Hotspot` / `xcertplay`，期待链：
   `legacy AP: started saved hotspot ssid=…` → AP 状态 13（`CarHotspotStatus`）→ `manual hotspot adopts live AP configuration …` → RFCOMM → 0x5703 → iPhone 入热点 → 首帧。
3. iPhone 先与车机蓝牙配对（无感连接优先）。
4. 已知待观察点：4.4 读不到 channel → 0x5703 报 channel 0（auto，属正常）。

## 6. 遗留与下一步

- [ ] 修复 §4 的 Robolectric 环境（test-only 类路径），跑通 `CarHotspotTetheringTest` / `CarHotspotSetupTest` 回归。
- [ ] CS11 上车验证（§5）。
- [ ] **v2**：原车热点配置不可用（读出 null / WEP）时，用用户设置里的 `manualHotspotSsid/passphrase` 兜底建配置（等价 EasyPlay `newConfiguration()`，本次 v1 明确失败并留日志）。
- [ ] 本次改动尚未提交（工作区 dirty，基线 `d8611cd`）。
