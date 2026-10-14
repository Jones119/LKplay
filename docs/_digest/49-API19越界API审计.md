# 49 · API19 越界 API 审计（修复落地）

日期：2026-10-10 ｜ 范围：`D:\Development\DiAuto-DiPlay\CarPlay-CS11`，分支 `cs11-api19`，起点 **HEAD = `f77c179c`**
修复分支：**`api19-fixes`**（独立 worktree `D:\Development\DiAuto-DiPlay\_worktrees\api19-fixes`），提交 **`3037e56e`**（单个提交，基于 `f77c179c`）
输入：`_artifacts/api19-lint-audit/REPORT.md` + `lint-{shared,common,mobile}.{xml,txt}`（同一目录）
目标运行时：**Android 4.4.3 / API 19 / Dalvik**（领克 CS11 车机）；同一 APK 也跑 Android 15 平板，所以更高 API 的代码必须存在但要隔离。

> 本文是**只读审计（REPORT.md）的修复篇**，也是这类问题的长期记录。审计本身的方法论、lint 计数与"已核对且干净"清单见 `REPORT.md`；本文只记"改了什么、为什么这样改、还差什么实机证据"。

---

## 0. 一句话结论

审计的 7 项（F1–F7）里，**6 项已修**（F1/F2/F3/F4/F5/F7），**F6 按要求只报告不改**。其中 **F1 是车机上必崩的确定性缺陷**（`Math.floorDiv` 是 API 24 且 desugar 不覆盖，抛的是 `Error`），**F4 是静默功能丢失**（中央屏地图卡在车机上永远不出现），**F5 是死判断把整条 API19 NCM 路径变成不可达代码**。同时补上了审计发现的**方法论盲区**（lint 不报 `java.*` 库 API）的守卫。

---

## 1. 结论基线（审计复核，未改动）

| 项 | 值 |
|---|---|
| minSdk / targetSdk / compileSdk | `19` / `37` / `release(37)` |
| multidex | `:mobile`/`:shared`/`:common` 均 `multiDexEnabled = true` |
| core library desugaring | **已开启**（`com.android.tools:desugar_jdk_libs:2.1.4`，Java 11 源/目标） |
| D8 实际改写实证 | APK dex 字符串表有 `j$/util/Base64`(9)、`j$/time/Instant`(8)、`j$/util/Optional`(18)、`j$/util/stream/Stream`(114)、`j$/util/function`(59)；`CompletableFuture` = 0 |
| **不被 desugar 覆盖** | `java.lang.Math`（→ F1）、`java.nio.file`、`CompletableFuture` |

---

## 2. 审计发现与处置一览

| # | 严重度 | 用了什么 | 原位置 | 处置 | 落点 |
|---|---|---|---|---|---|
| **F1** | 真崩（确定性） | `Math.floorDiv/floorMod(long,long)`（API 24，desugar 不覆盖） | `airplay/NtpClock.kt:221-222` | **已修** | `NtpClock.kt:221-222`（调用点）、`:241` `floorDivLong`、`:254` `floorModLong` |
| **F2** | 高 | `MediaSession`/`MediaMetadata`/`PlaybackState`/`AudioAttributes`/`AudioFocusRequest`（21/26）在主 object 的方法体里 | `CarPlayMediaKeys.kt:56/181-182/195-205/215/281-284/307-319/340-357/414` | **已修**（搬进版本隔离持有者） | 新 `MediaSessionBridge.kt`（`Api21MediaSession:160`、`Api26AudioFocus:143`、`MediaKeysPlatform:78`）；`CarPlayMediaKeys.kt:66/176/184/191/282/294/307` |
| **F3a** | 中 | `android.app.LocaleManager`（API 33）在 `attachBaseContext` 路径上 | `AppLocale.kt:35/57/72` | **已修** + **附带发现一处新的 lint 盲区**（见 §4.4） | 新 `Api33Locale.kt:19`（`supported:23`、`preferredLanguageTag:42`、`saveLanguage:58`、`migrateLegacyPreferenceOnce:68`）；`AppLocale.kt:31/51/63` |
| **F3b** | 中 | `NcmFunctionDiscovery.kt:94-106` 的反射块在 4.4 上**永远不可能成功** | 同上 | **已删**（死代码+误导） | `NcmFunctionDiscovery.kt:53/60` |
| **F3c** | 中 | `IphoneCarPlayConfiguration.kt:37/38/49/50/59/120/125` 的 `UsbConfiguration`（整类 API 21） | 同上 | **已隔离**（不是"不可达所以不管"） | 新 `Api21Configuration.kt:20`（`find:35`、`describe:49`、`usbMuxInterfaceOf:60`）；`IphoneCarPlayConfiguration.kt:32/42` |
| **F4** | 中（静默无功能） | `TYPE_APPLICATION_OVERLAY`（API 26；4.4 需 `TYPE_PHONE`） | `CenterMapOverlay.kt:103` | **已修** | `CenterMapOverlay.kt:62`（`overlayTypeFor`）、`:122`（调用点） |
| **F5** | 中（死代码 + 诊断静音） | 版本判断本身失效（条件退化为 `SDK_INT < 21`） | `CarPlayController.kt:1613-1626` | **已修**（结论：API19 原始 NCM 路径已被内核网卡取代 → 删死分支） | `CarPlayController.kt:1621`（调用）、`:1640`（`RawNcmDecision`）、`:1648`（`rawNcmDecision`）、`:1663`（`openNcm`） |
| **F6** | 低（语义错） | `Cs11Compat.systemService()` 声明返回 `T`（缺服务时抛异常），调用侧 `?.`/`if (x == null)` 成死分支 | 见 §6 清单 | **按要求未改**（只报告） | — |
| **F7** | 低 | `MediaCodecList(REGULAR_CODECS).codecInfos`（21+）守卫在别的方法 | `OpusEncoderSupport.kt:53` | **已修** | `OpusEncoderSupport.kt:49`（调用）、`:64`（`Api21CodecList`） |
| **盲区** | — | lint 不报 `java.*` 库 API（三份报告 `java.` 命中 0） | — | **已加守卫** | 新 `shared/src/test/.../ForbiddenLibraryApiTest.kt`；lint 配置旁注（`shared/build.gradle`、`common/build.gradle.kts`） |

### 2.1 与 `07d §7.2/§7.3` 的对账

`docs/_digest/07d-UI生命周期与API19阻塞清单.md` 的 7.2 表把 `TYPE_APPLICATION_OVERLAY` 记为 **"1 文件｜26（19 需 TYPE_PHONE）｜`CenterMapOverlay.kt:99`"**，7.3 又写了"悬浮窗：19 上用 `TYPE_PHONE` + 用户手动或系统预置授权"。
→ **本次 F4 正是按这份文档的预期修的**：`overlayTypeFor()` 在 <26 返回 `TYPE_PHONE`(2002)，≥26 返回 `TYPE_APPLICATION_OVERLAY`(2038)。授权侧的 `permitted()`（`CenterMapOverlay.kt:50`，`<23` 直接 true）本来就已正确，未改。

