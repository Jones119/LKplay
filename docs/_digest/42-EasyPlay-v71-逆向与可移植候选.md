# 42 · EasyPlay v71 逆向与可移植候选（2026-10-09）

> 样本：`D:\Syncdisk\EasyPlay-Lynk-Android4.4-10-v71.apk`（8,551,548 B，
> SHA-256 `47760B899168D8798436E8F9122D23A3243EE34AFC600A364896FDBFDE8D20F5` ✓ 与任务书一致）
> 基线：`_analysis/easyplay/EasyPlay-preview36.apk`（7,683,804 B）+ 其 jadx 输出 `_analysis/easyplay/src/sources`
> 新版反编译：`_analysis/easyplay/v71/`（jadx 1.5.6，3821 个类，**20 个非致命错误**，`sources` 完整可用 ✓）
> 纪律：**只读分析，未运行/未安装**；未执行 Gradle、未改动 `CarPlay-CS11/` 任何文件 ✓
> 定位：本轮只回答一个问题——**v71 相比 preview36 新增了什么值得我们抄的东西**。

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| **原生库** | **零新增**：三个 `.so` 的**字节数与 SHA-256 完全一致** ✓（连重编译都没有 ✗） |
| **资产/权限/SDK** | **零新增权限**；assets 只多了一张抖音码/赞赏码图；`targetSdk`/`minSdk` 未变 ✓ |
| **全部变化** | 集中在 `classes3.dex`（10,377,476 → 10,841,008 B，**+463,532 B** ✓）= **纯 Java/Kotlin 代码** |
| **代码级变化量** | 403 个自有类（+40 新增）；**68 个同名文件内容有变** ✓；`+412 KB / −101 KB` 自有源码文本 |
| **最大惊喜** | ① 他们**放弃了自动开热点**，把**车机原车热点变成默认模式**（v71 变更日志原话）② 音频**音量键归属**做成了一套按"当前在播什么"切换的状态机 ③ USB 授权做成了**token + 同一次插拔去重**的协调器 ④ **没有 103 缓冲音频**——我们那条路他们没走 |
| **最值钱的可移植项** | 音量控制流（`LegacyVolumeControlRoutes` + `LegacyAudioRouting`）、麦克风采集单线程化 + MIC 直采、USB 授权协调器、启动看门狗、5353 双栈同端口绑定 |

### 变更日志是一等公民（**最大方法论收获**）

新版 APK 把 **v11→v71 全量更新注记**打进了 `assets/easyplay/releases.json`（10,014 → **28,169 B** ✓）。
它就是一份**他们自己做过的、按版本标注的优化清单**，直接告诉我们 v37~v71 每一版的意图。
本报告的所有候选都用它交叉验证过（证据文件：`_analysis/easyplay/v71-assets/assets/easyplay/releases.json`）。

与本次痛点直接相关的 changelog 原文（节选，可信度=**他们自己的宣称**，非我方实测 ✗）：

| 版本 | 注记原文（关键句） |
|---|---|
| **v71** | 「**默认使用车机热点连接**，保留已保存的连接方式」；「CS11 **直接使用 MIC 录音源**，保留麦克风开关」；「**修正 CarPlay 外缘圆角**」；「保留原画面尺寸、视频解码流程与已选择的帧率」 |
| **v70** | 「**恢复 10 月 7 日 preview64 使用的视频解码与显示流程，撤回后续 SurfaceView、输入轮询和批次输出实验**」 |
| **v66** | 「CS11 默认使用 Wi-Fi Direct CarPlay……完善 Wi-Fi Direct **异常退出后的网络恢复**」 |
| **v61** | 「完善**三指下滑识别**：补判**抬指时**才上报的最终位移，并兼容**缺少第三指按下事件**、随后才出现三指移动的情况」 |
| **v53** | 「修复 preview52 在部分车机上新增**双栈设备发现导致 5353 端口自冲突**……如果第一个发现通道已经成功、第二个通道因系统端口限制无法绑定，则**保留可用通道继续连接**」 |
| **v52** | 「解决**蓝牙接收队列满后可能停滞**的问题，**减少触控指令的网络等待**」；「修正已确认 Siri 语音格式的**上行时间戳**」；「热点**优先使用 IPv4**」 |
| **v47** | 「优化 USB 有线授权流程：**合并同一次设备插入的重复权限申请，忽略过期授权结果**；拒绝或等待超时后**不再自动反复弹窗**」 |
| **v45** | 「增加 **ES 文件浏览器设备发现端口冲突**处理……尝试释放一次 ES 后台进程并重试」 |
| **v43** | 「识别 CS11 **原厂硬件降音回调**，避免把它**误判为静音**」 |
| **v41** | 「设置新增**画面帧率**选项：默认 30 帧，可选择 60 帧实验模式」 |
| **v39** | 「为 Android 4.4 补充 **TCP 连接存活检测**，避免手机离开后旧投屏会话长时间占用连接」 |
| **v38** | 「补充 CS11 原车蓝牙服务的已连接设备识别，**厂商接口查询增加限时保护**，避免阻塞自动连接」 |
| **v37** | 「修复手机**提前**连接车机蓝牙和热点时的无线连接恢复逻辑」；「为未完成的无线连接增加**超时恢复**，并处理**首帧早于会话就绪**的情况」 |

> ⚠️ **诚实标注**：EasyPlay 的更新注记是他们自己的宣称。我们**从未在同一台 CS11 上跑过 EasyPlay**（项目铁律：不安装第三方改版），
> 所以下面凡是"效果"层面的判断都是**代码级推断**，不是 A/B 实测 ✗。我会逐条标 `read`（我读了代码）或 `inferred`。

## 1. 版本 / Manifest 事实（old vs new）

| 项 | preview36（旧） | v71（新） | 变化 |
|---|---|---|---|
| `package` | `com.shihab.diplay.legacy` | 同左 | — |
| `versionName` | `0.2.7-lynk-api19-preview36` | **`0.2.7-lynk-api19-71`** | 去掉 preview 前缀 ✓ |
| `versionCode` | 36 | **71** | +35 |
| `minSdkVersion` | 19 | 19 | — |
| `targetSdkVersion` | 28 | 28 | — |
| `compileSdkVersion` | 37 | 37 | — |
| `RELEASE_CHANNEL` | `lynk-api19` | `lynk-api19` | — |
| `DEBUG` / `debuggable` | true / true | true / true | — |
| `usesCleartextTraffic` | true | true | — |
| `extractNativeLibs` | true | true | — |
| 更新源 | `https://easy-obd.oss-cn-beijing.aliyuncs.com/latest.json` | **`https://oss.jxmbws.com/latest.json`** | **换了域名** ✓（安全面，与我们无关 ✗） |

**权限**：逐条比对 `AndroidManifest.xml` → **完全一致，零新增** ✓
（`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、
`BLUETOOTH`/`BLUETOOTH_ADMIN`(maxSdk=30)、`BLUETOOTH_CONNECT`、`RECORD_AUDIO`、`KILL_BACKGROUND_PROCESSES`、
`FOREGROUND_SERVICE`、`RECEIVE_BOOT_COMPLETED`）。
→ **`KILL_BACKGROUND_PROCESSES` 在旧版就有** ✓（不是 v71 为杀 ES 新加的），但 v71 才真正用上（`LegacyEsDiscoveryCompatibility`）。

**组件差异（v71 唯一新增组件）** ✓：

```
+ <provider android:name="com.shilapi.xcertplay.legacy.LegacyArtworkProvider"
+     android:exported="false"
+     android:authorities="com.shihab.diplay.legacy.artwork"
+     android:grantUriPermissions="true"/>
```

其余组件（`LegacyActivity`、`LegacySessionService`、`LegacyMediaKeyReceiver`、`LegacyBootReceiver`、`CarPlayVpnService`）与旧版同名同配置 ✓。
证据：`_analysis/easyplay/v71/resources/AndroidManifest.xml:63-67`。

> 这个 provider 是「专辑封面交给车机」链路的一环（`LegacyArtwork*` 4 个新类 + `LegacyArtworkHttpServer`）。
> **与本项目无关** ✗（我们不做仪表封面，doc 37 已定性）。

## 2. 原生库 / 资产清单 diff

### 2.1 `lib/` —— **零变化** ✓（本节最重要的一条）

| 库 | 旧大小 | 新大小 | 旧 SHA-256(前16) | 新 SHA-256(前16) | 判定 |
|---|---|---|---|---|---|
| `lib/armeabi-v7a/libdiplay_crypto.so` | 126,800 | 126,800 | `7ce5c43f64e3ee74` | `7ce5c43f64e3ee74` | **逐字节相同** ✓ |
| `lib/armeabi-v7a/libdiplay_lwip.so` | 476,232 | 476,232 | `afbf06c86a1963ae` | `afbf06c86a1963ae` | **逐字节相同** ✓ |
| `lib/armeabi-v7a/libdiplay_opus.so` | 488,552 | 488,552 | `59291678bd70f714` | `59291678bd70f714` | **逐字节相同** ✓ |

→ **v71 没有任何新的 crypto/opus/lwip/pacing 原生库**，也没有把现有库重编译 ✗。
**结论：本轮所有可移植项都是纯 Java/Kotlin 层面的，移植不引入任何新的 native 依赖** ✓（这对我们是好消息）。

### 2.2 dex

| 项 | 旧 | 新 | 变化 |
|---|---|---|---|
| `classes.dex` | 1,253,608 (`603c34cd…`) | 1,253,608 (`603c34cd…`) | **逐字节相同** ✓ |
| `classes2.dex` | 2,436,500 (`aba1a0b0…`) | 2,436,500 (`aba1a0b0…`) | **逐字节相同** ✓ |
| **`classes3.dex`** | 10,377,476 (`2771988d…`) | **10,841,008** (`912d1fb1…`) | **+463,532 B** ✓ |
| `assets/ecarx-modern/classes.dex` | 4,860 | 5,036 | +176 B（E03 车机新代 API 垫片） |

→ 自有代码只落在 `classes3.dex`。★ **这给了一个很有用的筛选器**：`classes.dex`/`classes2.dex` 是第三方/支撑库，**未变**。

### 2.3 `assets/` 与 `resources`

| 项 | 旧 | 新 | 判定 |
|---|---|---|---|
| `assets/easyplay/releases.json` | 10,014 | **28,169** | ★ 变更日志（v11→v71），见 §0 |
| `assets/easyplay/about.html` | 829 | 953 | 文案 |
| `assets/licenses/*.txt`（BouncyCastle/Concentus/lwIP/Monocypher/Opus） | 5 个 | **5 个，内容未变** ✓ | — |
| `assets/offline-mfi/identity.pk8` + `certificate.p7b` | 67 / 607 B | 67 / 607 B | 与 doc 24 结论一致 |
| `resources.arsc` | 1,468 | 1,832 | +364 B（只多两张二维码图） |
| `resources/res/drawable-nodpi`, `res/values` | — | 新增 | **仅抖音码/赞赏码** ✗ |

→ **`res/values/strings.xml` 在 jadx 输出里是空的**（`_analysis/easyplay/v71/resources/res/values*` 无文件）✓，
所以**没法用 strings.xml 快速发现新功能**——这就是为什么本报告主要靠 `releases.json` + 代码 diff。
后续做同类分析时，**先读 `assets/**/releases.json`** 是最高性价比的一步 ✓。

## 3. 代码级变化盘点

* 自有命名空间：`com.shilapi.xcertplay.**`（403 个类，+40）、`com.shihab.**`（1）、`com.android.**`（1）。
* **68 个同名类内容有变**（43 个新增类）。
* 单文件变化量 Top（`git diff --no-index` 行数，头 12 名）：

| 文件 | 新增/删除行 | 备注 |
|---|---|---|
| `legacy/LegacySessionService.java` | +2221 / −648 | 无线重连状态机、看门狗、封面 |
| `orchestration/CarPlayController.java` | +811 / −444 | 无线模式选择、音量/焦点接线 |
| `legacy/LegacyActivity.java` | +903 / −294 | 圆角遮罩、显示方案、三指手势 |
| `legacy/LegacyEcarxMediaCenter.java` | +770 / −180 | 仪表媒体/封面（**我们不移植** ✗） |
| `media/MicrophoneUplink.java` | +771 / −251 | ★ 麦克风采集重写 |
| `media/LegacyAudioFocus.java` | +500 / −99 | ★ 焦点/音量重构 |
| `media/AndroidMediaSink.java` | +431 / −32 | ★ 路由接线 + 麦克风开关 |
| `legacy/LegacyConnectionScreen.java` | +458 / −199 | 待连接页 UI |
| `network/CarPlayBonjour.java` | +378 / −258 | 双栈发现 |
| `legacy/LegacyPhoneSelector.java` | +338 / −103 | 厂商已连接设备识别 |
| `transport/Iap2LinkChannel.java` | +290 / −42 | 文件传输独立配额 |
| `network/TcpLiveness.java` | +285 / −23 | ★ 存活检测加固 |

**已确认是 jadx 噪声（不是真变化，别看）** ✗：`legacy/LegacyDisplayProfile.java`（唯一 diff 是 `/* JADX WARN ... 0x00bf */`→`0x00c0`）、
`airplay/AudioPacketCapture.java`、`media/CarPlayMetadataSnapshot.java`（只有默认参数位数变化）、
`network/LwipSessionNetwork.java`、`network/LocalOnlyHotspotInterfacePolicy.java`、`network/Ipv6NcmBridge.java`、
`mfi/Iap2MfiAuthenticationClient.java`（只多了 `throws` 声明）、`media/CarPlayNowPlaying.java`（只多一个字段）。

---

## 4. ★ 排序候选表

排序依据：**① 命中我们当前痛点（音频卡顿 / USB / 无线 / 视频 / CPU&GC）② 收益 ③ 移植成本与风险 ④ API 19 可行性**。

> **行号约定**（重要）：jadx 1.5.6 的这份输出**没有 `// Sources` 注释**，
> 所以本表所有 `file:line` 都是**该文件在此次 jadx 输出里的真实行号**（我用 `read` 工具读出的行号，已用 `Get-Content` 二次核对 ✓）。
> 引用旧版时写 `old:`，引用新版写 `new:`。这与 doc 24/36/37/41 里的行号**不可比**。

