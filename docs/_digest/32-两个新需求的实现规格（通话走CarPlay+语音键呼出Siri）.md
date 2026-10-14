# 32 · 两个新需求的实现规格（2026-10-08 车机实测后）

## ③ 新缺陷（2026-10-08 车上发现，优先于两个需求）：切到车机主页再回 CarPlay 后黑屏/冻屏

**现象**：CarPlay 画面正常时切到车机主页，再切回 CarPlay ⇒ **黑屏**（无画面）。
**日志证据**（`_artifacts/cs11_port/cs11-wakeword-off-test.log`）：

```
16:44:46  ecarx.launcher3 接管（用户切主页）
16:46:06  DiPlayActivity → 16:46:08 CarPlayHostActivity 恢复（用户切回）
16:46:58  video stats rx=4.4fps shown=2.0fps maxGap=878ms shownP50=957ms dropped=12 kbps=124
16:47:08  video stats rx=7.2fps shown=4.8fps shownP50=42ms kbps=199
```

**判定**：**App 未被杀、AirPlay 会话也未重建**（pid 2956 未变、无新的 `starting controller`），但
**入流被 iOS 掐到 4~7fps / 124~199 kbps**（正常 ~30fps / 3.4Mbps）⇒ 不是我们画不出来，是**源端降级/暂停送流**。
链路：切主页 ⇒ 我们渲染中断（Surface 丢失/不再消费）⇒ iOS 判定配件端卡住而暂停送流 ⇒
切回后**我们没有请求关键帧（IDR）** ⇒ 解码器无新关键帧 ⇒ 一直黑屏（经典 Surface 重建后黑屏）。
注意 16:47:08 的 `shownP50=42ms` 说明**延迟会自己恢复**，但**帧率不恢复** ⇒ 只等是等不回来的。

**修复方向（下次实现，优先级最高）**：
1. `CarPlayHostActivity` 在 `onResume` / `surfaceCreated`（或 `SurfaceHolder.Callback` 的 surface 重建）时
   **显式重新绑定解码器输出 Surface**，并**向 iOS 请求关键帧/重同步视频流**（AirPlay 侧通常有
   `feedback`/`requestKeyframe` 或重发 video SETUP 的手段；先在代码里找 `VideoStream` 是否已有该能力，
   没有就补一条）；
2. 检查 `onPause`/`onStop` 时我们是否**主动停掉了视频消费**（若是，改为继续消费但只不做呈现，可显著降低
   iOS 降级的概率）；
3. 若车机允许，让 DiPlay 在 CarPlay 期间**保持前台**（已有 `DiPlaySessionService` 前台服务；但活动/Surface
   仍会被车机桌面覆盖，所以 1 是主修）。

**用户补充（决定性）**：黑屏是**完全黑**（不是静止的最后一帧、也不是花屏块）⇒ **解码器没有任何输出**
⇒ 不是"iOS 少送帧"，而是**输出 Surface 没挂上/已失效**。

**⚠️ CS11 专属限制（决定了修法）**：`MediaCodec.setOutputSurface` 是 **API 23+**，4.4 上**不存在** ⇒
解码器一旦创建就**无法改挂新 Surface**。因此 Surface 被销毁后**唯一可行的修法是重建解码器**
（或重建整条视频流），而不是"重新 setSurface"。

**因此实现路径（下次照此做）**：
1. 在 `CarPlayHostActivity` 的 `SurfaceHolder.Callback.surfaceCreated`（或等价的 Surface 重建点）里，
   **停掉并重建视频解码器**、用新 Surface 重新 `configure`，然后**向 iOS 请求关键帧/重发 video SETUP**
   （先在代码里确认 `VideoStream`/`AirPlaySession` 是否已有 requestKeyframe 之类能力；没有就补一条反馈）；
2. 首选更省事的替代：**改用我们自己持有的 `SurfaceTexture`**（TextureView 或自建），让 Surface 生命周期
   由我们掌控，从而**避免**"Surface 被销毁 ⇒ 必须重建解码器"这条路；
3. 同时检查 `onPause/onStop` 是否停了视频消费（若停了改为"只不呈现、不停止消费"），降低 iOS 降级的概率。

**验收**：切车机主页 5~10 秒再切回 ⇒ 画面 **1 秒内**恢复，`video stats` 回到 ~30fps / ~3Mbps。