7.2 表里其余条目在审计报告 §2 已被逐条对账为"已解决/已守卫"，本次未再触碰。

---

## 3. 逐项修复说明

### F1 · `Math.floorDiv`/`Math.floorMod`（真崩）

**为什么必须修**：`Math.floorDiv(long,long)` 是 Java 8 → **API 24**；`desugar_jdk_libs` 2.1.4 的清单里**没有** `java.lang.Math`（`java.lang.*` 是平台类，desugar 不动）。4.4 上抛 `NoSuchMethodError`，**它是 `Error`**，所以 `NtpClock.runSender` 的 `catch (_: Exception)`（`:77`）接不住；`syncedNtp()` 无版本守卫，`AirPlaySession.openTiming()` 在 SETUP 里无条件启动 timing 端口，`CarPlayMediaEngine` 的 feedback 路径也会调。全仓无 `UncaughtExceptionHandler`。

**修法**（`NtpClock.kt`）：
- 调用点改为本地 `floorDivLong`/`floorModLong`（`:221-222`）。
- 两个辅助函数放在**文件私有作用域**之外但仅 `internal`（`:241` `floorDivLong`、`:254` `floorModLong`），KDoc 明写"`Math.floorDiv` 是 API 24 / 未 desugar / 不要换回去"，并指向守卫测试。
- 语义**必须**是 floor（向下取整），这是这两个函数存在的唯一理由：`ntpFromNanos` 收到的是 `System.nanoTime() + clockOffsetNs`，**经常为负**（开机单调钟接近 0，而 offset 是一整个 NTP 纪元、正负皆可）。Kotlin 的 `/` 与 `%` 向零取整，会让 `-1 ns` 变成 `0 s / -1 ns`，构造出**快一秒**的 NTP64 时间戳。

实现（`NtpClock.kt:241`）：

```kotlin
val quotient = dividend / divisor
val remainder = dividend % divisor
return if (remainder != 0L && (remainder < 0L) != (divisor < 0L)) quotient - 1 else quotient
```

- `remainder != 0L` 排除整除（商的截断值已是 floor）。
- 余数与除数异号 ⟺ 截断商向上取整了。
- **踩坑记录**：第一版用了常见写法 `(dividend xor divisor) < 0L` 作为"异号"判据，**这是错的**——它判的是被除数与除数异号，而不是余数与除数异号。对 `floorDivLong(3, -5)`（应为 `-1`）会返回 `0`。单测当场抓住（`floorDivisionRoundsTowardNegativeInfinityForEverySignCombination`），已改正。
- 溢出边界：`floorDivLong(Long.MIN_VALUE, -1L)` 在 Java/Kotlin 整数除法里等于 `Long.MIN_VALUE`（不自陷），辅助函数保持该行为、不抛异常（它跑在 NTP 发送线程上）。有单测钉住。

**测试**：`shared/src/test/java/com/shilapi/xcertplay/airplay/NtpClockFloorArithmeticTest.kt`（6 例）
- 四个符号组合的 floor 除法；负除数（`floorDiv(3,-5) = -1`）；
- 与 `BigInteger.divideAndRemainder` 逐例交叉验证（含 `Long.MIN_VALUE`/`Long.MAX_VALUE` 边界）；
- 余数符号跟随除数、`|余数| < |除数|`、`dividend == floorDiv*divisor + floorMod`；
- 除数为 0 抛 `IllegalArgumentException`；
- 负纳秒不"借位"到下一秒（`-1 ns → -1 s / 999999999 ns`）。

### F2 · 媒体会话对象搬进版本隔离持有者

**为什么必须修**：这些类型在 4.4 上不存在，而守卫分散在**别的方法**里（`start()` 提前 return、`regainFocusLocked()` 提前 return），可 `updateLocked()`(:176) → `publishPlaybackStateLocked()` 与 `releaseLocked()`(:282) 在 4.4 上**无条件执行**，方法体里有 `new-instance PlaybackState$Builder` 与 `MediaSession.release()`。按仓库自定规则（`docs/_digest/08 §4.3`、`docs/_digest/17 §2` 第 3 条：**被执行方法体内出现 API21+ 类型即崩**），这是必须消除的形状。

**修法**（新文件 `common/src/main/java/com/shilapi/xcertplay/MediaSessionBridge.kt`）：

| 成员 | 行 | 作用 |
|---|---|---|
| `internal interface MediaSessionBridge` | :31 | 主 object 持有的**接口**类型；`of()` 在 <21 返回 null |
| `internal interface MediaSessionController` | :66 | 持有者回调主 object 的通道，签名里**没有任何** API21+ 类型 |
| `internal object MediaKeysPlatform` | :78 | `ownsMediaSession(level)` / `ownsAudioFocusRequest(level)` — 纯函数，JVM 可测 |
| `internal object MediaSessionBridgeFactory` | :95 | `metadataOf()`(:104)、`playbackStateOf()`(:125)、`actions()` — 只做"把已定好的决定变成对象" |
| `private object Api26AudioFocus` | :143 | `AudioFocusRequest.Builder` 藏在这里（否则 21–25 校验该构造器即崩） |
| `private class Api21MediaSession` | :160 | 真正持有 `MediaSession`/`MediaMetadata`/`PlaybackState`/`AudioFocusRequest` + `MediaSession.Callback` |

> 写持有者时踩到的第二个坑（自查发现、已修）：`Api21MediaSession` 里一度写成 `context.getSystemService(AudioManager::class.java)`——那是 **API 23**。本类只在 ≥21 加载，于是 **21–22 上它会静默返回 null，媒体键焦点整块失灵**。改回仓库统一的 `com.shilapi.xcertplay.compat.systemService`（`MediaSessionBridge.kt:168`）。这正是"搬进新类时顺手把 compat 层的用法丢了"这一类回归，值得记忆。

`CarPlayMediaKeys.kt` 侧：
- `session` 字段类型改为 `MediaSessionBridge?`（:66）——**本 object 的字段与方法体中不再出现任何媒体会话类**；
- `start()`(:184) 里 `<21` 提前 `startLegacyKeys()`，然后 `MediaSessionBridge.of(context, sessionController)`(:191)；
- `releaseLocked()`(:282) 只调 `session?.release()`（4.4 上 session 恒为 null，只剩遗留按键注销）；
- `publishPlaybackStateLocked()`(:294) 用 `playbackStateRequest()`(:307) 先做决定，再交 `MediaSessionBridgeFactory.playbackStateOf()` 造对象；
- 键位映射抽成 `dispatchMediaKey(event, source)`(:229)，两个入口（4.4 广播 + 21+ 会话回调）共用一份映射；
- **遗留路径完全不变**：`startLegacyKeys()`/`stopLegacyKeys()`/`onLegacyMediaKeyEvent()` 原样保留，日志串不变（`media keys active (legacy MEDIA_BUTTON) focusGranted=…`）。
- `focusHeld` 的写法也统一了：桥通过 `MediaSessionController.audioFocusHeld(held)` 回报，4.4 的遗留路径自己写。
- 顺带修掉一处顺序 bug：原 `regainFocusLocked()` 用 `audio.requestAudioFocus(request)`，而 26+ 的 `focusRequest` 只有在 `start()` 里才会被赋值——`request == null` 时它会 `return`（`:169`），把"重新拿回焦点"整段吞掉。现在桥自己持有 request 并在 `activate()` 里重建，不再依赖那个可空字段。