### 等级 ★★★（建议近期做：命中痛点、风险低）

---

#### C1. 音量键归属状态机 —— 让原车音量键永远调"当前在响的那路" ★★★

**做什么**：维护一张 `owner(renderer) → (channel, stream, lastPcm)` 表，按 **PHONE > ASSISTANT > NAVIGATION > MEDIA** 的优先级
挑出"当前该被音量键控制的流"，并用带**版本号（token）校验的 `Handler.post`** 把结果投递给主线程回调；
导航提示音有 **350 ms 冷却** 与 **900 ms 空闲过期**，并且要求"导航焦点已拿到"才把导航算作在播。

**证据**（new）：
* `media/LegacyVolumeControlRoutes.java:169-198` `playing(owner, channel, stream)`：
  ```java
  if (channel == AudioChannel.NAVIGATION) { Long l = this.navigationCooldown.get(owner); ... }
  Route previous = this.routes.get(owner);
  if ((previous != null ? previous.getChannel() : null) == channel && previous.getStream() == stream) {
      previous.setLastPcm(now); return;          // 同一 owner 同流 → 只刷新时间戳，不 publish
  }
  this.routes.put(owner, new Route(channel, stream, now)); publish(); scheduleExpiry();
  ```
* `:320-425` `publish()` 优先级比较（`case 1:`PHONE→4、`case 2:`ASSISTANT→3、`case 3:`NAVIGATION→2、`case 4:`MEDIA→1）+
  `:344-354 / :413-424` 两次 `main.post` 都带 `revision` token，回调里 `:427-442 / :444-459` 再校验 `revision == token` 才投递
  → **消除乱序的晚到回调覆盖新状态**。
* `:224-245` `endNavigationPrompt()`：把导航 owner 放进 350 ms 冷却（`:35` `NAVIGATION_COOLDOWN_MILLIS = 350`）后立刻摘除
  → 导航播报结束的"回退"不会来回抖。
* `:252-289` `scheduleExpiry()` + `:291-313` `expireNavigation()`：按 `lastPcm + 900`（`:38` `NAVIGATION_IDLE_MILLIS`）过期。
* 接线：`media/AndroidMediaSink.java:470-482` `onAudioStarted$lambda$29$lambda$28` →
  `playing` / `remove`；`media/AndroidMediaSink.java`（新增方法区）`onAudioCommand(String type)`：
  `if (Intrinsics.areEqual(type, "unduckAudio")) volumeRoutes.endNavigationPrompt();`
  —— **`unduckAudio` 是 AirPlay 协议里真的会来的命令**，用它当"导航播报结束"的信号比猜时间窗可靠 ✓。
* 播放侧真实事件源：`media/LegacyAudioRenderer.java:631-658` 在 `track.write` 成功后，
  对 NAVIGATION 通道**先扫一遍 PCM 是否非全零**，只有真的"有声音"才上报 `playing=true`：
  ```java
  boolean audibleNavigation = this.selection.getChannel() != AudioChannel.NAVIGATION;
  if (!audibleNavigation) { for (int index = offset; index < i; index++) if (bytes[index] != 0) { audibleNavigation = true; break; } }
  ...
  if (audibleNavigation && this.running && this.track == active) outputListener.invoke(channel, volumeControlStream, true);
  ```
  并在 `:680-696` / `:713-731` 轨道释放/停止时上报 `playing=false`。

**为什么对 CS11 有用**：doc 35 已经查明 CS11 上有**独立的导航音量通道**；我们当前是"永远把音量键给音乐"。
这套状态机的价值是：**导航播报期间音量键自动落到导航通道**，播报结束自动回音乐 —— 不需要用户学习、不需要读原车音量键。
而且 `audibleNavigation` 的非零检测正好是他们为 **v43「避免把原厂硬件降音回调误判为静音」** 做的处理（对我们的直读意义：
**不要用"收到导航包"当"正在播导航"，要用"写到 track 的 PCM 非全零"** ✓）。

**移植成本**：**M（6~10 h）**。纯逻辑、无 native、可单测（他们自己的 `LegacyVolumeControlRoutes` 完全可注入 `clock` 与 `changed` 回调 ✓）。
**风险**：
1. `publish()` 会频繁 `main.post`，若我们的 `renderer` 实例多（多路音频流）要确认不抖 —— 他们用 `revision` token + "同流只刷时间戳" 已经压住 ✓。
2. **别把 MEDIA 也塞进 `AudioTrack` 流 12**：`LegacyAudioRouting` 只对 NAVIGATION 用流 12（见 C2），混用会让音乐也走导航音量。
3. `unduckAudio` 在我们的 AirPlay 会话里是否已经解析出来，需要确认（**未核对我们的代码** ✗ `inferred`）。
**API 19**：✅ 兼容（`Handler`/`SystemClock`/`AudioTrack` 全是 API 1~3 的东西）。
**置信度**：`read`（我读完了 `LegacyVolumeControlRoutes.java` 全文 483 行）。
**我们已有吗**：**没有** ✗。我们有导航/音乐独立音量滑块（doc 42 前的既有实现），但**没有"按当前在播内容自动切换音量键归属"**。

---

#### C2. `LegacyAudioRouting` —— 把"选哪条音频流"抽成可注入的策略 ★★★

**做什么**：把"某个通道该用哪个 `AudioTrack`"从散落的 if-else 抽成一个 `Route(kind, legacyStreamType, usage)` 候选列表 +
统一的 `createTrack` / `volumeControlStream`。**关键一条硬编码**：

```java
// new: media/LegacyAudioRouting.java:169-182
if (channel == AudioChannel.NAVIGATION) {
    if (sdk >= 21) {
        preferred = new Route(Kind.NAVIGATION_ATTRIBUTES, null, 12);        // AudioAttributes USAGE_NAVIGATION
    } else {
        preferred = (sdk == 19 && StringsKt.equals(manufacturer, "Freescale", true)
                     && StringsKt.equals(model, "CS11", true)) ? stream(12)   // ★ CS11 专用：STREAM_TTS=12
                    : music;                                                 // 其他机器退回流 3
    }
} else {
    int standard = channel == AudioChannel.PHONE ? 0 : 3;
    Integer num = ((1 > iIntValue || iIntValue >= 11) ? 0 : 1) != 0 ? numValueOf : null;  // streamOverride 夹取
    preferred = stream(num != null ? num.intValue() : standard);
}
return CollectionsKt.distinct(CollectionsKt.listOf(preferred, music));       // ★ 永远带"音乐流"兜底
```

* `:185-202` `createTrack(Route, rate, channelMask, capacity)`：
  `Kind.LEGACY_STREAM` → `new AudioTrack(streamType, rate, mask, 2, capacity, 1)`；
  `Kind.NAVIGATION_ATTRIBUTES` → `:228-232` `NavigationApi21.createTrack`（`AudioAttributes.setUsage(12).setContentType(1)` + `AudioFormat`；
  API<21 直接 `throw new IllegalStateException("Navigation attributes require API 21")` ✓ 有护栏）
* `:204-218` `volumeControlStream(Route)`：`LEGACY_STREAM` → 返回流号；`NAVIGATION_ATTRIBUTES` → `Integer.MIN_VALUE`（= 不设）
* 使用点：`media/LegacyAudioRenderer.java:420-460` `createTrack()` 遍历候选、
  `this.volumeControlStream = LegacyAudioRouting.INSTANCE.volumeControlStream(route);`
  然后 `this.focus.addRouted(candidate2, this.selection.getChannel(), this.volumeControlStream);`
  并打印 `Audio route kind=… stream=… usage=… volumeControlStream=…`（**可诊断** ✓）。
* `:204-218` 与旧版的对比：**旧版把 `streamOverride` 夹在 `1..11`**
  ```java
  // old: media/LegacyAudioRenderer.java
  Integer numValueOf = Integer.valueOf(this.streamOverride);
  int iIntValue = numValueOf.intValue();
  if (((1 > iIntValue || iIntValue >= 11) ? 0 : 1) == 0) numValueOf = null;   // 1..11
  ```
  → **旧版会主动砍掉流 12**（doc 37 §③ 已经点出这个坑 ✗）；**v71 把这条路走通了** ✓。

**为什么对 CS11 有用**：
1. **`STREAM_TTS=12` + `RND(manufacturer=Freescale, model=CS11)` 是正在 CS11 上跑的实现** —— 这是 doc 35「导航音量通道真相」的
   **最直接代码实锤** ✓。比我们靠猜/靠原车行为反推强得多。
2. **「候选列表永远带音乐流兜底」** 是低成本高鲁棒的做法：厂商 ROM 上流 12 若建不出来，自动回音乐而不是静音 ✗→✓。
3. `volumeControlStream` **必须跟着实际建出来的流走**（而不是各自写死）——我们如果加了流 12 但没同步音量控制流，会出现"音量键调不到导航"。

**移植成本**：**S~M（3~6 h）**。我们这个抽象目前散在 `AndroidMediaSink`/`AndroidMediaRenderer` 里，抽出来即可。
**风险**：
1. **`manufacturer == "Freescale" && model == "CS11"` 这种按机型硬编码很脆** —— 我们已确定就是 CS11，可以**直接常量化**（去掉字符串比较更稳）✓。
2. 流 12 在别的 App/车机状态下可能被占用或不被接受 → 必须有 `catch` 回退（他们就是这么写的 ✓）。
3. **`volumeControlStream = Integer.MIN_VALUE` 语义要看清**：那是"不设置音量键归属"，别照抄成 `setVolumeControlStream(Integer.MIN_VALUE)`（会崩）✓
   —— 他们的写法是把它存在字段里、由上层决定要不要调 `Activity.setVolumeControlStream`，**这一步在 jadx 里看不全** ✗ `inferred`。
**API 19**：✅ 这条**就是为 API 19 写的**（`sdk == 19` 分支）✓。
**置信度**：`read`（`LegacyAudioRouting.java` 全文 234 行）。
**我们已有吗**：**部分** —— 我们有导航/音乐多流（doc 35），但**没有"候选+兜底+按流同步音量控制"这套结构**，也**没有把流 12 用于导航**的确认。

---

#### C3. 麦克风采集重写：**单线程 + 修订号（revision）+ MIC 直采 + 预分配帧缓冲** ★★★

**做什么**：把原来"**capture 线程** 和 **silence 线程** 两条独立线程各自持有 encoder/recorder"的写法，
改成**一条 `transmit` 线程 + `modeLock` + 单调递增 `revision`** 的"原子换挡"；并且：
* 采集时**只在"已确认连接到车机"的 CS11 上用 `MIC`（source=1）**，不再先试 `VOICE_RECOGNITION`；
* **帧缓冲只 `new byte[frameBytes]` 一次**（`transmit` 开头），循环内复用；
* `AudioRecord.read(frame, filled, frame.length - filled)` 允许**部分读**并累计到整帧才发包；
* **静音期（capture=false）也把 encoder 建好**（`!capture` 传进 `OpusEncoder`，见 `:361-367`），并按
  `frameDurationNanos` 的**绝对时间轴**补零包 —— 这样取消静音时**没有编码器冷启动**；