> 来源：车上实测结论——① 车机麦克风被百度语音助手独占（`probe source=6/7/1/0 result=-3`），
> 而**通话时** `VOICE_COMMUNICATION` 能拿到（`result=1920`）；② CarPlay 通话被**车机自带拨号**接管
> （用户看到"跳到车机拨号界面"，对方听不见、我们能听到）。
> 需求：**① 设置里加开关 → 关掉百度那套语音助手（释放麦克风），方向盘语音键改为呼出 Siri；
> ② CarPlay 连接后拨号/通话完全走 CarPlay，不再跳车机。**

## 需求 ②：让通话完全落在 CarPlay（**实现代价小，建议先做**）

### ②-0 接口探测结果（已确认，2026-10-08 深夜）

`dexdump` 读出 `com.ecarx.xui.adaptapi.bt.hfp.IHfp` 的完整方法表（与我们已用的 `IA2dp` 同一族）：

```
isHfpConnected()  getHfpConnectedAddress()  getHfpConnectionState()  isHfpServiceReady()
reqHfpConnect(…)  ★reqHfpDisconnect(…)★            ← 与 reqA2dpDisconnect 完全对应，需求②就靠它
reqHfpDialCall(…)  reqHfpMemoryDial(…)  reqHfpReDial(…)  reqHfpSendDtmf(…)
★reqHfpVoiceDial()★                                ← 走 HFP 触发手机的语音拨号（iPhone 上即 Siri）
reqHfpAnswerCall()  reqHfpRejectIncomingCall()  reqHfpTerminateCurrentCall()
reqHfpAudioTransferToCarkit()  reqHfpAudioTransferToPhone()  muteHfpMic(…)  isHfpMicMute()
startHfpRender()  pauseHfpRender()  registerHfpCallback(…)  unregisterHfpCallback(…)
getHfpCallList()  getHfpRemote{Battery,Signal,Network,Subscriber,Telephony}…  isHfpRemoteVoiceDialOn()
```

**两个结论**：
1. **需求② = 照搬 A2DP 的写法**：`Bt.create(Context) → getHfp() → reqHfpDisconnect(addr)`（会话结束用
   `reqHfpConnect` 连回），并入现有 `PhoneMediaAudioWatchdog` 的同一看护周期即可；
2. **需求①-A 多了一条更稳的 Siri 触发路径**：`IHfp.reqHfpVoiceDial()` —— 走 HFP 让 iPhone 自己起 Siri。
   建议**优先用 CarPlay 原生 `AirPlaySession.invokeSiri()`**（体验最干净），把 `reqHfpVoiceDial()` 作为
   **兜底**（当 CarPlay 的 requestSiri 不生效、或用户关了 CarPlay 音频时）。

### 补充：取消语音助手开关的路线已定

用户在车机内**找不到**关闭语音助手的开关 ⇒ **方案 C 排除**，走 **方案 A**（app 内开关 + rootd 循环守护），
**方案 B（系统应用）**作为后续升级。详见 §①-B。


**原理**：车机自带拨号/电话界面是被**车机的 HFP 连接**唤起的；只要在 CarPlay 会话期间**释放手机的 HFP**，
车机就没有电话可接管，通话自然由 CarPlay 的 telephony 流承载（而那条链路我们已实测可用：
`type=100 audioType=telephony codec=OPUS` + 采集源 `VOICE_COMMUNICATION` 能拿到麦克风 ✓
+ 软件 Opus 解码器已在车机验证 ✓）。

**探测已确认的接口**（同一族、同一个 jar，已在我们 BOOTCLASSPATH 上）：
```
com.ecarx.xui.adaptapi.bt.hfp.IHfp            ← 接口
com.ecarx.xui.adaptapi.bt.hfp.HfpImpl         ← 实现
com.ecarx.xui.adaptapi.bt.hfp.HfpClientCall   ← 通话对象
com.ecarx.xui.adaptapi.bt.hfp.IHfpCallback    ← 回调
```
**下一步要做的一件小事**：用 `dexdump` 读出 `IHfp` 的方法名（对齐 `IA2dp.reqA2dpDisconnect` 的命名习惯，
预期形如 `reqHfpDisconnect(String)` / `isHfpConnected()` / `getHfpConnectionState()`），然后在
`PhoneMediaAudioWatchdog` 里把"HFP 释放/恢复"与现有的 A2DP 释放并列（同一个看护周期、同一个开关）。

**要请你确认的行为取舍**：释放 HFP 后，**车机自带的蓝牙电话将不可用**（通话、来电全部只在 CarPlay 里），
且**手机离开 CarPlay 时会恢复**（我们看护器已有"会话结束再连回"的模式）。若你希望保留车机电话、只在
CarPlay 期间接管，那正是我们的默认行为 ✓。