**测试**：
- `common/src/test/.../MediaSessionBridgeTest.kt`（7 例，`@Config(sdk = [26])`）：`MediaKeysPlatform` 的 19/20/21/26 边界；`of()` 在 ≥21 返回非空、`isActive` 为真；构造时**恰好一次**把焦点结果回报给 owner；`metadataOf`/`playbackStateOf` 的字段与 action 位。
- `common/src/test/.../CarPlayMediaCallbackTest.kt`（改为 `@Config(sdk = [29])`）：控制器 play/pause/next/prev 的显式映射；方向盘 PLAY/PAUSE/PLAY_PAUSE 三键都被**认领**；同一物理按下的重复被 `MediaKeyEchoGate` 挡掉；未映射键**交还平台**（返回 false）；语音键被认领；`playbackStateRequest` 的两路信号优先级（iPhone 元数据优先于"线上有音频"、只有标题也算元数据、位置未知为 -1）；`metadataChanged` 不因位置/播放态重发元数据。

**需要 Robolectric/真机确认的部分**（JVM 测不到）：
1. **真机**：Dalvik 是否真的容忍"守卫内引用的新类型"。本修复把引用挪走，形状上已合规，但**只有车机 logcat 能证明**（见 §8 不确定项 (a)）。
2. **Robolectric API 19 不可用**：`org.robolectric:robolectric:4.17` 没有 android-all 的 API 19 包——实测 `IllegalArgumentException: API level 19 is not available`。所以 4.4 的运行只能在车机上验；JVM 侧只能测"决定"这一层。
3. 真机上媒体键是否真的从 `MEDIA_BUTTON` 广播到达、`registerMediaButtonEventReceiver` 是否"最后注册者胜"（`docs/_digest/08 §7` 的待验证项）。
4. 26+ 分支的 `requestAudioFocus(AudioFocusRequest)` 在车机上不可能走到（车机是 19），只由平板覆盖。

### F3 · `LocaleManager` 隔离 + `UsbConfiguration` 路径隔离 + 删死反射

**F3a `AppLocale.kt`（`attachBaseContext` 路径，4.4 必执行）**
- 新 `common/src/main/java/com/shilapi/xcertplay/Api33Locale.kt`：`supported(level)`(:23)、`preferredLanguageTag(context)`(:42)、`saveLanguage(context, locale)`(:58)、`migrateLegacyPreferenceOnce(context, localeOf)`(:68)。`LocaleManager` 的三处用法全部在此。
- `AppLocale.kt` 的 `preference()`(:31)、`save()`(:51)、`wrap()`(:63) 改为守卫 + 委托；`wrap()` 下方的 prefs + `createConfigurationContext` 老路径**一字未动**。
- `PREFS`/`KEY_LANGUAGE`/`KEY_MIGRATED` 由 `private` 改 `internal`（两个 object 共用同一份 prefs 键，迁移逻辑要在持有者里读老值）。
- 测试：既有的 `AppLocaleTest`（`@Config(sdk=[33])` 与 `[28,29,30,31,32]`）**未改一行**即通过——说明行为等价。

**F3a 附带发现：`Locale#getScript` 是 API 21（lint 抓到了）**
- 隔离时先在 `AppLocale.preference()` 里内联解析 script，`lint` 立刻报
  `NewApi AppLocale.kt:37: Call requires API level 21 (current min is 19): java.util.Locale#getScript`。
- 结论：**"方法只在 33+ 执行"不构成用 API21 方法的理由**——lint 只看 `minSdk`，看不见调用点守卫；而项目的隔离约定本身就是"按类型/按 API 分层"。
- 修法：把 script 判断整体挪进 `Api33Locale.preferredLanguageTag()`（`:42`），由它返回"本 app 自己语言标签"（`en`/`ar`/`ru`/`es`/`uk`/`zh`/`zh-TW`），`AppLocale.preference()` 只做一次 `tag in ALL` 兜底。这样 `AppLocale` 的方法体里既没有 `LocaleManager` 也没有 `getScript`。
- 踩坑记录（Robolectric 实测）：Android 上 `Locale.toString()` 是**遗留 JDK 形式**而不是 BCP 47——`zh-Hant` → `zh__#Hant`、`zh-Hans-TW` → `zh_TW_#Hans`。所以"从 `toString()` 前缀判断 script"是错的，必须用 `getScript()`（放持有者里）或 `toLanguageTag()`（API 21）。
- 既有的两条 script 测试（`explicitTraditionalScriptIsRecognizedWithoutATraditionalRegion`、`explicitSimplifiedScriptTakesPrecedenceOverTraditionalRegion`）在改动后仍**全绿**，`AppLocaleTest` 16 例 0 失败。

**F3b `NcmFunctionDiscovery.kt`：删掉永远不可能成功的反射**
- 原 `interfaces()` 的 `<21` 分支（原 `:94-98`）反射 `UsbConfiguration::class.java`。**`UsbConfiguration` 整类 `since="21"`**（已用 `platforms/android-35/data/api-versions.xml` 复核），4.4 上类不存在 → `runCatching` 只会得到 `emptyList()`，**是死代码兼误导**。已删，只留 `(0 until configuration.interfaceCount).map(configuration::getInterface)`，KDoc 写明原因（`:53-58`）。
- 原 `alternateSettingOf()` 的 `<21` 反射兜底同样已删（`:60-63`）：`UsbInterface#getAlternateSetting` 也是 **since 21**（同一份 api-versions.xml），4.4 上不可能成功。
- 因此 `NcmFunctionDiscovery` 的 `Build` 与 `UsbDevice` import 已移除。

**F3c `IphoneCarPlayConfiguration.kt`：`UsbConfiguration` 需要隔离，不是"不可达"**
- 报告要求的判断：**必须隔离**。理由：`UsbConfiguration` 的整类、`UsbDevice#getConfigurationCount/getConfiguration` **都是 API 21**（api-versions.xml 复核；lint `NewApi` 的 8 条均落在 `:49/50/59/120/125`）。当前 `find()` 在 `<21` **先 early return null**（`:33-36`），`IphoneUsbHost` 的枚举调用点也都在 `SDK_INT >= 21` 块内（`IphoneUsbHost.kt:286-299`），所以**今天在 4.4 上不会被解析**——但这份保证只存在于"调用者记得守卫"里，`usbMuxInterface()` 曾是 public 且由 `IphoneUsbHost.kt:298` 从另一个文件调用。
- 修法：实体代码搬进新 `shared/.../transport/Api21Configuration.kt`（`@RequiresApi(21)`；`find:35`、`describe:49`、`usbMuxInterfaceOf:60`，私有 `hasCdcNcm`/`hasAppleEthernet`）。`IphoneCarPlayConfiguration` 只留守卫 + 活动配置路径（`activeConfigCarPlayReady:51`、`activeUsbMuxInterface:73`、`usbMuxEndpoints`，全部只用 `UsbDevice`/`UsbInterface` API 12–18 的成员）。
- 附带效果：lint 那 8 条 `NewApi` 的引用点全部搬到隔离类里，形状与 `AudioApiBridge` 一致。