* 采集失败（`read` 返回负）→ `onCaptureFailed(revision)` **回调通知上层自动关采集**，而不是死循环。

**证据**（new）：
* `media/LegacyMicrophoneSources.java:38-43`（全文 44 行）：
  ```java
  return (!(sdk == 19 && StringsKt.equals(manufacturer, "Freescale", true)
            && StringsKt.equals(model, "CS11", true))
          && (sdk < 29 || !StringsKt.startsWith(device, "e03", true)))
       ? CollectionsKt.listOf(6, 1)      // 其他机器：先 VOICE_RECOGNITION 再 MIC
       : CollectionsKt.listOf(1);        // ★ CS11(API19) 与 E03(API29)：直接 MIC
  ```
* `media/MicrophoneUplink.java:64` 构造函数新增 `long initialRevision, Function1<Long,Unit> onCaptureFailed`；
  `:119-176` 新增 `Request(capture, revision)`；`:204-345` `start/run/requestCaptureEnabled/setCaptureEnabled` 全走 revision；
  `:347-407` `prepareMode(token)` **先把旧 `Mode` detach 并 close，再建新 encoder/recorder，再在 `modeLock` 内校验 revision 才提交**
  ```java
  Resources resourcesDetachMode = detachMode();
  boolean capture = getCaptureRequested();
  resourcesDetachMode.close();                       // 旧资源确定释放，不与新资源并存
  ...
  if (!this.closed.get() && getRevision() == token) {
      this.encoder = opusEncoder; this.recorder = audioRecordCreateRecorder; ... return true;
  }
  new Resources(audioRecordCreateRecorder, opusEncoder).close();   // 过期 → 丢弃新建的
  return false;
  ```
* `:409-466` `createRecorder(token)`：`mask = channels==1 ? 16 : 12`；
  `new AudioRecord(source, rate, mask, 2, Math.max(minimum * 2, this.frameBytes * 4))`；
  建好后校验 `getState()==1` → `startRecording()` → `getRecordingState()==3` 才算成功 ✓
* `:565-745` `transmit(DatagramSocket)`：`byte[] frame = new byte[this.frameBytes]`（**唯一一次分配**）；
  `:653` `i = recorder.read(frame, filled, frame.length - filled)`；
  `:658-671` 读失败 → `onCaptureFailed.invoke(mode.getRevision())` + `setCaptureEnabled(false)`；
  `:700-726` 静音 → 补零 + `frameDurationNanos` 绝对时间轴 sleep：
  ```java
  long next = this.config.getFrameDurationNanos() + deadline2;
  long now = System.nanoTime();
  deadline2 = next < now ? this.config.getFrameDurationNanos() + now : next;   // 落后就跳，不追
  long wait = deadline2 - System.nanoTime();
  if (wait > 0) Thread.sleep(wait / 1000000, (int) (wait % 1000000));
  ```
* `:755-839` `sendFrame`：新增按窗口统计 `frames / inputNonZeroFrames / sentPackets / encoderEmpty / encodeMaxUs / sendMaxUs`
  （`:828-837` 每 `VideoStats.WINDOW_NS` 打一行）—— **直接可用的上行健康指标** ✓

**对照旧版**（`old: media/MicrophoneUplink.java`，431 行）：旧版是**两个方法两条线程**
（`capture(AudioRecord, DatagramSocket)` 与 `silence(DatagramSocket)`，各在自己的线程里持 `MicrophoneCounters`），
且 `AudioRecord` 在 `start()` 里就建好、`captureEnabled` 是 **final** 字段（不可运行中切换）。
→ **v71 的 revision 机制是为了修"连接中切麦克风"（v44 变更日志）而做的**，属实质性重构 ✓。

**为什么对 CS11 有用（对应我们的痛点 1）**：
1. **`MIC` 直采是 CS11 上唯一正确的音源**（`VOICE_RECOGNITION` 在这台车机上是坏的/不存在的）——
   这条**直接可抄**，而且他们用 `RND` 精确限定到 CS11，说明这是实测结论而不是猜 ✓。
2. **`Math.max(minimum * 2, frameBytes * 4)` 的缓冲下限**对手里 ROM 给很小 buffer 的情况是护栏 ✓。
3. **静音期保持 encoder 热** → Siri/通话**起头不丢字**（我们的 uplink 是首包在采集开始时才建）。
4. **`inputNonZeroFrames` vs `frames`** 是判断"麦克风是不是真在收声"的关键指标 —— 我们现在**没有**这个口径 ✗。
5. `onCaptureFailed → 自动关采集`避免了 `AudioRecord.read` 持续返回负值时的死循环刷屏 ✓。

**移植成本**：**M（6~10 h）**。核心是 revision 状态机 + 单线程化；`LegacyMicrophoneSources` 是 10 分钟 ✓。
**风险**：
1. 我们已有自己的 uplink 实现（含 `libdiplay_opus.so` native Opus），**不要整体替换**，只吸收：`MIC` 直采、单线程 + revision、
   `frameBytes*4` 缓冲下限、静音期保持 encoder、`inputNonZeroFrames` 指标。
2. **`Thread.sleep(wait/1000000, wait%1000000)` 这种"落后就跳到下一个格"的策略在有抖动时会让包时间戳不连续** ——
   他们的 RTP 时间戳由 `MicrophonePacketizer` 的计数器决定，如果不是按绝对时间推就会漂 ✗ `inferred`（我没读 `MicrophonePacketizer` 全量）。
   ⇒ 移植前先确认我们 uplink 的时间戳生成方式**与我们**一致。
**API 19**：✅（`AudioRecord(int,int,int,int,int)` 构造器 API 3+；`getMinBufferSize` API 3+）。
**置信度**：`read`（`MicrophoneUplink.java` 关键区间 340-479、560-849；`LegacyMicrophoneSources.java` 全文）。
**我们已有吗**：**没有**（我们的 uplink 是 native Opus + 自己的线程模型，无 revision / 无 MIC 直采限定 / 无 inputNonZeroFrames）。

---

#### C4. `TcpLiveness` 加固：**不用 `ParcelFileDescriptor.fromSocket()`，改 `getFileDescriptor$()` + `dup()`；并逐项验证** ★★★

**做什么**：给会话 TCP 加 keepalive，但**不再依赖 `ParcelFileDescriptor.fromSocket(socket)`**（这台 ROM 上会返回 null / 抛异常），
而是按 API 分层拿到 fd：API≥29 用 `fromSocket`；**API<29 用反射 `Socket.getFileDescriptor$()` + `ParcelFileDescriptor.dup()`**；
并且**每一个 `setsockopt` 单独 `try/catch`**，最后把"哪几项真的成功了"报出来。

**证据**（new）：
* `network/TcpLiveness.java:13-33` 常量：`IPPROTO_TCP = 6`、`TCP_KEEPIDLE = 4`、`TCP_KEEPINTVL = 5`、`TCP_KEEPCNT = 6`、
  **`TCP_USER_TIMEOUT = 18`**
* `:46-70` `configure()` → `configure$legacy(socket, optionsFactory, diagnostic)`；用 `FunctionReferenceImpl` 把 `openOptions` 当工厂注入
* `:71-135` `configure$legacy`：**逐项 attempt**
  ```java
  if (!attempt("enable", diagnostic, () -> { socket.setKeepAlive(true); return Unit.INSTANCE; })) { report(false,false,diagnostic); return; }
  ...
  boolean idle   = attempt("idle",   diagnostic, () -> { opened.set(4, 10);   return Unit.INSTANCE; });
  boolean interval = attempt("interval", diagnostic, () -> { opened.set(5, 3);  return Unit.INSTANCE; });
  boolean count  = attempt("count",  diagnostic, () -> { opened.set(6, 3);    return Unit.INSTANCE; });
  keepalive = idle && interval && count;
  userTimeout = attempt("user_timeout", diagnostic, () -> { opened.set(18, 20000); return Unit.INSTANCE; });
  ... 无论成败都 attempt("close", ...)
  report(keepalive, userTimeout, diagnostic);
  ```
* `:246-262` `report()`：`"TCP peer health configured idle=10 interval=3 count=3 keepalive=" + keepalive + " userTimeout=" + userTimeout`
  → ★ **这一行就是全套改动的意义**：旧版只报"tuning unavailable"，新版报**每一项到底成没成** ✓
* `:300-345` `openOptions(Socket)`：
  ```java
  if (Build.VERSION.SDK_INT >= 29) {
      duplicate = ParcelFileDescriptor.fromSocket(socket);            // API29+：官方路径
      if (duplicate == null) throw new IOException("Socket duplication failed");
  } else {
      synchronized (socket) {
          if (socket.isClosed()) throw new IOException("Socket is closed");
          Object obj = Socket.class.getMethod("getFileDescriptor$", new Class[0]).invoke(socket, new Object[0]);
          FileDescriptor fd = obj instanceof FileDescriptor ? (FileDescriptor) obj : null;
          if (fd == null) throw new IOException("Socket descriptor unavailable");
          if (!fd.valid()) throw new IOException("Socket descriptor is invalid");
          parcelFileDescriptorDup = ParcelFileDescriptor.dup(fd);      // ★ API<29 的可用路径
      }
  }
  ```
* `:347-360` 设置方式也分层：API≥26 → `Os.setsockoptInt(fd, 6, option, value)`；
  **API<26 → 反射 `libcore.io.Libcore.os` + `libcore.io.Os.setsockoptInt(FileDescriptor,int,int,int)`**
  （`:371-375` `$method.invoke($os, fd, 6, option, value)` —— 注意 **6 是硬编码的 IPPROTO_TCP**）
* `:200-244` `attempt` / `reportFailure`：捕获 `Exception` **和 `LinkageError`**；异常类名要走
  `Regex("[A-Za-z_$][A-Za-z0-9_$]*").matches(...)` 校验、超 128 字符就丢弃 → **防止把 ROM 的诡异异常消息裸打进日志** ✓

**对照旧版**（`old: network/TcpLiveness.java`，58 行 / 3,395 B）：
```java
socket.setKeepAlive(true);
if (Build.VERSION.SDK_INT < 29) { return; }              // ★ API<29 直接放弃 ✗✗
try {
    ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);   // ← 唯一路径，老 ROM 上可能返回 null
    Os.setsockoptInt(pfd.getFileDescriptor(), OsConstants.IPPROTO_TCP, 4, 10);
    Os.setsockoptInt(..., 5, 3); Os.setsockoptInt(..., 6, 3);
    Os.setsockoptInt(..., OsConstants.TCP_USER_TIMEOUT, 20000);
    diagnostic.invoke("TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; no video-idle timeout");
} catch (Exception failure) {
    diagnostic.invoke("TCP peer health tuning unavailable: " + failure.getClass().getSimpleName());
}
```
即旧版**只有一个全局 try/catch + 一条 fd 获取路径**（`fromSocket`），任一 `setsockopt` 失败就整块放弃，
且**无法区分失败在哪一项**——这正是 v71 改成"逐项 attempt + 分层取 fd + 报告每项成败"的原因 ✓。
**重点澄清（避免误判）——已逐行核对旧版全文（`old: network/TcpLiveness.java`，58 行）**：

```java
// old: network/TcpLiveness.java:28-34
public final void configure(Socket socket, Function1<? super String, Unit> diagnostic) throws SocketException {
    Intrinsics.checkNotNullParameter(socket, "socket");
    Intrinsics.checkNotNullParameter(diagnostic, "diagnostic");
    socket.setKeepAlive(true);                      // ← 只做了这一句
    if (Build.VERSION.SDK_INT < 29) {
        return;                                     // ★★★ API<29 直接返回，四个 setsockopt 一个都没执行
    }
    try { ... Os.setsockoptInt(...); diagnostic.invoke("TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; ..."); }
```

✅ **确认：旧版在 CS11（API 19）上只调了 `setKeepAlive(true)`，四处调优全部被 `return` 跳过，
那句 "TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms" 在 API19 上根本不会打印。**
（`setKeepAlive(true)` 只是打开 keepalive，用的还是内核默认 `TCP_KEEPIDLE=7200s` ⇒ **2 小时**才开始探测 ✗✗，
与我们想要的"手机一拔就能发现"完全无关。）

⇒ **v39 变更日志「为 Android 4.4 补充 TCP 连接存活检测」说的就是这件** ✓，
而 **v71 的 `getFileDescriptor$() + ParcelFileDescriptor.dup()` 就是 API 19 的落地方式** ✓✓。
**这是本报告里唯一一条"旧版在 API19 上是空操作、新版才真正生效"的改动。**
2. 参数未变（idle=10 / interval=3 / count=3 / userTimeout=20000ms）—— **变的是"能不能设上去"和"知不知道设上了"**。