**验收**：CarPlay 里拨号 → **不再跳车机界面**；对方能听到你（上行源已确认可用）、你能听到对方。

## 需求 ①：设置开关 —— 关掉百度语音助手 + 方向盘语音键呼出 Siri

这条拆成两半，**一半容易、一半需要你定策略**。

### ①-A 方向盘语音键 → Siri（**容易**，代码基本都在）

已有零件：
- `WheelKeyService`：方向盘按键以 `KeyEvent` 进我们进程（`keys.onKey(...)` 走 `WheelZoomKeys` 的策略表）；
- `CarPlayMediaButton.opensSiri(keyCode)`：已判定 **`KEYCODE_VOICE_ASSIST` 就是语音键** ✓（说明车机的语音键确实会作为该 keyCode 到达应用）；
- `AirPlaySession.invokeSiri()`：已实现（发 `requestSiri`，siriAction 2/3）✓。

**要做的**：在按键策略里加一条——`KEYCODE_VOICE_ASSIST` → `session.invokeSiri()` 并**消费**该事件
（对照 `WheelZoomKeys.Action.CONSUME` 的既有写法）。**风险低**（纯新增映射），可与设置开关联动
（开关关闭时保持原样，即不拦截）。

### ①-B 关掉百度语音助手、释放麦克风（**需要你选策略**）

已实测的约束：
- `pm disable com.baidu.che.codriver` **无效**（返回 0 但不生效，进程照样重生）✗；
- `am force-stop` 有效但**几秒后自启**，所以要"**循环停**"（`tools/cs11_release_mic.sh` 已备好，默认循环 3 分钟）。

**三条可选路线**：

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A. root 循环守护（推荐先做）** | app 在开关打开时，把"循环强停"命令写进车的 **rootd 队列**（`/data/.android/rootcmd.sh`，该目录可写）并定期续命 | 不改系统、立刻可用、可随时关 | 依赖 root 链（**每次开机需重新点火 root**，doc 16 的那条链）；每 2 秒一次 force-stop 略糙 |
| **B. 装成系统应用** | 用 root 把我们的 APK 放进 `/system/priv-app/` 并重启，然后 app 自己就能调 `PackageManager.setApplicationEnabledSetting`（≡ `pm disable`）✓ | 干净、持久、**顺带能拿到方向盘按键与前台特权** | 需要一次性 root 安装 + 重启；之后升级要走系统应用路径 |
| **C. 车机设置里手关"语音唤醒"** | 不写代码，用厂商自己的开关 | 最干净、永久 | 需要车机有该开关（你记得有）；**不是 app 内开关**，无法与你要求的"CarPlay 软件设置里加开关"一致 ⇒ 可作为**推荐给用户的提示文案** |

**建议**：**A 先落地**（app 内开关 + rootd 循环守护，满足"设置在 CarPlay 软件里"的诉求），
把 **B** 作为后续"更干净"的升级路径；同时**保留 C 作为提示**（设置项下方写一句"若车机自带语音唤醒开关，建议一并关闭"）。
⚠️ **注意**：麦克风是"单客户端"资源——**只要百度那套被停住，我们的采集源阶梯任一源都可能被允许**
（届时 `VOICE_RECOGNITION` 或 `VOICE_COMMUNICATION` 皆可），所以①-B 是**需求①的真正前置**：
不放出麦克风，方向盘键呼出 Siri 也只能靠手机麦克风（如现在这样"能用但不是车机麦"）。

### ① 的验收
- 开关打开 → 日志出现"voice assistant suppression active"（新增诊断行）+ `microphone probe … result>=0`；
- 按方向盘语音键 → **呼出 Siri**（CarPlay 画面出现 Siri），且**不再是"小度小度"**；
- 高德语音指令**能识别**（车机麦克风真的被我们拿到）——这是本次车上未闭环的最后一格。

## 实施顺序建议（都不需要重新摸接口）
1. **② 的 `IHfp` 方法名探测 + 并入看护器**（半天内可完成、可离线写完，上车一次验证）；
2. **①-A 语音键 → `invokeSiri()`**（纯新增映射，可与设置开关同批）；
3. **①-B 方案 A**（设置开关 + rootd 循环守护脚本的安装与续命）；
4. 视情况升级到 **①-B 方案 B（系统应用）**。