**F3 未做（故意）**：没有给 `AppLocale`/`IphoneCarPlayConfiguration` 的方法加 `@RequiresApi` 注解了事——注解只是给 lint 看的，不改变 Dalvik 的校验行为；必须靠"类型只在够高版本被加载"。

### F4 · 中央屏悬浮卡的窗口类型

**为什么必须修**：`TYPE_APPLICATION_OVERLAY`(2038) 在 4.4 上**不存在**，但它是 `static final int`，**编译期内联**，所以不抛 `NoClassDefFoundError`；2038 在 4.4 的 WindowManager 里是无效窗口类型 → `addView` 抛 `BadTokenException`/`SecurityException` → 被 `show()` 末尾的 `catch (error: RuntimeException)` 吞掉 → **中央屏地图卡在 CS11 上永远不出现**（静默功能丢失）。`07d §7.2` 早已把它列为未修项。

**修法**：`CenterMapOverlay.kt` 新增 `internal fun overlayTypeFor(level: Int): Int`（:62，`≥26 → TYPE_APPLICATION_OVERLAY`，否则 `TYPE_PHONE`），构造 `WindowManager.LayoutParams` 时用它（:122）。`permitted()` 的 `canDrawOverlays` 守卫（:50）本来就对，未改。

**测试**：`common/src/test/.../CenterMapOverlayTest.kt` 新增 2 例
- `theOverlayWindowTypeFollowsTheApiLevel`：钉住常量值本身（`TYPE_PHONE == 2002`、`TYPE_APPLICATION_OVERLAY == 2038`）以及 19/21/25 → 2002、26/33 → 2038。**注意**：Robolectric 4.17 没有 API 19 的 android-all，所以 19 这个数字是**直接传参**进纯函数断言的，不是真的在 API 19 环境里跑（`IllegalArgumentException: API level 19 is not available` 实测过）。
- `theShownCardUsesTheTypeForThisPlatform`：在 SDK 33 下 `show()` 后，窗口实际拿到的 `params.type` 就是 2038。

**需要真机确认**：CS11 是否接受 2002、以及在**没有** `SYSTEM_ALERT_WINDOW` 时是否直接拒绝（`07d §7.3` 的"用户手动或系统预置授权"）。见 §8 不确定项 (b)。

### F5 · 版本判断退化 → API19 NCM 路径不可达（结论与理由）

**先定意图**（读 `Cs11NetdevBootstrap`、`openNcm`、有线链路、`07a`/`07d`、git 历史）：

- `Cs11NetdevBootstrap.supported` **定义就是** `Build.VERSION.SDK_INT < 21`（`Cs11NetdevBootstrap.kt:33`）。
- 于是原 `:1613` 的 `SDK_INT < 21 && Cs11NetdevBootstrap.supported` **退化为同一个级别测试**，`:1620` 那个位于 `else` 里的 `<21` 降级分支**恒为 false**（lint `ObsoleteSdkInt` 在 `shared` 报告里就只有这一条：`CarPlayController.kt:1620: Unnecessary; Build.VERSION.SDK_INT < 21 is never true here`）。
- `git log -L` 给出**意图的出处**：`5b0f5774`（"wired CarPlay fully on screen on the CS11 head unit"）**新增**了 `&& Cs11NetdevBootstrap.supported` 这一项，把 `ncm` 在 API19 上**显式置空**（注释：内核不能建 TUN，raw-USB NCM 桥永远载不了媒体，4.4 由内核 cdc_ncm 网卡 + 系统网络栈顶替）。它**只加了外层守卫，没删 d100018a 留下的内层旧 catch**，于是形成"外层已置空、内层还在判断能不能降级"的双重残留。
- `runStack()`（`CarPlayController.kt:1757+`）的语义也印证：`ncm == null` 且 `directNetdev == false` 时走 `debugLog("CS11 degraded mode: no NCM bridge; control channel only")` 并继续（控制通道 only）。也就是说**内核网卡失败时的既有降级语义是"无媒体桥"，不是"回头试 raw-USB"**。

**结论：整条 API19 原始 NCM 路径对本车机已作废，删死分支。**
- 判定理由（四点齐）：① 该路径由 `5b0f5774` 显式弃用并有实证支撑（doc16/17：内核无 TUN、raw-USB NCM 桥在本 ROM 上不可用）；② 既有的降级语义已经是"无 NCM 桥继续"，再回头试 raw-USB 属重复设计；③ `openNcm()` 的 API19 分支**依赖的反射发现已被 F3b 判定为永远不可能成功**（`UsbConfiguration` 类在 4.4 不存在），所以它本来也只会抛"no NCM function"；④ 保留一个恒不可达的分支会让下一次审计再次误判"这条路径是活的"。

**修法**（`CarPlayController.kt`）：
- `openDataPaths` 里改为一个显式决定：`val decision = rawNcmDecision(Build.VERSION.SDK_INT)`（:1621），`usesKernelNetdevOnly` 为真则打日志并置 `null`，否则 `openNcm(device)`。
- 新增 `internal data class RawNcmDecision`（:1640）与 `internal fun rawNcmDecision(sdkInt: Int)`（:1648）——**纯函数、可 JVM 测**，这就是"证明 <21 路径可达/被正确决定"的落点。
- `openNcm()`（:1663）删掉整段 `<21` 分支，KDoc 写明"raw-USB NCM 桥仅 API21+；API19 无消费者（内核 cdc_ncm 网卡顶替），其发现变体 `NcmFunctionDiscovery.findAllActive` 已一并删除"。
- `NcmFunctionDiscovery.findAllActive()` 及其专用逻辑删除（唯一调用点就是那段死分支），`NcmFunctionDiscovery` 现在只有 `find(configuration)` 一个入口。

**测试**：`shared/src/test/.../orchestration/CarPlayWiredTransportDecisionTest.kt`（4 例）
- `theCarLevelUsesTheKernelNetdevInsteadOfTheRawUsbBridge`：**API 19 必须走内核网卡**，且替代日志串含 `kernel netdev transport selected`；
- `everyLevelBelow21TakesTheKernelNetdevPath`：1..20 全为真；
- `api21AndAboveStillOpenTheRawUsbBridge`：21..37 全为假（平板侧行为未变）；
- `theKernelNetdevGateMatchesTheApi19LevelTest`：断言 `Cs11NetdevBootstrap.supported == (SDK_INT < 21)`，把"那个多余的与项为什么可删"钉进测试。
（测试通过反射外的正常途径调用：`CarPlayController` 用与 `CarPlayControllerUsbServiceTest` 同款的最小构造。）