**为什么对 CS11 有用**：这是**我们"手机离开后旧会话长时间占连接"的直接解法**。
`TCP_USER_TIMEOUT=20s` 的语义是"数据 20 s 没被 ACK 就判死"，比 keepalive 三件套（10+3×3=19 s 后开始探测）更早收敛；
两者叠加 ⇒ 拔手机后 **≤20 s 内**读到 `ECONNRESET/ETIMEDOUT`，而不是无限 `read()` 挂着 ✓。

**移植成本**：**S（2~3 h）**。一个独立工具类、无依赖、可注入 `Socket` 做单测 ✓。
**风险**：
1. **`getFileDescriptor$()` 是隐藏 API**（`$` 结尾的测试可见性方法）。API 19 的 libcore 上有它，但**厂商改过 libcore 的话可能没有**
   → 必须有 `NoSuchMethodException` 回退（他们包在 `openOptions` 的 try 里，失败只是 `keepalive=false` 并报出来 ✓ 我们要照做）。
2. `ParcelFileDescriptor.dup(fd)` **复制的是同一个内核 fd 的引用**，`close()` 只是把引用计数减一 ✓ 不会关掉 socket（他们确实在 finally 里 close ✓）。
3. **`Os.setsockoptInt` 需要 API 21+**：API19 走 `libcore.io` 反射分支 —— 这条分支**在 jadx 里可见但在 API19 上是否真的被走到我无法运行时验证** ✗ `inferred`。
   ⇒ 移植后**必须用 `keepalive=true userTimeout=true` 这一行日志做上车判据**（他们贴心地把判据留在了日志里 ✓）。
**API 19**：✅ **就是它的目标**（但依赖两条反射路径，见风险 3）。
**置信度**：`read`（`TcpLiveness.java` 全文 320 行，含 `configure$legacy` 与 `openOptions`）。
**我们已有吗**：**没有** ✗。doc 41 的清单里**连"TCP keepalive"都没列**，所以这是个**纯新增**项。
**给 parent 的提示**：⚠️ 这是本报告里**唯一一条"旧版在 API19 上是空操作、新版才真正生效"**的项，价值密度最高。

---

### 等级 ★★（建议排期：命中痛点但成本/不确定性更高）

---

#### C5. USB 授权协调器：**token + 同一次插拔去重 + 附着校验** ★★

**做什么**：把 USB 权限申请从"`requestPermission(device, pi)` 然后等广播"升级成**带身份令牌的协调器**：
每次申请生成 `UUID` token 并写进 `PendingIntent` 的 `data` URI 与 extra；回调时**校验 token 一致 + 设备确实还插着 + 设备身份没变**；
同一次插拔内**重复的 `observe()` 直接吞掉**；拒绝/超时后**不再自动反复弹窗**。

**证据**（new）：
* `transport/IphoneUsbHost.java:40-53` `PERMISSION_TOKEN = "com.shilapi.xcertplay.USB_PERMISSION_TOKEN"`、
  `PERMISSION_TOKEN_PATTERN = Regex("[a-zA-Z0-9-]{1,80}")`
* `:586-601` `requestPermission(device, token)`：
  ```java
  if (!PERMISSION_TOKEN_PATTERN.matches(token)) throw new IllegalArgumentException("Invalid USB permission request token");
  if (!isAttached(device)) throw new IphoneUsbException.DeviceUnavailable("iPhone USB device disconnected", null, 2, null);
  if (this.usbManager.hasPermission(device)) return new PermissionRequest.AlreadyGranted(device);
  this.usbManager.requestPermission(device, permissionPendingIntent(token));
  ```
* `:603-625` `hasAttachedPermission` / `isAttached`：`usbManager.getDeviceList().get(device.getDeviceName())` +
  `Companion.sameAttachment$legacy(usbDevice, device)` → **用"当前枚举到的设备"对比"当初申请的设备"**，而不是只比 `UsbDevice` 对象
  （★ **这一点正好命中我们"iPhone 切 USB 模式后设备身份变化"的场景**，v47 变更日志原话 ✓）
* `:607-625` `parsePermissionResult(Intent)`：**先校验 token 与 `intent.getData()` 都匹配**，再解析结果
  ```java
  if (!Intrinsics.areEqual(intent.getAction(), this.permissionAction)
      || (token = intent.getStringExtra(PERMISSION_TOKEN)) == null
      || !PERMISSION_TOKEN_PATTERN.matches(token)
      || !Intrinsics.areEqual(intent.getData(), permissionUri(token))
      || (device = usbDevice(intent)) == null
      || !this.matcher.matches(device.getVendorId(), device.getProductId())) return null;
  ```
* `transport/IphoneUsbPermissionCoordinator.java`（**新文件**，434 行）：
  * `:28-29` `TIMEOUT_MILLIS = 120000`；`:45-56` `State{PENDING,TIMED_OUT,DENIED,GRANTED}`
  * `:403-409` `schedulePoll`：`postDelayed(poll, PENDING ? 500 : 5000)` → **快轮询一次、超时后退避到 5 s**
  * `:222-283` `observe(device)`：`if (this.host.isAttached(device))` 且
    `previous != null && sameAttachment$legacy(previous.getDevice(), device)` → **直接 return（去重）** ✓
  * `:285-324` `result(...)`：token 不一致 / 设备身份变了 / 已终态 → **全部 return（忽略过期结果）** ✓
  * `:338-360` `isCurrent(device, token)`：token 一致 **且** 状态 GRANTED **且** `hasAttachedPermission` 三重确认
  * `:367-392` `pollPermission()`：先查附着、再查权限、PENDING 且超 120 s → `TIMED_OUT` 并 `onRequired(true)`
    （`onRequired(true)` = **提示用户去手点授权**，而不是自动再弹 ✗✓）

**为什么对 CS11 有用**：usbfs 权限这块我们正在做；
「**iPhone 切 USB 模式 → `UsbDevice` 身份变 → 旧授权结果不该被采信**」以及「**同一次插拔只弹一次窗**」是两条能立即减少用户困扰的行为改进 ✓。

**移植成本**：**S~M（3~6 h）**。纯 Android framework、无 native。
**风险**：
1. **`PendingIntent` + `data` URI 的 token 传递在 API 19 上可用** ✓，但**部分 ROM 的 UsbManager 会重写 PendingIntent 的 data**
   → 他们的 `parsePermissionResult` 会**直接判为无效**（fail-closed，安全但不是我们想要的"授权成功却连不上" ✗）→ 建议**保留一条"无 token 但设备匹配"的兼容回退**。
2. `sameAttachment$legacy` 的具体比较字段**我没读** ✗ `inferred`（在 `IphoneUsbHost$Companion` 里），移植前要读清它到底比了什么
   （`deviceName`？`vendorId/productId`？`getSerialNumber`？—— API19 上 `getSerialNumber()` 常返回 null，用它就会恒不等）。
3. 120 s 超时对"用户正在开车、想等会儿再点授权"偏短 —— 我们可保留更长的可配超时。
**API 19**：✅（`UsbManager.requestPermission(UsbDevice, PendingIntent)` 是 API 12+）。
**置信度**：`read`（`IphoneUsbPermissionCoordinator.java` 全文；`IphoneUsbHost.java` 的 `:586-625` 区间）。
**我们已有吗**：**部分**（我们有 USB 权限流程与 `UsbPermissionSetupTest`，但**没有 token 机制、没有 sameAttachment 校验、没有同插拔去重**）。

---

#### C6. 无线启动看门狗：**90 s 总超时 + 首帧后 30 s 宽限 + 5 s 重查** ★★

**做什么**：无线会话建立后武装一个看门狗；超时后若"已 active 但还没有表面"就 **每 5 s 再查**，
"表面就绪"则给**从 `firstFrameSurfaceReadyAt` 起算的 30 s 宽限**；宽限用完仍无首帧则
**提示 + `stopSession(true, true)` 强制重连**。

**证据**（new `legacy/LegacySessionService.java`）：
* `:91` `WIRELESS_STARTUP_TIMEOUT_MILLIS = 90000`
* `:1397-1429` `armWirelessStartupWatchdog$legacy(phone, token)`：
  ```java
  if (this.wired) return;                              // 有线不走这个看门狗
  Runnable check = () -> {
      if (watchdog != this || generation != token || !automatic || wired || destroyed || closing || !busy || wirelessStartupFrameSeen) return;
      if (getActive()) {
          if (surface == null) { main.postDelayed(this, 5000L); return; }
          long grace = 30000 - (SystemClock.elapsedRealtime() - firstFrameSurfaceReadyAt);
          if (grace > 0) { main.postDelayed(this, grace); return; }
      }
      update(getActive() ? "投屏首帧等待超时，正在重新连接" : "无线连接等待超时，正在重新连接");
      stopSession(true, true);
  };
  main.postDelayed(check, WIRELESS_STARTUP_TIMEOUT_MILLIS);
  ```
* `:1402` `firstFrameSurfaceReadyAt = SystemClock.elapsedRealtime()`（武装瞬间）与 `:2104` 相同赋值（**表面就绪时重置** ✓）
* `:1431-1437` `cancelWirelessStartupWatchdog()`：`removeCallbacks` + 置空 ✓
* `:1453-1464` `startSession(...)`：`if (!wiredSession) armWirelessStartupWatchdog$legacy(phone, token);` ✓

**为什么对 CS11 有用**：我们**当前就卡在"hot-replug 黑屏挂起"**（doc 36）。这条是**超时兜底**而非根因修复，
但它把"无限挂起"变成"30 s 后自动重连" ✓，且有正确的"宽限从表面就绪起算"语义（避免把"用户在车机设置页"误判成故障 ✗）。

**移植成本**：**S（2~3 h）**。
**风险**：`stopSession(true, true)` 的语义（是否清蓝牙/是否保留热点）**取决于我们的会话状态机**，直接照抄可能把好会话也断掉 ✗ —— 只借**时间轴**（90/5/30），触发动作由我们决定。
**API 19**：✅。
**置信度**：`read`（`LegacySessionService.java:1397-1437`、`:2104`）。
**我们已有吗**：**没有** ✗（我们有断连恢复与首帧等待，但没有"首帧等待超时→主动重连"的看门狗）。

---

#### C7. mDNS 双栈发现的 **5353 同端口绑定 + 单栈降级** ★★

**做什么**：要给多个本机地址（IPv4+IPv6）各开一个 mDNS 监听，但**必须共用同一个端口**（否则 mDNS 应答里 advertise 的端口不一致）；
做法是"**第一个 bind 成功拿到实际端口，后续地址都用这个端口**"；若第二个因系统限制绑不上，
**保留已成功的那个继续跑**（单栈降级）而不是整体失败。

**证据**（new）：
* `network/CarPlayDiscoveryPortKt.java:50-90`（`bindCarPlayDiscoveryFamilies`，241 行全文）：
  ```java
  for (final InetAddress inetAddress : CollectionsKt.distinct(addresses)) {
      ... ServerSocket/端口选择 ...
      server.bind(new InetSocketAddress(address, iIntValue));
  }
  ```
* 断言重点在 `AirPlayListenersKt.java:18-56`（**新文件**）：**先 bind 第一个拿端口，后面复用**
  ```java
  ServerSocket serverSocket = (ServerSocket) CollectionsKt.firstOrNull(listeners);
  if (serverSocket != null) {
      Integer numValueOf = Integer.valueOf(serverSocket.getLocalPort());
      if (!(numValueOf.intValue() > 0)) numValueOf = null;
      iIntValue = numValueOf != null ? numValueOf.intValue() : port;     // ★ 复用首个实际端口
  } else iIntValue = port;
  server.bind(new InetSocketAddress(address, iIntValue));
  ```
  失败时 `:47-55` 把已建的 `ServerSocket` 全部 `close()` 再抛 ✓
* 专用异常 `network/CarPlayDiscoveryPortBusyException.java`（14 行）：
  `super("设备发现端口（5353）被其他应用占用", cause)` → **中文、面向用户** ✓
* 单栈降级 + 重试：`CarPlayDiscoveryPortKt` 的默认参数里 `:44-47`
  ```java
  static final Unit bindCarPlayDiscoveryFamilies$lambda$0() throws InterruptedException { Thread.sleep(250L); return Unit.INSTANCE; }
  ```
  （`waitForRelease` 默认实现 = **睡 250 ms 再重试** —— 等上一个进程释放 5353）
* ES 文件浏览器冲突：`network/LegacyEsDiscoveryCompatibility.java`（**新文件**，531 行）：
  `:40-42` `ES_PACKAGE = "com.estrongs.android.pop"`、`MAX_TABLE_BYTES = 262144`、`MAX_TABLE_ROWS = 4096`、
  `:123` `recover(isCurrent, onConflict, onDiagnostic)` —— 只在**确实确认** 5353 冲突且能确认是 ES 占用时，
  才"释放一次 ES 后台进程并重试"，并在无法自动处理时**引导用户去系统设置强停** ✓（v45 变更日志）

**为什么对 CS11 有用**：doc 37 已经记录我们做过 **5353 端口自冲突**的排查；
v71 的 `AirPlayListenersKt` 给了"**多地址必须共用端口**"这个正确形状 ✓，以及"**单栈降级**"这条容错 ✓。
`LegacyEsDiscoveryCompatibility` 对我们**价值低** ✗（CS11 上不一定装 ES，而且杀别人进程风险高）。

**移植成本**：**S（双栈/端口复用 2~3 h）**；`LegacyEsDiscoveryCompatibility` **不建议**（L，且杀进程有产品风险 ✗）。
**风险**：
1. **API 19 上对 IPv6 `InetAddress` 单独 `bind` 常常失败**（`NetworkInterface` 上没有全局 IPv6 时的常见行为）→
   必须确认我们的 socket 工厂（`SessionNetwork.serverSocket()`）在 API19 走的是 `ServerSocket` 还是 `MulticastSocket` ✓ `inferred`。
2. **mDNS 的 5353 需要 `SO_REUSEADDR`**，否则"我们自己的两个 socket"就会互斥 —— 他们的 `network.serverSocket()` 里是否设了 `setReuseAddress` **我没读** ✗ `inferred`。
3. 单栈降级会让"iPhone 只在 IPv6 上应答"的场景静默失败 → 降级时必须**把降级原因写进用户可见/日志** ✓（他们有 `onSingleFamilyFallback` 回调 ✓）。
**API 19**：⚠️ 需核实（`ServerSocket.bind(SocketAddress)` 本身 API 1+ ✓，但 IPv6 行为依赖 ROM）。
**置信度**：`read`（`AirPlayListenersKt.java` 全文；`CarPlayDiscoveryPortBusyException.java` 全文；`CarPlayDiscoveryPortKt.java:44-90`）；
`LegacyEsDiscoveryCompatibility` 只看了轮廓 `inferred`。
**我们已有吗**：**部分**（我们有 Bonjour/mDNS 与无线已验证跑通，但**不确定是否做了"多地址同端口复用"** ✗）。

---

#### C8. AirPlay 会话宽度背压：**控制消息与文件传输分开配额** ★★

**做什么**：iAP2 链路原来只有一个 `controls: ArrayDeque<byte[]>` + `MAX_PENDING_CONTROLS(64)` + `MAX_PENDING_CONTROL_BYTES(1 MiB)`；
v71 把**文件传输（封面等大块）单独拆出一个配额池**（`MAX_PENDING_FILES = 32` / `MAX_PENDING_FILE_BYTES = 256 KiB`），
并给控制队列元素带上 `fileTransfer` / `resetFiles` 标记，`recvControl` 遇到文件包就**继续等**（而不是把它当控制帧返回）。

**证据**（new `transport/Iap2LinkChannel.java`）：
* `:23-33` 常量：`MAX_PENDING_COMMANDS = 64`、`MAX_PENDING_COMMAND_BYTES = 1048576`、
  `MAX_PENDING_CONTROLS = 64`、`MAX_PENDING_CONTROL_BYTES = 1048576`、
  **`MAX_PENDING_FILES = 32`**、**`MAX_PENDING_FILE_BYTES = 262144`**、`RECEIVE_CHUNK_BYTES = 8192`、`RECEIVE_POLL_MILLIS = 100`
* `:140-215` 新增 `SessionPayload(bytes, fileTransfer, resetFiles)` 与 `Command(control, fileTransfer)`
* `:313-340` `recvControl(timeoutMillis)` **循环跳过文件包**、**并扣减 remaining 超时**：
  ```java
  long started = System.nanoTime(); long remaining = timeoutMillis;
  do {
      SessionPayload next = recvSession$legacy(remaining);
      if (next == null) return null;
      if (!next.getFileTransfer() && !next.getResetFiles()) return next.getBytes();
      remaining = RangesKt.coerceAtLeast(timeoutMillis - ((System.nanoTime() - started) / 1000000), 0L);
  } while (remaining != 0);
  return null;
  ```
* `:341-370` `recvSession$legacy(...)`：出队时按类型扣**各自的计数器**（`fileCount/fileBytes` 或 `controlBytes`）✓
* `:344-354` `nextSession...pollFirst()` 同类逻辑；`:540` / `:595` `controls.add(new SessionPayload(new byte[0], false, true, 2, null))`
  = **resetFiles 标记包**，用来告诉上游"文件传输边界到了" ✓

**为什么对 CS11 有用**：这正是把我们「**大块封面/文件把控制队列挤满 → iAP2 控制帧延迟**」这条风险面**结构性**解决 ✓。
我们不做仪表封面 ✗，但**iAP2 里还有其它文件传输类消息**（catalog/资产），所以这条对我们仍然有效 ✓。

**移植成本**：**M（4~6 h）**，改的是我们的 iAP2 队列层，需要回归。
**风险**：`resetFiles` 的语义要在我们的接收侧有对应消费点，否则**标记包会被当空控制帧丢掉**、文件边界信息丢失 ✗。
**API 19**：✅（纯集合逻辑）。
**置信度**：`read`（`Iap2LinkChannel.java` 的常量与 `recvControl/recvSession` 区间）。
**我们已有吗**：**不确定** ✗（我们的 iAP2 链路是否有分类配额，**未核对**）。

---

#### C9. 蓝牙 RFCOMM **入队/出队缺 `notifyAll` 导致"队列满即停滞"的修复** ★★

**做什么**：`ArrayDeque` 做待发队列时，**生产者在队列满时 `lock.wait()`，消费者出队后必须 `lock.notifyAll()` 唤醒它们**；
v71 补上了这两处唤醒 —— 这就是 v52「解决**蓝牙接收队列满后可能停滞**的问题」的落地。

**证据**（new `transport/BluetoothRfcommDuplexStream.java`，diff 后新增）：
```java
this.pendingBytes -= chunk.length;
if (chunk.length <= maxBytes) {
    this.lock.notifyAll();                 // ★ 新增：腾出空间，唤醒等待的发送方
    return chunk;
}
...
this.pending.addFirst(tail);
this.pendingBytes += tail.length;
this.lock.notifyAll();                     // ★ 新增
return head;
```
同文件另有结构性改进：构造函数从"持有一个 `BluetoothSocket`"改成
**`(InputStream input, OutputStream output, Function0<Unit> closeOwnedSocket)`** —— 把"关谁"从流本身解耦 ✓
（`new BluetoothRfcommDuplexStream(BluetoothSocket)` 变成薄包装）。
另有 `:26` 新增字段 `private final Function0<Unit> closeOwnedSocket;`、`:333` `closeOwnedSocket.invoke()` 替代 `this.socket.close()`。

**为什么对 CS11 有用**：**"队列满即永久停滞"是一类非常隐蔽的死锁**（只在链路抖动/大批量时出现）。
如果我们的 RFCOMM 写队列是"满了 wait、出队不 notify"的形状，就会**在无线链路恢复后仍然卡死** ✗。
这条 10 分钟就能自查（grep 我们自己的 `wait()` / `notify` 配对）✓。

**移植成本**：**S（<1 h，如果确实是这个 bug）**。
**风险**：`notifyAll` 换成 `notify` 会漏唤醒；`notifyAll` 放在 `if` 分支里若将来提前 return 会漏 —— 要保证**每条"腾出空间"的出口都 notify** ✓。
**API 19**：✅。
**置信度**：`read`（diff 里的新增行）。
**我们已有吗**：**未核对** ✗ —— **建议 parent 优先花 10 分钟 grep 我们的 RFCOMM/写队列**（性价比最高的即时自查）。

---

### 等级 ★（可选/顺手）

---

#### C10. `VideoFramePool` 默认帧数 **8 → 24** ★

**证据**：`airplay/VideoFramePool.java`（new）合成构造器默认值：
```java
// new
this((i3 & 1) != 0 ? 4194304 : i, (i3 & 2) != 0 ? 24 : i2);   // 4 MiB 预算 / 24 帧
// old
this((i3 & 1) != 0 ? 4194304 : i, (i3 & 2) != 0 ? 8 : i2);    // 4 MiB 预算 / 8 帧
```
`VideoFramePool` 其余逻辑（含 `close()` 幂等）**未变** ✓ —— 这是**我们已移植的那个类**（doc 41 §10.1）。

**为什么对我们有用**：我们从 EasyPlay 抄的默认是 **8 帧 / 4 MiB**。在 720p（≈1.38 MB/帧）下：
* 8 帧 × 1.38 MB ≈ **11 MB** > 4 MiB 预算 ⇒ 实际会被预算卡住；
* 24 帧的意义是"**预算内允许更多并行缓冲**"，对"解码器积压 + GC 抖动"是正收益 ✓。
**但要注意**：预算仍是 4 MiB 没变，所以 24 只是"上限许可"，真实占用仍受 4 MiB 约束 ✓（他们没把预算放大，这个克制是对的）。

**移植成本**：**XS（改一个常量 + 复跑单测）**。
**风险**：`VideoFramePool` 的 `close()` 是幂等的（`AtomicBoolean`），帧数变多**不会**泄漏 ✓；但如果我们的池"每次取不到就新建"，改大只会增加常驻内存 —— 要和 4 MiB 预算一起看。
**API 19**：✅。
**置信度**：`read`（diff 一行）。
**我们已有吗**：**已有该类，但参数是旧值** → 属于"**重新对齐参数**"，不是新特性。
**建议**：先量一次我们的 `_artifacts` 里 `queueMax` / 分配计数（doc 41 §12 有 `DiPlay-VideoStats`），再决定改不改 ✓。

---

#### C11. 三指下滑识别重写 + 触点诊断（**修 3 类漏识别**） ★

**做什么**：三指下滑（进入设置）从"必须在 `ACTION_POINTER_DOWN(5)` 那一刻凑齐 3 指"改成"**`ACTION_MOVE(2)` 或 `ACTION_POINTER_UP(6)` 时也能建立/推进**"，
并补上"**抬指时才上报的最终位移**"；同时引入 `maxPointers` + `diagnosticOutcome` 诊断。

**证据**（new `legacy/LegacyCarPlayTouchHandler.java`）：
* `:261-262`（旧）`if (event.getPointerCount() == 3 && action == 5)` → `:284`（新）`if (event.getPointerCount() == 3)` 内部分 `action == 5 || action == 2`
  ⇒ **兼容"缺少第三指按下事件、随后才出现三指移动"** ✓（v61 原话）
* `:484-500`（新）`trackSwipe`：`if (event.getPointerCount() == 3 && event.getActionMasked() != 5)`
  → `boolean lifting = event.getActionMasked() == 6;` → `if (event.getActionMasked() == 2 || lifting) { ... }`
  ⇒ **抬指（`POINTER_UP`）也算一次判定** ✓（v61「补判抬指时才上报的最终位移」）
* 诊断字段：`:30-31`（新）`private String diagnosticOutcome; private boolean diagnosticReported;`、`:33` `private int maxPointers;`
  `:521-546` `invalidateSwipe(reason)` / `reportDiagnostic()`；
  `:583-596` `clear()` 里 `reportDiagnostic()` 后重置 `maxPointers/diagnosticOutcome/diagnosticReported`
* 上报文本：`:539` `"Touch: maxPointers=" + i + " outcome=" + str`（默认 `"forwarded"`）
* 判定阈值**未变**：`:89` `threshold = 64.0f * density`；`:498` `dy < threshold || dy < Math.abs(dx) * 1.5f` ✓

**为什么对我们有用**：我们 doc 37 §③ 已经抄了"按 `pointerId` 分槽"，
但**三指手势的"事件序列不完整"容错**是**新增**的 ✓。CS11 是电阻/低端触摸，**丢 `ACTION_POINTER_DOWN` 是真实存在的**。

**移植成本**：**S（2~3 h + 单测）**。判定逻辑纯函数，可用合成 `MotionEvent` 序列做单测 ✓。
**风险**：放宽到"`MOVE` 也能建立 swipe"会增加误触发（单/双指被算成三指）→ 他们的护栏是
`CollectionsKt.toSet(ids).size() == 3`（`:303-305` **必须 3 个不同 pointerId**）✓ 这个护栏**必须照抄**。
**API 19**：✅。
**置信度**：`read`（`LegacyCarPlayTouchHandler.java` 的 diff 全文）。
**我们已有吗**：**部分**（分槽有了；`ACTION_MOVE`/`POINTER_UP` 建立与抬指判定**没有**）。