### F6 · `Cs11Compat.systemService()` 的死 null 分支（**只报告，未改**）

`shared/src/main/java/com/shilapi/xcertplay/compat/Cs11Compat.kt:20-26`：API<23 时 `ContextCompat.getSystemService(...) ?: throw IllegalStateException(...)`，声明类型是 `T`（非空）。所以调用点上的 `?.` 与 `if (x == null)` 都是**死分支**，真缺服务时是**抛异常**而不是返回 null。

审计已核实：本仓库请求的 **17 个服务全部在** `ContextCompat` 的 pre-23 映射表（`LegacyServiceMapHolder.SERVICES`）里，现实中不会触发；语义是错的但不致害。**按要求本次一行未改**（动它会牵涉大量调用点、且属行为改动）。

可清理清单（按"是否真的死"分两类）：

**A. 死 `?.`（`systemService()` 已非空，`?.` 可删）**
| 文件:行 | 现状 |
|---|---|
| `shared/.../network/LegacySoftApControl.kt:41` | `context.systemService(WifiManager::class.java)?.let(::readStored)` |
| `shared/.../network/LegacySoftApControl.kt:187` | `... ?.let(::apState)` |
| `shared/.../network/WifiP2pGroupManager.kt:716` | `... ?.connectionInfo` |
| `shared/.../network/WifiP2pGroupManager.kt:728` | `... ?.isLocationEnabled` |
| `shared/.../network/WifiP2pGroupManager.kt:734` | `... ?.unsafeCheckOpNoThrow(...)` |
| `shared/.../orchestration/CarPlayController.kt:175` | `... ?.adapter` |
| `common/.../CenterMapOverlay.kt:281` | `... ?.removeViewImmediate(view)`（在 `runCatching` 内） |
| `common/.../DiPlayActivity.kt:1826` | `val adapter = systemService(...)?.adapter` |
| `common/.../DiPlayBluetooth.kt:10` | `runCatching { ... ?.adapter?.address }.getOrNull()` |
| `common/.../PhoneMediaAudioWatchdog.kt:79`、`:114` | 同上两处 |
| `common/.../CarPlayMediaKeys.kt:216` | `... ?.unregisterMediaButtonEventReceiver(...)`（**本次改动后仍在**，属同类） |

**B. 死 `if (x == null)`（服务缺失时会走到这里，但代码假设它返回 null）——报告点名 + 同类**
| 文件:行 | 现状 |
|---|---|
| `shared/.../network/LegacySoftApControl.kt:106` | `if (wifi == null) { log("legacy AP: WifiManager unavailable"); return false }` |
| `common/.../DiPlayActivity.kt:1884` | `if (manager == null) { toast(...); return }` |
| `shared/.../network/CarHotspotStatus.kt:26-29` | 同类（报告 §1 F6 点名） |
| `shared/.../network/ManualHotspotInterfaces.kt:21`、`:35` | 同类（报告 §1 F6 点名） |

> 编译器（lint 的 "Condition is always false"）在报告里只点名了 `LegacySoftApControl.kt:106` 与 `DiPlayActivity.kt:1884`；上表 B 的其余是同类手工核对结果。
> 处置建议（留给维护者决策）：把 `Cs11Compat.systemService` 改成返回 `T?` 并让调用点诚实，或删掉这些死判断并注明"缺服务即抛"。**本次不改。**

### F7 · `OpusEncoderSupport` 的 `MediaCodecList`

- 类型事实（api-versions.xml 复核）：`MediaCodecList` **类本身 since 16**，但 `<init>(I)`、`getCodecInfos()`、`REGULAR_CODECS`、`ALL_CODECS` 均 **since 21**；16–20 只有已废弃的 `getCodecCount()/getCodecInfoAt()`。
- 原写法把调用放在同一 object 的**另一个方法**里，靠"4.4 上不会被调用"保证安全——违反"新 API 放在独立静态类"的约定。
- 修法：新增 `private object Api21CodecList`（`OpusEncoderSupport.kt:64`，`hasOpusEncoder():65`），`platformAvailable()`（:49）在 `>=21` 时委托。4.4 仍返回 false → 走 `SoftwareOpusEncoder` 软件探测，行为不变。
- **`AndroidMediaSink.kt:696-707` 用的是 `getCodecCount()/getCodecInfoAt()` 老 API，本来是对的，不要动**（已写进 KDoc）。

---

## 4. 新守卫：不能回来的那一类 bug

### 4.1 盲区本身（为什么 lint 抓不到）

`REPORT.md §2` 的实测：`java.lang.Math#floorDiv(JJ)J` 在 lint **自己的** API 库里是 `since="24"`，且**不在** desugaring 清单，但三份 lint 报告里 `grep 'java\.'` / `grep NtpClock|floorDiv` / `grep Base64` **全部 0 命中**。
→ **本 lint 配置（desugaring 已开启）对 `java.*` 库 API 一律不报，只报 `android.*`。** F1 是靠读代码 + `javap -c` 反汇编字节码才发现的（实证：`invokestatic // Method java/lang/Math.floorDiv:(JJ)J`）。

### 4.2 守卫实现

**`shared/src/test/java/com/shilapi/xcertplay/airplay/ForbiddenLibraryApiTest.kt`**（4 个测试方法）：

| 方法 | 作用 |
|---|---|
| `moduleSourcesDoNotUseLibraryApisThatBreakAndroid4` | 扫描 `shared/common/mobile` 的 `src/main/java/**/*.kt`，命中禁列即**失败并逐条打印** `规则 (原因) at 相对路径:行号` |
| `newerPlatformClassLiteralsStayInsideTheirVersionIsolatedHolder` | §4.4 的"洗白类"规则：`LocaleManager::class` 只许出现在 `Api33Locale.kt` |
| `theScannerStillFindsInUseDesugarCoveredApis` | 反向自检：现在确实在用的 `java.util.Base64` / `java.time` 必须仍被扫到，否则是**扫描器坏了**而不是代码干净 |
| `everyForbiddenRuleMatchesItsOwnExample` | 每条规则的样例自检，防止正则写歪后"永远不报" |

禁列（规则 / 原因）：`Math.floorDiv`、`Math.floorMod`、`Objects.requireNonNullElse(Get)`、`String.join`、`Map.of/ofEntries/entry/copyOf`、`List.of/copyOf`、`Set.of/copyOf`、`java.nio.file`、`CompletableFuture`、`java.util.Optional`。
已覆盖清单（允许、但显式登记）：`java.util.Base64`、`java.time`（desugar 覆盖，实证见 `REPORT.md §3.4`）。
另有 `VERSION_ISOLATED` 清单解释"哪些用法是靠隔离类才被允许"。