---

#### C12. 「**外缘圆角**」实现：**4 个角小 View 遮罩**（不是全屏 shader） ★

**做什么**：不是给 `TextureView` 加 `Outline`/`ClipPath`（4.4 上会触发昂贵的软件裁剪 ✗），
而是**只在四个角**放 4 个"edge×edge"的小 `View`，各自 `drawPath` 一个抗锯齿的黑色圆角三角，
靠 `Matrix.setScale(±1, ±1)` 镜像复用同一个 Path。

**证据**（new **文件** `legacy/LegacyCarPlayCornerMask.java`，133 行）：
* `:44-54` `onMeasure`：`float radius = Math.min(w, h) / 15.0f;`
  `int edgeSpec = MeasureSpec.makeMeasureSpec((int) Math.ceil(radius), EXACTLY);` → **只给每个角 radius×radius 的尺寸** ✓
* `:56-65` `onLayout`：`corners[0].layout(0,0,edge,edge)`、`[1]` 右上、`[2]` 右下、`[3]` 左下 ✓
* `:28-41` 构造函数：`setWillNotDraw(true)`、`setClickable(false)`、`setFocusable(false)`、
  `setDescendantFocusability(393216)`、`setImportantForAccessibility(2)` → **完全不吃触摸/焦点/无障碍事件** ✓✓（这点很关键）
* `:107-125` `rebuild()`：`path.moveTo(0,0) → lineTo(r,0) → arcTo(arcBounds, -90, -90, false) → close()`，
  然后 `mirrorX/mirrorY` + `postTranslate` 把同一个 Path 翻到四个角 ✓
* `:128-131` `onDraw`：`canvas.drawPath(this.path, this.paint)`，`paint = new Paint(1)`（**ANTI_ALIAS_FLAG**）✓
* 接入：`legacy/LegacyActivity.java:259`
  `frameLayout4.addView(new LegacyCarPlayCornerMask(this), new FrameLayout.LayoutParams(-1, -1));`
  （**在 TextureView 之上叠一层**，`-1` = MATCH_PARENT ✓）

**为什么对 CS11 有用**：我们的**方向盘/车机屏幕外缘是圆角**（doc 41 §6 提过 `isOpaque`），
如果我们直接把 CarPlay 画面全屏铺满，**四角会被车机物理圆角切掉内容**。
这个实现**只画 4 个 radius² 的小方块**（1280×720 → r=48 → 4×48×48 ≈ 9.2K 像素，
对比全屏 921K 像素 = **1% 的填充率** ✓）——**在 4.4 无 RenderThread 的机器上这是最优形状** ✓✓。

**移植成本**：**S（2~3 h，含视觉调参）**。纯 `View`，无依赖。
**风险**：
1. `radius = min(w,h)/15` 是**他们的经验值**（1280→48px）；我们要**按 CS11 实测的物理圆角**调（很可能不同）✗。
2. 遮罩 View 必须是**纯黑**且**不吃事件**；如果误设 `clickable` 会让 CarPlay 四角失效 ✓（他们:`:34` 明确 `setClickable(false)`）。
3. **硬件加速下 `drawPath` 是 GPU 的** ✓；若该 View 落在软件层（`setLayerType(SOFTWARE)`）会变成 CPU 逐像素 ✗。
**API 19**：✅（`Path.arcTo(RectF,float,float,boolean)`、`Matrix`、`Paint(ANTI_ALIAS)` 全是 API 1+）。
**置信度**：`read`（`LegacyCarPlayCornerMask.java` 全文 133 行；`LegacyActivity.java:259`）。
**我们已有吗**：**没有** ✗。
**备注**：这条**不是性能项**，是"**画面不被车机圆角吃掉 + 观感贴原车**"。若我们不做圆角，属于产品选择 ✓ 但成本极低。

---

#### C13. 杂项小件（都能独立做，单项 <1 h）

| # | 做什么 | 证据（new） | 为什么有用 | 风险/API19 |
|---|---|---|---|---|
| C13a | **厂商蓝牙已连接查询加"单飞 + 限时"** | **新文件** `legacy/LegacyVendorConnectionQuery.java`（168 行）：`:27` `FutureTask<Set<String>> inFlight;`、`:69-80` `snapshot()`：`if inFlight != null → 复用`，否则新建 `FutureTask` 提交到单线程 `executor`，`get(waitMillis)` 超时返回空集 | v38 原话「**厂商接口查询增加限时保护，避免阻塞自动连接**」→ **厂商 binder 调用挂死是真实存在的**，我们 doc 37 也借了厂商 BT 通道 | ✅；`.get(timeout)` 注意**别把 `CancellationException` 当成功空集** |
| C13b | **已连接设备地址归一化 + 去重 + 正则校验** | `legacy/LegacyVendorBluetoothHandoff.java:179-215`：`trim()` → `toUpperCase(Locale.US)` → `ADDRESS.matches(address)`（`"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"`）→ **同一个 profile 二次 `isConnected` 复核** → `LinkedHashSet` 去重 | 我们 doc 37 §③ 已抄"释放前校验地址"，但**没做大小写归一**（BT 地址大小写混用是常见坑）✗ | ✅（`Locale.US` 必须显式给，否则土耳其 locale 的 `toUpperCase` 会把 `i`→`İ` ✗✓ 他们已经做对了） |
| C13c | **`SessionNetwork` 多地址 AirPlay 监听端口复用** | `network/AirPlayListenersKt.java`（见 C7） | 与 C7 同一件事的 AirPlay 侧：**多个监听必须同一端口** | ⚠️ 同 C7 |
| C13d | **USB 写"短写"即判失败** | `transport/Iap2UsbSession.java:91-95`：`int n = connection.bulkTransfer(outEndpoint, data2, i, len, ...); if (n != len) throw new IphoneUsbException.DeviceUnavailable("USBMUX write transferred " + n + " of " + len + " bytes", null, 2, null);` | **usbfs 上 bulkTransfer 返回值可能大于请求或短写**；短写必须重试/判失败，**不能当成"写完了"** ✓ | ✅；这条**旧版也有** ✗（不是 v71 新增），但**值得确认我们是否照做** |
| C13e | **`AndroidMediaSink.setMicrophoneCaptureEnabled(enabled, requestToken)`**：带 token 的"运行中切麦克风" | `media/AndroidMediaSink.java`（`:519+` 新增区）：`if (requestToken >= this.microphoneRequestToken) { ... microphoneRevision++; ... }` | 与我们痛点 1 相关：**连接中开关麦克风**不该重启会话 ✓ | ✅ |
| C13f | **`audibleNavigation` 非零 PCM 判定** | `media/LegacyAudioRenderer.java:631-640` | 见 C1；**"有包"≠"有声音"**，导航播报的前导静音不该触发音量键切换 ✓ | ✅ |
| C13g | **`MediaAudioBuffer` 计划表**（**v71 未改** ✗，仅记录形状） | `media/MediaAudioBuffer.java`（全文 111 行，**新旧逐字节相同** ✓）：`HEADROOM_MILLIS=200`、`MIN_START_BUFFER_BYTES=4096`、`MIN_TRACK_BUFFER_BYTES=16384`、`DEFAULT_MILLIS=300`、`presets={300,500,1000}`；`:97-106` `plan()`：媒体流 `trackBufferBytes = (mediaMillis+200)*Bps/1000`、`startBytes = mediaMillis*Bps/1000` | 这是**已知的旧件**，doc 41 §2.1b 已分析过（我们已有等价物）✗ | — |

---

## 5. 明确"不值得/不适用"清单

| 项 | 为什么不做 |
|---|---|
| **`libdiplay_lwip.so` + `NcmUsbBridge`/`Ipv6NcmBridge`/`KernelUsbNetwork`/`LwipSessionNetwork` 全家族** | ✗ 纯有线/NCM（手机插车机当网卡）路线。我们**已明确不做有线** ✓（doc 37 §④）。v71 对这几个文件**只改了 `close() throws IOException` 签名**，零功能变化 ✗ |
| **`WifiP2pGroupManager` / `LegacyP2pRadioController`(新,568行) / `LegacyWifiP2pGroupManager`(新)** | ✗ Wi-Fi Direct 路线。**关键情报**：v71 把 CS11 默认连接方式**从 Wi-Fi Direct 改回了「车机原车热点」**（`:3863-3870` `WirelessHotspotMode.MANUAL` → `CarHotspotStatus.isEnabled()` 检查；changelog v71「**默认使用车机热点连接**」）✓。**这与我们的现状完全一致**（我们就是"车开手动热点"）⇒ **他们的 P2P 代码对我们没有参考价值** ✓，但**"他们试了 WifiDirect 又退回来"这个结论本身有价值** ✓ |
| **`LocalOnlyHotspotManager` / `LocalOnlyHotspotInterfacePolicy`** | ✗ 需要 API 26+；`:3884-3886` 自己就 `throw new IOException("LocalOnlyHotspot requires Android 8 or later; choose the car hotspot")` ✓ |
| **仪表/媒体中心全家桶**：`LegacyEcarxMediaCenter`(+770行)、`LegacyArtworkHttpServer`(新)、`LegacyArtwork`/`LegacyArtworkLoader`/`LegacyArtworkProvider`(新)、`LegacyVehicleArtwork`/`Metadata`/`Presentation`(新)、`CarPlayArtwork`/`CarPlayArtworkTransferEvent`(新)、`LegacyLynkNavigationBridge`、`LegacyClusterSettings`、`LegacyModernMediaBackend`、`ecarx/ModernEcarxObjects` | ✗ 专辑封面推仪表 + 新代 ecarx 媒体服务。doc 37 已定性为「**加分项、非痛点**」，本轮它的代码量最大（+770 行）但**全是这条链路** ✗。**例外**：`LegacyArtworkHttpServer` 里若用了 `ContentProvider`+固定地址做图床，**这个形状**可能有别的用处 —— 但与我们 5 大痛点无关 ✗ |
| **`EasyPlayUpdateRepository` / `EasyPlayVersionSettings` / `about.html` / 抖音码赞赏码 / `ConnectionIllustration` / `ConnectionGlyph` / `LegacyConnectionScreen`(+458行)** | ✗ 自更新器 + 皮肤/UI。`LegacyConnectionScreen` 的 +458 行**全是待连接页重设计**（v57）✗ |
| **`LegacyWheelLearningDialog` / `LegacyWheelDispatcher` / `LegacyWheelAction`** | ✗ 方向盘学习 UI。**唯一值得记的一条**：`legacy/LegacyWheelMappings.java` 的 `SYSTEM_KEYS` **移除了 24/25（`KEYCODE_VOLUME_UP/DOWN`）** ✓ —— 即「**音量键不再由 App 拦截，交回车机系统**」，与 v43「原车音量键由系统直接处理，**不要求学习**」一致 ✓。这条我们**已知**（doc 35）✓ |
| **`LocalAdb`（−219 B）/ `EasyPlayRichText` / `R.java`** | ✗ 无关（`LocalAdb` 在 doc 24 已定性为"死代码"） |
| **`MicrophoneConfig.opusClockRate`（24000/48000，默认 48000）** | △ **半适用**。`:150-156`（new）新增字段 + 校验 `if (!(opusClockRate == 24000 || opusClockRate == 48000)) throw new IllegalArgumentException("Unsupported Opus RTP clock")`。**但 v71 到底是"修了"还是"加了护栏"无法从这处判定** ✗ `inferred`。价值在于"**把 Opus RTP 时钟率显式化并校验**"这个做法 ✓ 值 30 分钟 |
| **`VideoFramePool` 之外的视频侧改动** | ✗ **几乎没有**。`media/LegacyVideoDecoder.java` 的 `configureCodec()` 关键行（`:410` `format.setInteger("max-input-size", 4194304)`、`:355` `nextConfigure = System.nanoTime() + 1000000000`）**与旧版一致** ✓；`AndroidMediaSink` 里 video 部分只有 lambda 编号重排（`decoder$lambda$18`→`decoder$lambda$22`，jadx 噪声）✗；`LegacyDisplayProfile` **零变化** ✗；`MediaCodecSupport.hevcCodecSpecificData` 仍在但**未变** ✗。⇒ **EasyPlay 在视频解码/显示上没有新东西可抄**，而且 v70 明确**撤回了** SurfaceView/输入轮询/批次输出实验 ✓ |

---

## 6. 我们**已经**有的（别重复移植！）

> 依据 doc 41 §10/§11/§12 的落地记录 + 本轮 v71 逐文件 diff 复核。

| 能力 | 我们已有 | v71 里的对应物 |
|---|---|---|
| 视频帧池 + 幂等归还 | `VideoFramePool` + `OwnedVideoFrame`（doc 41 §10.1 #1） | `airplay/VideoFramePool.java`（**仅默认帧数 8→24**，见 C10） |
| 解密直写调用方缓冲 | `AirPlayCrypto.chachaOpenInto`（#2） | `libdiplay_crypto.so` 的 `openIntoNative`（**库逐字节未变** ✗） |
| 接收侧复用 header/body、只解密进池化帧 | `ScreenStream`（#3） | `ScreenStream.java`（本轮无实质 diff） |
| 解码队列 O(1) 记账 | `VideoDecodeQueue` 计数器（#4） | `VideoDecodeQueue` 等价物（本轮无 diff） |
| 所有权契约（`onOwnedFrame` 默认实现，兼容老调用方） | 有（#5） | — |
| 音频 offset 化解密（就地解密、nonce 复用） | `AudioStream`（#6） | `AudioStream.java` |
| 解码器重建 ~1s 冷却 | `a14dedb` | `LegacyVideoDecoder` 的 `nextConfigure = now + 1_000_000_000`（**一致** ✓） |
| **日志门控（lambda 形式 + `debug.diplay.trace`）** | `DiagLog.trace{}`（doc 41 §11） | `LegacyConnectionTrace`（**脱敏白名单**，形状不同） |
| **native Opus 解码** | `libdiplay_opus.so`（从 EasyPlay 借来） | 同一个 `.so`（**逐字节未变** ✗）；`LegacySoftwareOpusDecoder` 纯 Java 兜底 |
| **type 103 缓冲音频** | **有**（`BufferedAudioStream`，实验开关，doc 41 §12.4 实测"不卡了"） | **没有** ✗✗ —— EasyPlay 仍走 type 102 实时 UDP |
| **抖动队列满时丢最旧（skip-forward）+ `skipped/skippedMs` 指标** | **有**（doc 41 §12.3） | **相反**：`LegacyAudioRenderer.java:206` `if (!this.queue.offer(...)) diagnostic("Audio queue full; dropping newest packet")` —— **丢最新** ✗ |
| 音频输出格式变化重建 track | 有（doc 37 §③） | `LegacyAudioRenderer.java:544-563`（`index == -2` → 比较 `sample-rate/channel-count` → `releaseTrack()+createTrack()`；**与我们一致** ✓） |
| iAP2 逐帧 trace 门控 | 有 | `Iap2Session.emitFrameTrace` 等价物 |
| 触摸按 pointerId 分槽 | 有（doc 37 §③） | `LegacyCarPlayTouchHandler`（**新增的是三指序列容错**，见 C11） |
| 蓝牙释放前校验地址 | 有（doc 37 §③） | `LegacyVendorBluetoothHandoff`（**新增的是查询限时+归一化**，见 C13a/b） |
| 编解码器枚举回退 | 有（doc 37 §③） | `LegacyCodecFactory.names(...)`（**未变**） |
| 媒体键去重 + `abortBroadcast` | 有 | `LegacyMediaKeyReceiver`/`LegacyMediaKeyGate`（本轮几乎无 diff） |
| A2DP 释放 watcher | 有 | 无对应物 |
| 无线 CarPlay 在 CS11 跑通（热点自动化、控制通道同址绑定、RFCOMM+Bonjour） | **有**（doc 27/37） | EasyPlay 的 `setWifiApEnabled` 只有 1 处引用（doc 41 §9bis），**v71 还退回了"车机原车热点"** ✓ 我们**领先** |

---

## 7. 建议执行顺序（按性价比，全部可离线完成）

1. **10 分钟自查**（**先做这个**）：grep 我们自己的 RFCOMM/写队列有没有"满了 `wait()` 但出队不 `notify`"（C9）；
   顺手确认我们的 `bulkTransfer` 短写处理（C13d）。→ **这两条可能是"免费"的稳定性收益** ✓
2. **`TcpLiveness` 级别的加固**（C4，2~3 h）：**这是本轮唯一"旧版在 API19 上是空操作、新版才生效"的项** ✓，
   判据就是那行 `keepalive=… userTimeout=…` 日志。
3. **`LegacyAudioRouting` 常量化 + 候选兜底**（C2，3~6 h）：先只做"导航用流 12 + 候选回退音乐流 + 音量控制流跟着走"，
   **不要**同时上 C1（分开 A/B）✓。
4. **音量键归属状态机**（C1，6~10 h）：纯逻辑可单测；先只做 MEDIA/NAVIGATION 两档（PHONE/ASSISTANT 后置）✓。
5. **麦克风三件套**（C3 的取舍版，3~5 h）：`MIC` 直采限定 + `frameBytes*4` 缓冲下限 + `inputNonZeroFrames` 指标；
   revision 单线程化（M）单独排期 ✓。
6. **启动看门狗**（C6，2~3 h）+ **USB 授权协调器**（C5，3~6 h）—— 都命中"用户体验型故障"，可与 3~5 并行 ✓。
7. **圆角遮罩**（C12，2~3 h）与 **三指示别容错**（C11，2~3 h）：观感/易用性小件，成批做 ✓。
8. **`VideoFramePool` 默认 8→24 + 4 MiB 预算复核**（C10，XS）：先看 doc 41 §12 的 `queueMax` 再动 ✓。

**明确不排期**：Wi-Fi Direct 全家桶、LocalOnlyHotspot、仪表/封面/媒体中心、自更新器、UI 皮肤、有线/NCM/lwIP ✗（理由见 §5）。

## 8. 不确定性与免责

1. **行号口径**：本报告所有 `file:line` 来自**本次 jadx 1.5.6 输出**（无 `// Sources` 注释），
   **与 doc 24/36/37/41 的行号不可比** ✗。行号已用 `read` + `Get-Content` 双核对 ✓。
2. **20 个 jadx 非致命错误**：`sources` 目录完整（5258 个 `.java`），未发现缺失的候选类；
   但我**没有逐个比对编译产物**，个别人为无法反编译的方法体（通常是 Kotlin inline/synthetic）可能存在盲点 ✗ `inferred`。
3. **"效果"未实测**：全篇的"为什么有用"都是**代码级推断**。项目铁律不装第三方改版 APK ⇒ **没有 A/B 数据** ✗。
   EasyPlay 的 `releases.json` 是**他们的自述**，本报告只把它当**线索**而非证据 ✓。
4. **未核对的我方代码**：C7（我们的 mDNS 是否多地址同端口）、C8（我们的 iAP2 是否有分类配额）、
   C9（我们的写队列 notify 配对）、C1（我们的 `unduckAudio` 是否已解析）、C13d（我们的短写处理）
   —— 这 5 条**都需要在读我们自己的树之后才能定级** ✗。本报告对它们标的是"**未核对**"而不是"没有" ✓。
5. **API 19 依赖反射的项**：C4（`getFileDescriptor$()` / `libcore.io`）、C5（PendingIntent `data` 是否被 ROM 保留）
   —— 二者都**只在真机上能最终确认**，判据分别写在 C4/C5 的"风险"里 ✓。
6. **域名变化**：更新源从阿里云 OSS 换到 `oss.jxmbws.com`（`BuildConfig.EASYPLAY_UPDATE_URL`）。
   我**没有去探测这个域名**（无必要且属于外联）✗；仅作事实记录，安全评估仍应维持 doc 24 的"不安装"纪律 ✓。

## 9. 证据文件索引

| 用途 | 路径 |
|---|---|
| 新版反编译（本报告行号来源） | `_analysis/easyplay/v71/sources/**`（5258 个 `.java`） |
| 新版 manifest | `_analysis/easyplay/v71/resources/AndroidManifest.xml` |
| **变更日志（最高性价比）** | `_analysis/easyplay/v71-assets/assets/easyplay/releases.json`（v11→v71） |
| 旧版反编译 | `_analysis/easyplay/src/sources/**`（5219 个 `.java`） |
| 旧版 APK | `_analysis/easyplay/EasyPlay-preview36.apk` |
| 本轮 diff 脚本（可复用） | `_analysis/easyplay/diff-v71.ps1`（`-Rel <相对路径> [-Context N]` → `git diff --no-index`） |
| 新版 assets 解包 | `_analysis/easyplay/v71-assets/assets/**` |
| 本报告重点类（new） | `media/LegacyVolumeControlRoutes.java`(483)、`media/LegacyAudioRouting.java`(234)、`media/MicrophoneUplink.java`(964)、`media/LegacyMicrophoneSources.java`(44)、`network/TcpLiveness.java`(320)、`transport/IphoneUsbPermissionCoordinator.java`(434)、`network/AirPlayListenersKt.java`(61)、`network/CarPlayDiscoveryPortKt.java`(241)、`legacy/LegacyCarPlayCornerMask.java`(133)、`legacy/LegacyVendorConnectionQuery.java`(168)、`legacy/LegacyVideoSettings.java`、`media/MediaAudioBuffer.java`(111) |
| 对照（doc） | `docs/_digest/24-EasyPlay领克版逆向与安全评估.md`、`36-黑屏根因与EasyPlay对策.md`、`37-EasyPlay对标分析.md`、`40-无线CarPlay实时负载归因（adb实测）.md`、`41-代码层优化清单（编解码·触摸·日志·GC）.md` |

> **一句话总结**：v71 是**一次"把 v37~v71 的 35 版迭代合并回来的纯 Java 加固版"** —— 没有新原生库、没有新权限、视频侧零变化，
> 但在**音频路由/音量归属、麦克风采集、TCP 存活检测、USB 授权、无线启动看门狗、多地址端口复用、圆角遮罩**这 7 处
> 有真实可抄的代码；其中 **C4（TcpLiveness 在 API19 上从"完全空操作"变成"真的设上了并自证"）** 是本轮单项价值最高的一条。

---

# 决策记录（2026-10-09 离线批次）

> 性质：**落地决策**（不是再分析）。本批只回答"这三条候选到底做不做"，全部结论**离线可复核**，不需要车机 ✗。
> 行号口径：v71 行号 = 本报告 §8.1 的 jadx 输出；我方行号 = 本批次 `CarPlay-CS11` 分支 `cs11-api19` 的
> **工作树**（含本批次改动，`CarPlayHostActivity.kt` 共 4639 行）。与 doc 24/36/37/41 的行号**不可比** ✗。

## 决策 1：C6 无线启动看门狗 —— **不移植** ✓

**v71 的做法**（`read`）：`WIRELESS_STARTUP_TIMEOUT_MILLIS = 90000` 在 `armWirelessStartupWatchdog$legacy` 里
一次性 `postDelayed`（`legacy/LegacySessionService.java:91`、调用点 `:1400-1428`、由会话启动处 `:1462-1463` 装载）；
超时回调**不做总时长重置**，但在会话已 active 时把判据换成"首帧宽限"`FIRST_FRAME_SURFACE_GRACE_MILLIS = 30000`
（`:89`）——surface 还没就绪就 5 s 后再查（`:1413-1415`），surface 就绪后按 `30000 - (now - firstFrameSurfaceReadyAt)`
续期（`:1417-1421`），两者都不满足才 `stopSession(true, true)` 重连（`:1423-1424`）。
宽限的终点是**主屏首帧**：`wirelessStartupFrameSeen = true; cancelWirelessStartupWatchdog()`
（`:3391-3392`）。

**我们的等价物**（`read`，更强）：

| 层次 | 我们的实现 | 与 v71 的关系 |
|---|---|---|
| 首 TCP | `orchestration/FirstTcpWatchdog.kt:22-29`（`StartSession` 后起表，`accepted` 即取消，`sessionEstablished` 兜底取消） | 阈值来自 `network/WirelessStartupPolicy.kt:9` `FIRST_TCP_MILLIS = 30_000`，**比 v71 的 90 s 总超时更紧** ✓ |
| 失败上报 | `orchestration/CarPlayController.kt:899-915` 构造 watchdog，超时 → `WirelessStartupFailure.FIRST_TCP_TIMEOUT` | 等价 v71 的 `stopSession` |
| 退避重试 | `CarPlayHostActivity.kt:3510` → `:3946-3969` `reconnectAfterLoss`；预算 `WirelessStartupRetryBudget.kt:11-13`（2 s 起指数退避、封顶 30 s、`MAX_STARTUP_RETRIES = 5`，`WirelessStartupPolicy.kt:10`），稳定 60 s 后清零（`:11`、`:23-27`） | v71 **没有**等价退避，只有一次重连 ⇒ 我们更完整 ✓ |