实现细节：
- 扫描前先**剥掉注释与字符串字面量**——否则 KDoc 里引用 `Math.floorDiv`（本次修复自己就写了好几处）会误报。
- **只扫 main 源码**：测试源码跑在 JVM 上，永远不上车（`java.nio.file`/`CompletableFuture`/`java.time.Duration` 在测试里大量在用，是正当的）。
- 仓库根由 `user.dir`（Gradle 测试 JVM 从模块目录启动）向上找 `settings.gradle.kts`，不写死绝对路径。

**失败长这样**（F1 的反向检查实录，把 `floorDivLong` 换回 `Math.floorDiv` 时）：

```
java.lang.AssertionError: Library APIs that core library desugaring does not cover were found in
the module sources. They fail on Android 4.4 with NoSuchMethodError/NoClassDefFoundError, and lint
will NOT report them (see this class's KDoc). Replace them, or move the use behind a
version-isolated holder:
Math.floorDiv at shared\src\main\java\com\shilapi\xcertplay\airplay\NtpClock.kt:214 — API 24;
java.lang.Math is not in desugar_jdk_libs, so 4.4 throws NoSuchMethodError
```

### 4.4 第二处盲区：被 helper "洗白"的新平台类（本次修 F3 时发现）

**现象**（实测，可复现）：`AppLocale.kt` 在基线里把 `LocaleManager::class.java`（API 33）传给 `Cs11Compat.systemService(...)`，而 `wrap()` 由 `attachBaseContext` 在 4.4 上**必然执行**——但审计的三份 lint 报告里 **`AppLocale` 零命中**（已用脚本核对：`grep 'LocaleManager|Locale#|LocaleList'` → 三份报告 0 命中）。

**原因**：`Cs11Compat.systemService` 是

```kotlin
fun <T : Any> Context.systemService(serviceClass: Class<T>): T
```

`LocaleManager::class.java` 只是一个**类字面量**，被作为参数传进一个普通 Kotlin 扩展函数；lint 的 `NewApi` 只在**直接调用受版本限制的 API 成员**时触发，看不到"这个 `Class<T>` 会被拿去 `getSystemService`"。所以基线里 F3 的形状**从未出现在任何 lint 报告里**——它和 §4.1 的 `java.*` 盲区是**两个不同的**盲区（一个是"不查 java.*"，一个是"查不到被包了一层的平台类"）。审计是靠**读代码**（`AppLocale.wrap()` 在启动路径上）发现 F3 的。

**反证（同一次实验）**：同文件里一旦出现**直接**成员访问 `locale.script`，lint 立刻报 `java.util.Locale#getScript`（API 21）——所以不是"这个文件没被扫"，而是**只有被 helper 洗白的类字面量隐身**。

**处置**：`ForbiddenLibraryApiTest` 增加第 4 个测试方法 `newerPlatformClassLiteralsStayInsideTheirVersionIsolatedHolder`，规则是**结构性**的：`LocaleManager::class` 只允许出现在 `common/src/main/java/com/shilapi/xcertplay/Api33Locale.kt`，别处一律失败（同样剥注释/字符串后匹配）。规则表在测试里叫 `LAUNDERED_CLASS_LITERALS`，将来给别的"被 helper 包住的越界类"加规则就加一行。

> 同一模式还会以别的方式出现（把越界类型放进签名、放进泛型实参），本规则只覆盖"类字面量"这一种——因为那是本仓库实际用过的、且 lint 确实看不见的那一种。同理，**不要**把 `UsbConfiguration` 这类**出现在方法签名里**的类型加进这条规则：那会误报一批合法的同签名的类（试过，`IphoneCarPlayConfiguration`/`NcmFunctionDiscovery`/`IphoneUsbHost` 全被点名），它们的正确形状是 F3c 的"实体代码搬进持有者"，由 §4.2 的库 API 规则与人工评审兜底。

### 4.3 盲区写在"未来读者会看的地方"

- `shared/build.gradle` 的 `lint { }` 块旁（CS11/API 19 blind spot 注释，指向守卫测试与本文）；
- `common/build.gradle.kts` 的 `lint { }` 块旁（同内容中文版）；
- 守卫测试自身的 KDoc（类头第一段就是这条 blind spot）；
- `NtpClock.floorDivLong` 的 KDoc 末尾写了"不要换回去，见守卫测试"。

> 为什么不是 Gradle task：仓库里没有自定义 check task 的约定（`build.gradle` 只有 android/lint/dependencies 三段），而单测是既有且必定会跑的聚合点，所以用单测实现最省、最难被绕过。

---

## 5. 验证

命令（worktree 内）：

```
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --console=plain --offline
```

汇总口径：从 `<module>\build\test-results\testDebugUnitTest\*.xml` 累加 `testsuite@tests` 与 `failures+errors`。

| 模块 | 修复前（HEAD `f77c179c`） | 修复后 | Δ 测试数 | 失败集合 |
|---|---|---|---|---|
| `:shared` | **765 / 3 失败** | **779 / 3 失败** | +14 | `HotspotJoinNormalizedTransactionTest`（3，既有） |
| `:common` | **555 / 6 失败** | **569 / 6 失败** | +14 | `CarPlayControllerUsbServiceTest`（3）+ `UsbPermissionSetupTest`（3），均既有 |

**失败集合没有变大**：两次运行的失败用例名与数量完全一致，全是既有失败；新增的 28 个用例全绿。

### lint 复核（`.\gradlew.bat :shared:lintDebug :common:lintDebug --offline`）

| 模块 / id | 修复前 | 修复后 | 说明 |
|---|---|---|---|
| `:shared` `NewApi` | 65 | **57** | F3b/F3c/F7 把 `UsbConfiguration`/`MediaCodecList` 的引用点搬进隔离类，净减 8 |
| `:shared` `ObsoleteSdkInt` | 1 | **0** | 就是 F5 那条 `CarPlayController.kt:1620` |
| `:shared` `InlinedApi` | 33 | 33 | 未变（本次没动内联常量） |
| `:common` `NewApi` | 29 | **65** | **预期上升**：`CarPlayMediaKeys` 的媒体会话族引用**原样搬进** `MediaSessionBridge.kt`（40 条）+ `Api33Locale.kt`（18 条）+ `AppLocale.kt:45` 的 `Locale#getScript`（1 条）。这与 `shared` 的样板 `AudioApiBridge`（36 条 `NewApi`，见审计报告 §2）同一性质：**引用点集中在隔离类里，本来就是设计要的结果**，不是新增越界 |
| `:common` `InlinedApi` | 24 | 25 | +1：`CenterMapOverlay` 的 `TYPE_APPLICATION_OVERLAY` 仍在（它现在是"≥26 才用"，内联无害），另 `TYPE_PHONE` 不产生该告警 |
| 三份报告的 `java.` 命中 | **0** | **0（`shared`）/ 1（`common`）** | `common` 的这 1 条正是 §4.4 里的 `Locale#getScript`——**它现在是 lint 唯一能看见的那一条**，且落在 33+ 专用持有者里 |

**关于 `AppLocale.kt:45` 的那条 `NewApi`（`java.util.Locale#getScript`，API 21）**：它位于 `Api33Locale.preferredLanguageTag()` 内（只有 `SDK_INT >= 33` 才被调用），并且按项目约定**不能**改用 `Locale.toString()` 前缀判断——Android 上 `toString()` 是遗留形式（实测 `zh-Hant` → `zh__#Hant`）。所以这里保留唯一的、已解释的 lint 告警，而不是用 `@Suppress` 把它藏起来。

### 反向检查（都做了，都成立）

| 对应 | 做法 | 结果 |
|---|---|---|
| **F1** | 把 `NtpClock.kt` 的 `floorDivLong` 换回 `Math.floorDiv`，只跑守卫测试 | ✅ 守卫测试失败并指名 `NtpClock.kt:214`（见 §4.2 输出）；**辅助函数单测（`NtpClockFloorArithmeticTest`）依旧全绿**——因为 `ntpFromNanos` 是文件私有函数，辅助函数测试**测不到**调用点，这正说明"只测辅助函数不够、必须有扫描式守卫" |
| **F4** | 把 `overlayTypeFor` 改成恒返回 `TYPE_APPLICATION_OVERLAY` | ✅ `CenterMapOverlayTest > theOverlayWindowTypeFollowsTheApiLevel` 失败（14 例中 1 例） |
| **F5** | 把 `rawNcmDecision` 的条件从 `< 21` 翻成 `>= 21` | ✅ `CarPlayWiredTransportDecisionTest` 4 例中 3 例失败（含"API 19 必须走内核网卡"） |
| **F2/F3/F7** | 未做等价反向改写 | 隔离类无法用"改一行"的方式反向验证；其价值由**车机 logcat** 判定（§8 (a)）。这三项的可测部分（决策/映射/对象构造）都有正向用例 |

---

## 6. 故意不改的项

| 项 | 位置 | 为什么不改 |
|---|---|---|
| F6 的 `systemService()` 死 null 分支 | 见 §3 F6 的 A/B 两张表 | 任务明确要求"只报告、不改"；改它=行为改动（从"抛异常"变"返回 null"），会牵动 12+ 调用点 |
| `openNcm()` 里 `if (SDK_INT >= 21) configuration.id else -1` / `alternateSetting` 的三处版本三元 | `CarPlayController.kt:1653-1656` | 方法现在只在 ≥21 被调用，这三处 else 是"守卫在别的方法里"的残留；改它属纯清理、无行为收益，且不属于本次任务范围（审计把它们归在"已核对守卫"）。已在 `openNcm` KDoc 里写明方法仅 API21+ |
| `themes.xml` 的 `android:Theme.Material.NoActionBar`(21) parent 等 | `common/src/main/res/values/themes.xml` | 审计结论：4.4 上父样式不存在 → 只保留显式 item、框架属性回落系统默认，**不崩**；文件中已用 `windowActionBar=false`+`windowNoTitle=true` 补偿。彻底做法是补 `values-v21/themes.xml`，属独立议题 |
| `typo` 级 lint 项：`UnusedAttribute`、`Aligned16KB`、`PrivateApi`/`BlockedPrivateApi` | 见 `REPORT.md §2` | 4.4 上被忽略/无害；`Aligned16KB` 是 Android 15 的 16KB 页问题，与 API19 无关 |
| `Math.floor`（`NtpClock.kt:215-216`） | 同上 | **API 1 就有**，不是越界；不要与 `floorDiv` 混为一谈（顺手记一笔，免得后人误删/误改） |

---

## 7. `REPORT.md §3` 的"已核对且干净"清单（本次未再触碰）

审计已逐条核过并判定干净，**不要重复审**：向量图（AGP 生成 PNG 兜底）、multidex（`MultiDexApplication` 与 `MultiDex` 都在主 dex）、依赖 minSdk（无进 APK 的依赖 > 19，无 `tools:overrideLibrary`）、Java 8+ 库 API 判定表、全部反射点、手册/资源侧（`minSdk 19 / targetSdk 37`）、启动路径（唯一启动期风险点就是 F3 的 `AppLocale.wrap()`，本次已隔离）、`UsbReadQueuePolicy`、`Cs11Compat` 的媒体兼容函数、`IphoneUsbHost` 的双分支、`AndroidMediaSink`/`CarPlayRotation`/`CarPlayHostActivity` 等已守卫点。

其中三项与本次修复直接相关的复核结论：
1. **`docs/_digest/07d §7.2`** 的 `java.util.Base64`(26) / `java.time`(26) / `CompletableFuture`(24) / `Map.computeIfAbsent`(24) 四项**已被 desugaring 或手工替换解决**；F1 的 `Math.floorDiv` 是**唯一**仍在使用且不被覆盖的库 API（本次修掉）。
2. **`AndroidMediaSink.kt:696-707`** 用 `getCodecCount()/getCodecInfoAt()` 老 API 是对的——F7 只动了 `OpusEncoderSupport`。
3. **`docs/_digest/17 §2` 第 3 条**（Dalvik 按方法惰性但急切校验）是 F2/F3 全部"搬进隔离类"的依据；本 ROM 的实际容忍度见 §8 (a)。

---

## 8. 仍需实机取证（审计的不确定项 (a)–(f)）

| # | 不确定项 | 取证方法 / 要看的 logcat 串 | 判读 |
|---|---|---|---|
| **(a)** | **Dalvik 是否真的容忍"守卫化新类型引用"**。仓库自记（doc17 §2）说"首次执行即崩"，但里程碑 `5b0f5774` 里已有同类形状且车机上跑起来了——二者不能同时成立 | 车机上一次**真正走到媒体键/NowPlaying** 的会话：`adb logcat -s DiPlay-MediaKeys DiPlay-CenterMap xcertplay-usb` 并全局搜 `NoClassDefFoundError|VerifyError` 且带 `android.media.session.`、`android.app.LocaleManager`、`android.hardware.usb.UsbConfiguration`；同时确认出现 **`media keys active (legacy MEDIA_BUTTON) focusGranted=…`** | 若**没有**上述 `NoClassDefFoundError` 且出现 legacy 注册行 → 本 ROM 把失败推迟到指令执行，F2/F3 的隔离是"约定合规 + 消除隐患"；若**出现** → 本次修复正是必要的那一刀（且旧包必崩） |
| **(b)** | **CS11 是否接受 window type 2002**（无 `SYSTEM_ALERT_WINDOW` 时） | 中央屏场景触发后看：成功 `DiPlay-CenterMap: card shown WxH at x,y` + `card surface WxH`；失败 `DiPlay-CenterMap: card failed`（带 `BadTokenException`/`SecurityException` 栈）。另 `adb shell dumpsys window | grep -i diplay` | 修前预期恒为 `card failed`（2038 无效）；修后应见 `card shown`。若仍 `card failed` → 是授权问题（ROM 无 SYSTEM_ALERT_WINDOW 或未预置授权），不是窗口类型问题 |
| **(c)** | **timing 端口在真机是否真被打开**（决定 F1 是"每次会话崩"还是"只在某些协商下崩"） | 看是否有 `airplay-ntp-rx`/`airplay-ntp-tx` 线程活动、`TCP peer health` 行、以及 **`NoSuchMethodError: java.lang.Math.floorDiv`**（旧包） | 出现后者 → F1 在真机确实是确定性崩溃；修后该串必须**消失** |
| **(d)** | 平板（Android 15 / targetSdk 37）上的隐藏 API 限制；`HotspotJoinFirmware.current()`（`:17-18`）未包裹 `getMethod("get", String)`，只在 `HotspotJoinPlatform.connect()`（`require(SDK_INT >= 33)`）可达 | 平板 logcat 的 hotspot 诊断行，看是否抛 `NoSuchMethodException` | 抛 → 需要 `runCatching` 包裹（本次未动，属独立项） |
| **(e)** | `profileinstaller 1.3.0` / `androidx.startup InitializationProvider` 在 4.4 启动期 | 4.4 启动 logcat 是否出现其异常 | 出现 → 需要排除该 provider |
| **(f)** | F1 替代修法的精度 | 整数辅助函数可回避"`System.nanoTime()` 超 2^53 ns（≈104 天开机）后 double 丢精度"问题；该场景**无法在真机验证**（需 104 天不重启） | 保持整数实现即可；不要改成 `(ns / 1e9).toDouble()` |