**v71 覆盖而我们**未**覆盖的，只剩两处窄场景**（`read`，均为推理非实测 ✗）：

1. **"TCP 已被接受、但会话始终不 active"**：我们的看门狗在 `accepted()` 时即取消（`FirstTcpWatchdog.kt:31-39`），
   之后**没有**任何计时器；这段路上只有事件驱动（`CarPlayController` 的状态回报 / iAP 隧道错误）。
   v71 的 90 s 是一次性总闸，所以它**能**兜住"TCP 通了但会话谈不成"。
2. **"active 且 surface 就绪、但视频输入为零"**：我们的解码器级恢复要求在**已经收到过输入**的前提下才重建
   —— `media/AndroidMediaSink.kt:590`（`outputFramesSinceConfigure == 0 && receivedSinceConfigure > 0`）、
   `:843`（`inputsSinceConfigure++`）、`:959-961`（`NO_OUTPUT_GRACE_NS = 700 ms`、`REBUILD_COOLDOWN_NS = 1 s`）。
   一个"从没喂进过帧"的流不满足 `inputsSinceConfigure > 0`，不会被这条恢复路径接住。

**为什么最终仍然不移植**（两条硬理由）：

- **会话级看门狗在本分支试过，并被主动回退** ✓：`7eaaa630 fix(cs11): rebuild the session when the video throughput collapses`
  → `18865523 Revert "fix(cs11): rebuild the session when the video throughput collapses"`（已 `git log` 核对存在）。
- 同一区域**刚修完**"两次虚假的重连"——`0a7383af fix(cs11): stop reconnecting CarPlay when the display did not actually change`。
  在没有真机实测证据的情况下，再给会话加计时器，等于把刚摁下去的"无故重连"重新引回来 ✗。

**结论**：C6 的**首 TCP 部分我们已覆盖且更紧**；剩下的两处窄场景**等有 CS11 实测日志再定**，
届时优先考虑"把 `FirstTcpWatchdog` 的取消点从 `accepted` 挪到 `sessionEstablished`"（改动面最小、可单测），
而**不是**引入 v71 式的一次性 90 s 总闸 ✓。

## 决策 2：C12 圆角遮罩 —— **不移植** ✓

**事实**（`read` + 全树 grep，见命令）：本树**没有**任何"CarPlay 画面圆角遮罩"。

- grep `clipToOutline|setRoundRect|Outline(` 的全部命中只有 UI 卡片/面板，**没有一处**作用在视频面：
  - `common/src/main/java/com/shilapi/xcertplay/CenterMapOverlay.kt:141-144`（地图卡片圆角：`getOutline` → `setRoundRect` → `clipToOutline = true`）
  - `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:1739`（`if (Build.VERSION.SDK_INT >= 21) clipToOutline = true`，普通视图）
- CarPlay 画面本体是 `CarPlayHostActivity.kt:993-996` 的 `TextureView`（挂 `surfaceTextureListener`，经
  `CarPlayPicture.Binding`，`:997`），上面**只叠了** `:998-999` 的透明 `gestureLayer`（`isClickable = true` + `onHostTouch`）。
  没有任何 Shape/Outline/遮罩覆盖层。

**为什么不做**：v71 的 `legacy/LegacyCarPlayCornerMask.java`(133 行) 是纯观感件，
而我们的视频面是 `TextureView` + 解码器直写 Surface ✓；要做出真圆角就得在 API 19 上
自绘遮罩层（额外一层全屏 View + 每帧合成），**用 CPU/GPU 换观感**——在 i.MX6Q 上这笔账不划算 ✗。
另：CS11 原车屏的圆角由车机外壳/中控盖板决定，遮罩只对"视频内容本身的四个直角"起作用，收益更低。

**结论**：不移植；若将来真要做，判据是"CS11 实拍对比图里视频四角确实露出直角并被用户抱怨" ✓。

## 决策 3：C11 三指手势容错 —— **已移植** ✓

**先纠正一处前提**：任务书写"v71 新增两条容错"，实测是**三条**，且第 3 条（不同 pointerId 护栏）与我们
原先理解的"只检查 pointerCount"正好互补。

**功能本来就有**（我们，`read`）：`CarPlayHostActivity.kt:381` `gestureFingerCount = 3`（用户可配 2..4，
`:1669-1673`，持久化 `:539`/`:792`/`:1757`）、识别器 `:4194-4274`、
阈值 `SETTINGS_SWIPE_DISTANCE_DP = 72` / `SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f`（`:4450-4451`）、
指针分槽 `shared/.../media/CarPlayTouchMapper.kt:17-27`。

**v71 的实现**（`read`，`legacy/LegacyCarPlayTouchHandler.java`）：
`maxPointers = max(maxPointers, pointerCount)`（`:241`）；`pointerCount == 3` 时在 **`action == 5 || action == 2`**
（`POINTER_DOWN` *或* `MOVE`）建立 `Swipe(eventTime, 三个 Origin(id,x,y))`（`:263-285`），
并且**当且仅当三个 pointerId 去重后仍为 3** 才保留候选，否则 `swipe = null` +
`diagnosticOutcome = "invalid_pointer_sequence"`（`:281`、`:286-288`）；
`trackSwipe` 里 `lifting = action == 6`（`POINTER_UP`），`action == 2 || lifting` **同样判定**（`:492-507`），
每个 Origin 都要 `dy >= threshold(64dp) && dy >= |dx| * 1.5f`（`:501-505`），抬指时若不够就
`invalidateSwipe("released_before_threshold")`（`:514-517`）；判成功则 `onSettings.invoke()`（`:511`）。

**我们补齐的三条**（改 `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`）：

| # | 容错 | 落点 |
|---|---|---|
| 1 | 手势可在 **`ACTION_MOVE`** 建立（用户三指一次性滑下、可能永远收不到第三个 `POINTER_DOWN`） | `:4213-4219`（`ACTION_POINTER_DOWN, ACTION_MOVE ->` 同一分支）+ `:4313-4337` `startSettingsSwipeTracking` |
| 2 | **`ACTION_POINTER_UP` 也判定**（手指抬起时补判最终位移） | `:4235-4259`（`moved \|\| lifting`，抬指那帧显式剔除 `liftedId`）+ `:4365-4390` `swipeReachedSettingsThreshold` |
| 3 | 必须是 `gestureFingerCount` 个**不同 pointerId** 的护栏 | `:4316-4321`（`ids.size != gestureFingerCount` 直接不建立跟踪）+ `:392` `gesturePointerIds` 字段 |

**建立事件本身不能同时完成手势**（实现细节，`read`）：v71 在 `MOVE` 上建立候选后**同一帧不判定**（`trackSwipe`
只在候选非空时比较"当前 vs 候选起点"，而候选起点就是这一帧的三个 Origin，`dy` 恒为 0，`:495-505`）✓。
我们照此处理：`MOVE` 建立跟踪后直接 `return`，第一次判定发生在**下一个** `MOVE`/`POINTER_UP` ⇒ 单帧抖动
不可能误开菜单，而真实滑动总有下一帧 ✓。

**起点采用"上一帧坐标快照"而不是 MotionEvent 引用**（实现细节，`read`）：框架对整条手势**复用同一个
`MotionEvent` 对象**，所以"上一帧"必须按值快照（`:4403-4418` `PreviousGestureSample`，`:401` 字段），
否则读到的是本帧坐标 ⇒ `dy` 恒为 0、手势永不成立 ✗。逐指起点逻辑：建立时**已在场**的手指取上一帧坐标，
**随建立事件才出现**的手指暂无起点，首次判定给它记下当前位置并**跳过**（`:4378-4384`），
因此"建立帧携带的行程"不会被记到新手指头上 ✓。

附带修掉一个隐患：质心原来按 **index** 平均、且抬指那一帧仍把已抬起的 index 算进去（旧实现），
现在统一按 **pointerId** 取坐标并显式跳过 `liftedId`（`:4365-4390`，`read`）✓。

**保持不变**（防回归的关键）：`menuOpen` 早退（`:4194`）；建立跟踪时仍 `controller?.sendTouch(emptyList())`
（`:4336`，先给 CarPlay 断触，避免手势被当成 3 指滚动送到地图）；阈值的**方向判据**仍是"向下且
`deltaY >= deltaX * 1.15`"（`:4392-4397`，且**逐指**各判一次）；`gestureFingerCount`、持久化、
`openSettingsMenu()` 动作（`:4253`）均未动 ✓。

**触摸路径如何区分"手势"与"地图多指滚动"**（`read`）：触摸先到手势识别器，`gestureSequenceActive` 期间
整个序列被**吞掉**（`:4273` 提前 `return true`），不会落到 `CarPlayTouchMapper`；
而 CarPlay 侧本来就只上报 **2** 个接触点（`CarPlayTouchMapper.kt:8` `MAX_CONTACTS = 2`），
所以"两指滚地图"永远走转发路径、不可能触发菜单 ✓；三指滚动**在旧代码里就会被吞**（旧逻辑同样在
`pointerCount == 3` 时 `gestureSequenceActive = true`），本轮**没有放宽**这一点：三指横向/向上移动仍
被吞且不打开菜单（方向判据未变）✓。

**本轮新增测试**（`common/src/test/java/com/shilapi/xcertplay/CarPlayHostSettingsTest.kt`，扩充既有类）：

| 用例 | 行 | 覆盖 |
|---|---|---|
| `aSwipeIsEstablishedDuringActionMoveWithoutAFreshPointerDown` | `:117` | ①：`DOWN(1)` → `MOVE(3, y=100)` 建立（此时**不**开菜单）→ `MOVE(3, y=800)` 开菜单 |
| `theSwipeCompletesWhenAFingerLiftsAtActionPointerUp` | `:131` | ②：`MOVE(3, y=150)` 未达阈值不开菜单，随后 `POINTER_UP(3, y=800)` 补判并开菜单 |
| `repeatedPointerIdsAreNotASettingsSwipe` | `:145` | ③：`pointerCount == 3` 但三个槽位都是 id 7 ⇒ 无论如何滑动，菜单**不**打开 |
| `aDownwardSwipeWithTheConfiguredFingerCountStillOpensTheMenu` | `:157` | ④：既有 happy path（含"序列复位后下一手势仍可用"） |
| 回归保留 | `:73`/`:88`/`:104` | 配置 2..4 指均开菜单、错误指数目/非下滑不开、抬指取消跟踪直到下一手势 |

辅助改动：`touch(...)` 增加 `ids: IntArray = IntArray(count) { it }` 形参（`:512-524`），仅用于构造重复 id 事件 ✓。

**本批次验证（离线，`read` 级证据）**：

- 命令：`.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --console=plain --offline`，
  总数从 XML 报告聚合（这两个任务在存在既有失败时**必然** BUILD FAILED ✓）。
- 前 → 后：`:shared` **751 / 3 失败 → 751 / 3 失败**（未动）；`:common` **548 / 6 失败 → 552 / 6 失败**
  （**+4 = 本轮新增用例**，失败集合逐条核对与基线**完全相同**：`CarPlayControllerUsbServiceTest` ×3、
  `UsbPermissionSetupTest` ×3）；新增用例所在类 `CarPlayHostSettingsTest` = **31 tests / 0 failures** ✓。
- **反向检查**：把 `CarPlayHostActivity.kt` 暂时 stash 回原版、只留测试，跑
  `:common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayHostSettingsTest` ⇒ **31 tests / 2 failed**：
  `aSwipeIsEstablishedDuringActionMoveWithoutAFreshPointerDown`（`:125`）与
  `theSwipeCompletesWhenAFingerLiftsAtActionPointerUp`（`:141`）都因 `assertTrue(menuOpen)` 失败
  —— **正是"旧代码不会在 MOVE / POINTER_UP 上开菜单"** ✓；`repeatedPointerIdsAreNotASettingsSwipe`
  与原有用例在新旧两版都通过（前者是旧版**没有**该护栏，后者是必须不回归的既有行为）✓。
  随后 `git stash pop` 还原并重跑全量 ⇒ 上述 552/6 ✓。
- **车机上建议做的动作**（人工判据）：① 三指**一次性**按下并**连续下滑**（不要停顿）⇒ 现在应开设置菜单
  （旧版会漏）；② 三指下滑到一半**先抬一指**再继续 ⇒ 抬指瞬间即应判定（旧版会丢）；
  ③ 三指**横向**滑、三指**向上**滑、两指**在**地图上慢慢滚 ⇒ 菜单**不得**打开（防回归）；
  ④ 设置里把指数改成 2/4，重复 ①②③ 的同构动作 ⇒ 行为随配置同步 ✓。