> 另一条并行改动的提示（审计 §5.7）：另一 agent 当时正在改 5 个文件（`CarPlayHostActivity.kt`、`ManeuverArrowRenderer.kt`、`CarPlayGlance.kt`、`RouteGuidanceState.kt`、`VendorClusterBridge.kt`）。本次修复**不与这些文件重叠**（worktree 从 `f77c179c` 拉出），定稿合并前需复核它们的 `+` 行里有没有 `SDK_INT`/`VERSION_CODES`/反射/Java8+ 库 API。

---

## 9. 改动清单（分支 `api19-fixes`，提交 `3037e56e`）

**新增**
| 文件 | 作用 |
|---|---|
| `common/src/main/java/com/shilapi/xcertplay/MediaSessionBridge.kt` | F2 的 API21+/26+ 持有者与版本决定 |
| `common/src/main/java/com/shilapi/xcertplay/Api33Locale.kt` | F3a 的 API33 `LocaleManager` 持有者 |
| `shared/src/main/java/com/shilapi/xcertplay/transport/Api21Configuration.kt` | F3c 的 `UsbConfiguration` 持有者 |
| `shared/src/test/java/com/shilapi/xcertplay/airplay/ForbiddenLibraryApiTest.kt` | §4 的库 API 守卫 |
| `shared/src/test/java/com/shilapi/xcertplay/airplay/NtpClockFloorArithmeticTest.kt` | F1 的 floor 语义测试 |
| `shared/src/test/java/com/shilapi/xcertplay/orchestration/CarPlayWiredTransportDecisionTest.kt` | F5 的传输决定测试 |
| `common/src/test/java/com/shilapi/xcertplay/MediaSessionBridgeTest.kt` | F2 的版本决定/桥测试 |

**修改**
| 文件 | 改动 |
|---|---|
| `shared/.../airplay/NtpClock.kt` | F1：调用点改本地辅助函数 + 两函数实现与 KDoc |
| `common/.../CarPlayMediaKeys.kt` | F2：去掉所有 API21+ 类型；键位映射抽 `dispatchMediaKey`；`playbackStateRequest` 可测化；`session` 改接口类型 |
| `common/.../AppLocale.kt` | F3a：三处 `LocaleManager` 委托给 `Api33Locale`；键常量改 internal |
| `shared/.../transport/NcmFunctionDiscovery.kt` | F3b：删死反射；删 `findAllActive`；KDoc 说明 |
| `shared/.../transport/IphoneCarPlayConfiguration.kt` | F3c：`UsbConfiguration` 实体代码移入 `Api21Configuration` |
| `common/.../CenterMapOverlay.kt` | F4：`overlayTypeFor(level)` + 调用点 |
| `shared/.../orchestration/CarPlayController.kt` | F5：`rawNcmDecision` + `RawNcmDecision`；`openNcm` 删 `<21` 分支；KDoc |
| `shared/.../media/OpusEncoderSupport.kt` | F7：`Api21CodecList` |
| `shared/build.gradle`、`common/build.gradle.kts` | §4.3：lint 旁的盲区注释 |
| `common/src/test/.../CarPlayMediaCallbackTest.kt`、`CenterMapOverlayTest.kt` | 适配与新增用例 |

**未动**：主工作树（`CarPlay-CS11` 的 `cs11-api19` 仍是 `f77c179c` + 另一 agent 的改动），未 merge/rebase/cherry-pick。
**worktree**：`D:\Development\DiAuto-DiPlay\_worktrees\api19-fixes`（需从主树拷 `local.properties` 才能跑 Gradle，与上次审计相同）。

---

## 10. 环境策略（用户明确，2026-10-10）：**CS11（API 19）是唯一目标环境**

> 用户原话：「平板是我们无法上机测试的情况下，次选测试环境，**所有环境兼容方面的东西，不用兼容平板，以 CS11 为主**」。

据此对本审计结论的处置口径如下：

1. **兼容性取舍一律以 CS11 / Android 4.4 / Dalvik 为准**：
   - 本文 F1–F5、F7 都是**为 CS11 修的**（平板本来就能跑），优先级不因平板而降；
   - **平板专用的兼容项不做**。典型：§8 的不确定项 (d)（`HotspotJoinFirmware.current()` 的 `getMethod("get", String)` 未包裹反射异常，只从 `require(SDK_INT>=33)` 可达）——**属平板侧问题，不修**，仅记录；
   - 未来若出现"新 API 让平板更好、但需要额外守卫/分支"的取舍，**默认不做**，除非它同时改善 CS11。
2. **平板只承担"无法上车时的功能冒烟"**：可验证——无线会话建立、音视频/触控通路、日志判据、仪表推送的 trace 行与厂商回显；**不可验证**——Dalvik 行为、4.4 隐藏 API、窗口类型、NTP/timing 真机路径、`bytes=4096` 之类的 CS11 解码器差异。凡属后者，**必须留在点火清单**（doc 28 §9），不得以"平板通过"结案。
3. **API 越界的判定基线**：minSdk 19 是硬约束；`targetSdk 37` 只服务于平板侧的平台行为，**不得**反过来成为放宽 19 兼容的理由（例如"反正平板是 37，就不隔离新类型"是不允许的）。
4. **本地测试的已知盲区**（必须持续记住）：Robolectric 4.17 **没有 API 19 的 android-all**（`IllegalArgumentException: API level 19 is not available`），所以 4.4 的运行期行为**在本机无法自动验证**；能做的是源码级/编译期守卫（见 `ForbiddenLibraryApiTest`）。
