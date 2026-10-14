# 27 · 无线 CarPlay 在 CS11 首亮（画面 + 触控）

> 日期：2026-10-07 夜 · 工作区 `CarPlay-CS11` @ 分支 `cs11-api19` · 提交链 `4654121`（热点自动化 + API19 修复）→ `b43ecc8`（AirPlay 数据端口绑定修复）
> 对端：iPhone 14,7 / iOS 26.6.2 / AirPlay 960.13.1 · 车机：领克03 CS11（i.MX6Q / Android 4.4.3 / API19）
> 证据截图：`_artifacts/cs11_port/wireless-first-light-20261007.png`

## 0. TL;DR

**不换车机、不用盒子、纯软件**：CS11 车机自己当 Wi-Fi AP，iPhone 经蓝牙 iAP2 拿到热点配置后加入热点、连上我们的 AirPlay 端口，**iOS 主屏已在车机上显示，触控可用**。实测 `first frame rendered` → `rx=33.9fps shown=16.2fps kbps=3707` → `touch2frame avg=83ms n=13`（均取自实机 logcat）。

本次一共解开**三个**卡点，其中第 2 个不在代码里，是 iPhone 侧的残留状态——这条经验必须记住（见 §3）。

## 1. 卡点一：4.4 无法观测「默认网络」，采样被永久判为「网络在变化」

- **现象**：`hotspot sample rejected: network_changed=true apEnabled=true`，每 15 s 超时一次 → `HOTSPOT_NOT_READY` → 无线永远起不来（蓝牙/iAP2 之前就已通）。
- **根因**：`ManualHotspotInterfaces.sample()` 里 `consistent = before.isSuccess && after.isSuccess && before == after`，而 `before = runCatching { connectivity?.activeNetwork }`。`ConnectivityManager.getActiveNetwork` 是 **API 23**，CS11（API 19）上不存在 → `runCatching` 捕获 `NoSuchMethodError`，**不崩但 `isSuccess` 恒 false → `consistent` 恒 false → 每个采样都被否决**。
- **同类的第二条轴**（潜在，未在本次触发但已修）：`allNetworks` 是 API 21，`wifiUpstreams` 在 4.4 上恒 `null`，而 `selectHotspotInterface` 把 `null` 当作 `upstream_unobservable` 否决。CS11 上因 `legacyApInterfaces()` 能观测到 `wlan0`（`evidence=platform_ap`）而未暴露，但一旦固件 regex 不匹配就会 100% 卡死。
- **修法**：**「不可观测」不等于「在变化」**。`defaultNetworkSampleConsistent(observable, before, after)` 在门闩外直接判一致；`HotspotNetworkSnapshot.upstreamObservable` 同理。两处都补了纯函数单测（含"仍不能授权 eth0/usb0"的反向断言）。
- **教训**：**被 `runCatching` 包住 ≠ 安全**。旧清单（doc 10）把这类归为"异常被捕获＝运行时安全"的噪音，实际它会静默改写布尔判定、让功能永久不可用。

## 2. 卡点二：iPhone 侧残留「有线时期」的 CarPlay 记录（非代码问题）

- **现象**：iAP2 全链正常——iPhone 明确回 `iap2 availability wired=true wireless=true themeAssets=false`、`0x4e0d available=true`，我们也发了 `0x4301 CarPlayStartSession`（含正确 SSID/口令/`wirelessIP=192.168.43.1`/`airPlayPort=7000`），但 **iPhone 既不弹提示、也不连 7000 端口**；`设置 → 通用 → CarPlay → 我的汽车` 里没有本车。
- **根因**：iPhone 里存着**之前有线调试**留下的 CarPlay 配对记录（名字就是 `DiPlay`）。该记录与无线交接冲突，iOS 因此不发起无线 CarPlay。
- **处置**：在 iPhone 上**删除该 CarPlay 记录**，随后立即弹出 CarPlay 提示；接受后 iOS 主动连上我们的 7000 端口。
- **教训**：无线调试前**先清掉 iPhone 上的旧 CarPlay 记录**（尤其做过有线调试的机器）。这与 doc 20 的"先连热点、不行就断开重连"是同一类"对端状态"问题。

## 3. 卡点三：AirPlay 数据端口绑到了这个 ROM 拒绝的地址（`SETUP` 500）

- **现象**：iPhone 已连上并完成 `auth-setup`（MFi 200），`SETUP` 时我们返回 500：
  `java.net.SocketException: Can't bind to a link-local address without a scope id: /::`，
  栈为 `NtpClock.listen` ← `AirPlaySession.openTiming` ← `handleSetup`；随后 `peer EOF`、会话结束。
- **根因**：`serverBindAddress()` 是**为有线写死的**——只找内核 NCM 网卡 `usb1` 上的 `fe80::2`（带 scope），找不到就回落 `::` 全零通配。无线路径没有 `usb1` → 回落到 `::` → 该 ROM 拒绝一切**无 scope 的 IPv6 绑定**（doc 17 记录过同一 ROM 特性）。NTP/事件/屏幕/音频/iAP tunnel 全都用这个函数，所以即使绕过 NTP 也会在下一处炸。
- **修法**：**数据端口绑「控制通道被接入的那个地址」**。`AirPlaySession.start()` 发布 `socket.localAddress`（`withLinkLocalScope` 补回 scope），`teardown()` 清除；`serverBindAddress()` 优先用它，找不到才走原有 `usb1` 逻辑。于是有线仍是 `fe80::2%usb1`（行为不变），无线绑 `192.168.43.1`（iPhone 实际可达的地址）。`media` 包那份重复实现改为委托同一函数。
- **实测**：修复后完整走通 `iAP tunnel(type=130)` → `screen(type=110, dataPort=45905)` → 持续 `200` 心跳 → `first frame rendered`。

## 4. 本次一并落地的能力（回顾）

- **热点自动化**（doc 26）：`LegacySoftApControl` 反射 `getWifiApConfiguration`/`setWifiApEnabled`/`getWifiApState`；设置页在非 BYD 固件也可见；`CarHotspotTethering` 改为 **legacy 优先、本地 ADB 兜底**；失败时恢复被关的 Wi-Fi 客户端。
- **自动采用车机真实热点配置**：`generation=1 manual hotspot adopts live AP configuration ssid='Lynk&Co' security=WPA_PSK`。**实测证明这条必要**：用户手填的口令是 10 位 `1891030057`，而车机固件里存的是 **11 位 `18910300575`**——手填错了，从固件读出来的才对。连接流程不再要求用户手抄热点口令（`carHotspotSelfConfigured()`）。
- **蓝牙尽力开启**（`requestWirelessBluetoothOn`，≤6 s 可中断）。
- **API19 可达性修复**（见提交 `4654121`）：`DiLink51ClusterMonitor.hasAccess`（`OPSTR_GET_USAGE_STATS` 在 4.4 是未知 op，会抛 `IllegalArgumentException` 且 lint 看不见）、`BydCarPlayCall` 的 `android.system.Os`、`AndroidMediaSink` 的 4.4 音频焦点遗留分支重新可达、`OpusEncoder`/`DiPlayActivity` 的两处 pre-21 死代码。

## 5. 流畅度实测：默认 60fps 是「卡顿感」的根源（已修）

用户反馈「触摸滑动不够流畅」。抓取同一组指标（app 自带 `DiPlay-VideoStats`，每 5 s 一行）做 A/B：

| 滑动中指标 | 请求 60fps（默认） | 请求 30fps | 说明 |
|---|---|---|---|
| `rx` → `shown` | 34.7→33.1 / **52.9→28.6** / 46.1→30.5 | 30.5→**31.5** / 29.8→28.6 / 30.0→29.8 | 60fps 下**丢近一半帧**；30fps 下收多少显示多少 |
| `recoveries` | **1~2 每 5 s**（`video backlog exceeded 1 s` → 重建解码器） | **0** | 卡死感＝解码器被反复重建 |
| 码率 | 峰值 **9507 kbps**，常态 8000+ | 峰值 8000，常态 **500~4000** | 空口压力减半 |
| `maxGap`（滑动中） | 119~399 ms | 104~177 ms | 帧间更均匀 |
| 触控 `touch2frame` | 73~105 ms / max 569 ms | 78~102 ms / max 319 ms | 基本持平（独立问题，见下） |

**机制**（代码实锤）：`AndroidMediaSink` 的视频线程每轮只处理一帧；`drainOutput` 对
`ageUs = now - presentationTimeUs > RENDER_MAX_AGE_US(250ms)` 的帧「只解码不上屏」；而某帧从收到到被处理超过
`STALL_RECOVERY_NS(1s)` 时 `queue.discardFrames() + recover()`（重建解码器）。CS11 的 H.264 解码+上屏只能稳定
产出约 30fps，向 iPhone 请求 60fps 就必然积压 → 丢帧 → 周期性重建 → 肉眼卡顿。

**已落地修复**：`AirPlayPersistence.loadFps()` 在 **`SDK_INT < 21` 时默认 30fps**（现代设备仍为 60，无回归面）。
实机上直接把 `xcertplay_airplay/display_fps` 设为 30 即可复现该结论（app 设置里就是「30 fps · 更省负载」）。

**仍未解决（下一步候选）**：触控上行延迟 `touch2frame` 平均 78~105 ms、峰值可达 319~569 ms，其中
`touchSendMax` 就有 106~195 ms —— 即「车机把触控写出去」这一步本身会阻塞。与视频下行共享同一空口，
怀疑是 AP 侧排队/缓冲膨胀（视频突发把上行小包压在后面）。可查方向：触控发送线程与写超时、iAP tunnel 与
事件通道的 TCP 写缓冲、以及空口侧的队列（wired 对照：doc 17 有线触控 avg 84 ms）。

**触控上行路径的代码侧分析（已修一半）**：
- 通道：`sendTouch` → `AirPlayHid.touchReport` → `sendHidReport` → `sendCommand`（AirPlay **事件通道** TCP + ChaCha20 + flush，受 `eventWriteLock` 保护，`tcpNoDelay=true`）。
- 计时口径：`TouchLatencyProbe.onTouchSent(sendStartNs, duration)` —— **`touchSendMax` 就是这一次写的耗时**；`touch2frame` 从「开始写」到「下一帧上屏」。**两者都不含排队时间**，所以排队积压不会体现在指标里。
- 调度：`CarPlayController.sendTouch` 把每次上报丢给 `touchExecutor = Executors.newSingleThreadExecutor()`（**单线程 + 无界队列**）。
- 机制：writes 偶发阻塞 106~384 ms（数值聚集在 ~200ms 附近，像 TCP 重传/RTO；空口被下行视频突发占用时上行小包被压后）→ 期间手指移动事件在无界队列里堆积 → 卡顿结束后**按顺序回放陈旧位置** → 指针"跟不上手指"（长停顿还会在手机上重放一整段旧滑动）。
- **已修**：新增 `TouchDispatchGate`（可单测）：HID 上报是**绝对状态快照**，因此同一"形状"（按下的手指集合不变）的连续上报只保留最新位置；**形状变化（手指按下/抬起、触点数变化）一定入队且保序**，绝不丢转换。`CarPlayController` 用「一个 worker 循环 drain」替代「每次上报一个任务」，并在会话开始/结束时 `reset()`。6 例单测覆盖（含"抬起不会被并入按下""第二根手指是形状变化且保序"）。
- **仍待办**：写阻塞本身（~200ms 级）属空口/协议层，未消除；且**指标仍不反映排队延迟**，建议后续把 `touch2frame` 的起点改为 `MotionEvent` 的事件时间戳（`eventTime`）并加一个"入队到开工"的耗时统计，这样排队积压才能在数据上看见。

## 6. 画面偏糊：逻辑分辨率 1280x720 被放大到 1080p 面板（已定性，待实测）

用户反馈「图标/画面有点糊」。实机数字已经把原因钉死：

| 事实 | 来源 |
|---|---|
| 面板物理分辨率 **1920x1080** | `wm size` → `Physical size: 1920x1080` |
| 但逻辑分辨率被覆盖为 **1280x720** | `wm size` → `Override size: 1280x720`（所以 `screencap` 是 1920x1080、`uiautomator` 坐标却只到 1280） |
| 我们向 iPhone 要的画布只有 **1280x650** | 会话日志 `display=1280x650 negotiated=1280x650 scale=100%`（650 = 720 减状态栏/导航栏） |
| 于是 1280x650 被放大 **1.5 倍**铺到 1080p | 表现为整体发虚（若是码率不足则是马赛克/块效应） |

**app 侧画布是"跟着宿主窗口像素走"的**（doc 07c：「画布尺寸取自宿主窗口像素的偶数对齐值，再乘分辨率百分比」），因此窗口只有 720p 时，**瓶颈不在 app、也不在百分比设置**。

**解法**（doc 14 早已论证："正确姿势：`wm size 1920x1080` + `wm density 240`（1.5×）"）：
- `wm size 1920x1080` → 窗口变 1080p → 画布≈1920x1080 → **与面板 1:1，不再放大**。
- `wm density 240` → dp 空间与现在**等价**（1280/160 = 1920/240 = 8dp 宽），UI 尺寸手感不变、像素密度 1.5 倍。

**代价（必须同测）**：像素 ×2.25 → VPU/GPU 负载上升（doc 14：i.MX6Q 上 720p@60 流畅、1080p30 在射程内）；**无线码率约翻倍**（30fps 下现为 0.5~8 Mbps）→ 2.4GHz 空口可能牺牲流畅度/触控；且 i.MX6Q **无 HEVC 硬解**，省码率这条路不通。这是**清晰 vs 流畅**的取舍，必须实测定档。系统级设置（app 改不了、adb 可设），能否长期保留取决于 ROM 是否重设（doc 14 指出这正是 root 用于「固化」的场景）。

### 6bis. **实测结论（2026-10-07 23:05，已测：1080p 被否决，回到 720p）**

按上面的解法实机切到 `wm size 1920x1080` + `wm density 240`（框架确认 `cur=1920x1080 app=1920x1080`），iPhone 重连后日志确认**画布确实上了 1080p**：`decoder=OMX.Freescale.std.video_decoder.avc.v3.hw-based mime=video/avc size=1920x1010`、`output format requested=1920x1010 coded=1920x1024 stride=1920`。

画面**确实变清晰了**（1:1 不再放大），但代价不可接受——用户滑动地图时每 5 秒一行的统计：

| 时间 | rx | shown | maxGap | shownP50 | shownP95 | dropped | queueMax | kbps | recoveries |
|---|---|---|---|---|---|---|---|---|---|
| 23:06:15 | 28.4 | 16.8 | 256ms | **50ms** | 108ms | 0 | 32 | 3433 | **2** |
| 23:06:20 | 13.8 | 10.2 | **957ms** | 51ms | 115ms | 5 | 24 | 3608 | **1** |
| 23:06:25 | 7.0 | 6.2 | **1795ms** | 50ms | **1411ms** | 6 | 4 | 2956 | 0 |
| 23:06:30 | 22.1 | 16.9 | 771ms | 51ms | 118ms | 1 | 24 | 3741 | 0 |

对照 **720p 基线**：`shown≈30fps`、`shownP50=33ms`、`recoveries=0`、`maxGap 104~177ms`、`kbps 0.5~8M`。

判定依据（三条都指向同一结论）：
1. **上屏节奏垮了**：`shownP50` 33ms→**50ms（≈20fps）**，`maxGap` 达 **1.8 秒**、`shownP95` **1.4 秒**；
2. **`recoveries=1~2`**＝解码器被重建＝肉眼可见卡死（720p 下恒为 0，这是硬指标）；
3. **瓶颈不是空口带宽**：`kbps` 只有 3.4~3.7 Mbps（720p 峰值也能到 8 Mbps）→ 是 **1080p 编码/传输的突发 + i.MX6Q 端 1080p 解码管线**撑不住，加带宽也救不了。

**决定：保持 720p**（`wm size 1280x720` + `wm density 160`，已还原并复核 `cur=1280x720 app=1280x720`）。"糊"是这套硬件上流畅的代价。
**若将来仍想要中间档**：一条命令可试 —— `wm size 1440x810` + `wm density 180`（放大 1.33×、像素 1.27×，比 720p 清楚、比 1080p 便宜得多）；本次已切到该档但用户选择不再试，未采集数据。**改完必须重启 app + iPhone 重连**才会重新协商画布。

**同轮附带收获（音频）**：该轮日志里 **`type=102` 音频流确实开起来了** —— `audio stream type=102 decrypted=300/400/…` 持续递增、`authFailures=0`，`Receive: audio type=102 packets=236 bytes=9912 seqForwardGaps=0 lateOrDuplicate=0 seqGapEvents=0`。即**解密链与接收链正常、无丢序**；但 9912 B/5s ≈ **2 KB/s 太低**，判定为**静音保活流**（iOS 在 CarPlay 会话中保持音频通道），**"能听见声音"仍未被证明**。验证方法：播放音乐后看该窗口的字节率是否升到 ~16 KB/s（128kbps AAC）并人耳确认。

### 6ter. **无线音频实测（2026-10-07 23:09，播放音乐中）—— 数据层全绿**

用户开始播放音频后采集 28 秒，四个层面全部正常：

| 层面 | 实测 | 判读 |
|---|---|---|
| 链路字节率 | **29,617 ~ 31,174 B/s ≈ 240~250 kbps**（静音时仅 ~2 KB/s） | **真音乐内容**，不是保活流 |
| 丢包/乱序 | `seqForwardGaps=0`、`lateOrDuplicate=0`、`seqGapEvents=0` | **零丢失零乱序** |
| 解密 | `decrypted` 稳定递增（1000→1900），`authFailures=0` | 解密链健康 |
| 解码 | `audio decoder output codec=AAC_LC buffers=1000→1650 bytes=4096` | 真在解 AAC |
| 消费 | `queued ≈ buffers + 1~2` | 队列几乎不积压 ⇒ sink 按正确速率消费，无饿死/无堆积 |
| 轻微瑕疵 | `dropped` 2→4（1650 个里 4 个，**0.24%**） | 轻微，后续可盯 |
| 播放期负载 | cpu0 忙 60.9%、cpu1 40.7%、cpu2 41.1%、cpu3 36.8% | **余量充足** |

⇒ **无线音频在数据层已完全打通**（这是首亮会话留下的最后一个"体验硬缺口"）。唯一未闭环项＝**人耳确认**（AudioTrack 即使路由错误也会照常消费，所以数据不能替代听觉）。

### 6quater. **麦克风/通话问题的根因（2026-10-07 23:18 实机抓到）—— 这台 ROM 没有 Opus 编解码器**

用户在车上触发 **Siri**（`audioType=speechrecognition`）后，我们一次抓到了**两个方向同时死掉**的证据：

```
Unable to instantiate a decoder for type 'audio/opus'.
audio decoder configuration failed mime=audio/opus
Audio: decoder stats audioType=speechrecognition codec=OPUS … decoderUnavailablePacketsTotal=257 → 457
Opus microphone encoder unavailable        (MicrophoneUplink.kt:70 ← AndroidMediaSink.onMicrophoneStarted:317)
microphone Opus encoder is unavailable
Microphone: failure type=speechrecognition source=VOICE_RECOGNITION codec=OPUS rate=48000 channels=1 frameMs=20 stage=ENCODER
```

即：**iPhone 为 `telephony`/`speechRecognition` 选了 Opus，而这台 Android 4.4 车机既没有 Opus 解码器（听不到对方/Siri）也没有 Opus 编码器（对方/Siri 听不到你）**。链路其余部分全是好的：`microphone=true` 已广播、`MicrophoneUplink.start` 已执行、`RECORD_AUDIO` 权限正常、`AudioRecord` 与效果链代码就绪。
设备侧复核：`/system/lib/libopus*` 不存在、`libstagefright_soft_opus*` 不存在、`getprop | grep opus` 空、`dumpsys media.player | grep opus` 空；ROM 里唯一的 "opus" 是铃声 `Canopus.ogg`。

**修法（已实现，运行时自适应）**：新增 `shared/.../media/AudioCodecCapabilities.kt` —— 探测 `MediaCodec.createEncoderByType/createDecoderByType("audio/opus")` 是否都可用；`AirPlayConfig` 新增 `opus: Boolean`（默认 true 以保持其它设备行为），`CarPlayHostActivity` 传入探测结果；`AirPlayInfoPlist.audioFormats()` 在无 Opus 能力时**只广播 PCM**（`opus` 位取 0，上下行一起改），让 iOS 改走 LPCM：
- 下行 LPCM → sink 的 LPCM 直通路径（`AudioCodecKind.LPCM`）；
- 上行 LPCM → `MicrophonePacketizer.toWirePcm`（**零依赖，不需要任何编码器**）。
新增测试 `aHostWithoutOpusCodecsAsksForPcmOnTheCallAndMicrophoneStreams`（断言无 Opus 时 `telephony`/`speechRecognition` 的 `audioInputFormats`/`audioOutputFormats` 为 `0x4154`，带 Opus 时仍为 `0x70004154`）。

**下次上车验证口径**（iOS 侧要重新协商，故需重连）：
1. 会话日志里该流应为 `codec=LPCM`（不再是 OPUS），且不再出现 `audio/opus` 失败；
2. `Audio: decoder stats … codec=LPCM outputBuffersTotal>0` ⇒ Siri/对方语音被解出（**能听见**）；
3. `Microphone: start/stats … codec=LPCM` 且帧数递增、无 `stage=ENCODER` 失败 ⇒ 你的语音在发（**对方能听见**）；
4. 走动测试：Siri 说"今天天气怎么样"能正确回答 ⇒ 端到端闭环；再打一个电话双向确认。

### 6quinquies. **音频通道：这台车有独立的导航通道，而我们的默认值指向 AUX（已改）**

`dumpsys audio` 显示 ROM 追加了一批厂商流，其中**导航是独立通道**（用户早先的印象正确）：

```
标准 0~9：VOICE_CALL / SYSTEM / RING / MUSIC / ALARM / NOTIFICATION / BLUETOOTH_SCO / SYSTEM_ENFORCED / DTMF / TTS
厂商追加：10 TUNER_AM  11 TUNER_FM  12 STREAM_NAVI_TTS  13 STREAM_NAVI_NOTIFICATION  14 STREAM_AUX
          15 MIC  16 TBOX  17 MIC_MIX  18 ICALL  19 BCALL  20~22 BLUETOOTH_*  23~25 NOTIFICATION_HINT/WARN_*  26~28 TBOX_*RING  29 PARTIAL_MODE
```

（索引按枚举顺序推断：**前 10 个与 AOSP 完全一致**，其后为厂商追加块；`audio_policy.conf` 里 `AUDIO_DEVICE_OUT_AUX` 是**独立物理输出**，所以 14≠车喇叭路径。）

我们的实现（`AudioChannelMapping.kt` + `AudioApiBridge.kt` + `AndroidMediaSink.channelOverride`）：
- API21+ 走 usage（`USAGE_MEDIA`/`USAGE_VOICE_COMMUNICATION`/`USAGE_ASSISTANT`/`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`）——与 HYLink 的做法一致（HYLink 反编译也只见 `AudioAttributes` + 这三个 usage，且**没有** `AudioRecord`，说明它的通话/麦克风另有其路）；
- 4.4 走 stream 路径：媒体=`STREAM_MUSIC(3)` ✓（所以音乐有声）；`telephony`/`speechrecognition` 也是 3；**导航/提示 = `navigationStreamType`，而它的默认值是 14 ＝ `STREAM_AUX`**（继承自上游 BYD 项目）→ **在本车上很可能落不到导航通道**。
- 隐藏的通道选择 UI：`config_advanced_audio_channel_mapping` 默认 **false**（`common/res/values/config.xml`），所以车机上那个"0~20 数字网格 + 每次点击播测试音"（`DiPlayActivity.playTestTone`）看不到。

**已在车上落地**（无需重编译，直接改偏好，root）：`xcertplay_airplay.xml` 里写入
`navigation_stream_type=12`、`navigation_audio_channel=12` ⇒ 导航/提示改走 **`STREAM_NAVI_TTS(12)`**（属主 10034/660 保持）。
**待验证**：CarPlay 里用 Apple 地图起一次导航，看播报是否出声、是否压过媒体（预期：能听到且不打断音乐）。若 12 不对再试 13（NAVI_NOTIFICATION）。
**若确认 12 正确**：应把 `AirPlayPersistence` 的默认值从 14 改为 12（并按 OEM 标签那样做一次性迁移），同时考虑给 CS11 打开 `config_advanced_audio_channel_mapping` 以便现场用测试音找通道。

## 6sexies. **第二夜（2026-10-07 23:30 ~ 2026-10-08 00:10）：音乐"1 秒就停"、Opus 墙、麦克风独占**

### A. 「点播放 1 秒后自动停」的根因 = **车机主动暂停手机**（与我们的代码无关）

用户现象：CarPlay 下 Apple Music/QQ音乐点播放**响 1 秒就停**；断开蓝牙（车机）则手机本地正常播放。日志铁证（每几秒重复一次）：

```
[AVRCP_PLAYING_STATUS_ID_PLAYING]                       ← iPhone 开始播放（我们这会儿正出声）
ecarx.bluetooth.service.a2dp.avrcp_action_play
BTMusicPlaybackEngine: onA2dpStateChanged: 10, isBtAudio=false, mCurrentAudioFocusState=0
BTMusicPlaybackEngine:920 NotToPlay  notToPlay,status=10
BTMusicPlaybackEngine:922 NotToPlay  current audio is not bt,so pause bt music isPlaying()true
remoteControl(RC_COMMAND_PAUSE) → mAvrcp.reqAvrcpPause() → pause() → pauseNative
[AVRCP_PLAYING_STATUS_ID_PAUSED]                        ← 1 秒后 iPhone 被暂停
```

**机制**：iPhone 同时以 **A2DP（蓝牙音乐）**身份连着车机；车机自带播放引擎（`com.ecarx.multimedia` 的 `BTMusicPlaybackEngine`）有一条策略——**当前音源不是蓝牙时就把蓝牙音乐暂停**。CarPlay 生效时音源是 CarPlay，于是它每 1 秒内就把手机按停。
**为什么盒子没有这问题**：盒子的蓝牙是它自己（iPhone 里显示设备类型 **CarPlay**），媒体音频走 CarPlay 通道、**根本不建 A2DP**；而我们的蓝牙对端是**车机本身**（一台带 A2DP/HFP 的"汽车音响"），iOS 自然按 A2DP 送媒体 → 撞上车机策略。
**为什么"第一次上屏时音乐正常"**：那时 A2DP 尚未挂上（用户后来重连/切换过蓝牙），车机无"蓝牙音乐"可暂停。

### B. 修复 = **我们的 app 主动释放手机的 A2DP**（车的厂商 API）

- 车机**标准** `BluetoothA2dp` 代理不可用（`getProfileProxy` 拿不到连接状态，看护器日志里从未出现 `released`）→ 必须走**车机自带适配 API**。
- 该 API 在 **BOOTCLASSPATH** 里（`/system/framework/ecarx.adapterapi.jar`，从 dexopt 命令行可见）→ 可直接 `Class.forName`。签名用 `dexdump` 从车机 jar 里核实：
  `com.ecarx.xui.adaptapi.bt.Bt.create(Context) : Bt`（PUBLIC STATIC）→ `getA2dp() : IA2dp` → **`reqA2dpDisconnect(String) : Z`**（另有 `reqA2dpConnect`/`isA2dpConnected`/`getA2dpConnectionState`/`pauseA2dpRender`…）。
- 落地：`common/.../PhoneMediaAudioWatchdog.kt`（纯逻辑 `PhoneMediaAudioWatcher` + Android 胶水），挂在 `DiPlaySessionService`（会话存活期）；每 5 秒检查一次（iOS 可能自己重连 A2DP），会话结束再 `reqA2dpConnect` 还给用户。日志实测：
  `bt instance via create(Context)=true` / `adaptapi a2dp=com.ecarx.xui.adaptapi.bt.a2dp.A2dpImpl` / **`reqA2dpDisconnect(9C:92:4F:02:B7:B7)=true`** → **音乐恢复正常（用户确认）** ✓

### C. **无线 CarPlay 的音频广播里必须有 Opus**（不能靠"去 Opus"绕开 4.4 没有编解码器）

我一度把"麦克风输入 + telephony/speechRecognition"改成 PCM-only 想绕开 Opus，结果 iOS **连音频流都不建**（会话只有 `SETUP 130`+`110`，没有任何音频帧）→ **声音留在手机喇叭上播**（用户报"点击播放是手机发出的声音"）。据此定性：**Opus 位不能去掉**（已回退），正确解法是**自带 Opus 编解码器**。

### D. 自带 Opus（已落地并实测编码成功）

- 素材来自 `_analysis/easyplay/`（EasyPlay = DiPlay 的**领克 fork，包名与我们完全一致**）：`libdiplay_opus.so`（**armeabi-v7a，488 KB**，导出 `Java_com_shilapi_xcertplay_media_LegacyNativeOpus_{version,create,encode,decode,close}Native`，内部链真 libopus：`opus_encoder_create`/`opus_encode`/`opus_decoder_create`/`opus_decode`）。
- 落地：`shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so` + 新增 `shared/.../media/LegacyNativeOpus.kt`（**类名与方法名必须与 JNI 完全一致，改了就绑不上**）；`OpusEncoder` 在 MediaCodec 缺失时**自动回退 libopus**（`MicrophoneUplink` 无改动）。许可证 BSD-3：`docs/Opus-LICENSE.txt`。
- 实测：`Opus microphone encoder unavailable`（MediaCodec，如预期）→ **`Opus microphone encoder started on libopus bitrate=64000/96000`** ✓

### E. 麦克风"有了编码器却仍无声音" = **采集被车机语音助手独占**

```
Could not find method android.media.AudioRecord.read …（API23 分支的 verify 噪音，4.4 走 3 参 read ✓）
Opus microphone encoder started on libopus bitrate=64000
Microphone: start type=speechrecognition source=VOICE_RECOGNITION codec=OPUS rate=48000 …
microphone read failed code=-3            ← ERROR_INVALID_OPERATION
Microphone: failure … stage=READ code=-3   captureBytes=0 reads=1 readErrors=1 encodedFrames=0 udpSent=0
```

`-3` 指向**音频 HAL 拒绝这次采集**；结合"车机麦克风长期被 `com.baidu.che.codriver` 的 16 kHz 常驻采集占着"（见 §6quater 的实证），以及**用户记得的社区说法"要用 Siri 语音唤醒就得关掉百度语音助手"**——判为**麦克风独占**。
**已加**：采集源回退阶梯（`VOICE_RECOGNITION → MIC → DEFAULT`，逐源探测并打印 `microphone probe source=X result=N`），下一次上车即可判定是否有源可用。若全被拒，则需在车机上"让出麦克风"（车机设置关语音唤醒；或 root 下循环 `am force-stop com.baidu.che.codriver`，该进程会迅速重生，需实测）。

### F. 仍未闭环：**通话/Siri 的下行**也必须是 Opus

同批日志里 `audioType=speechrecognition codec=OPUS … decoderUnavailablePacketsTotal=457`（`Unable to instantiate a decoder for type 'audio/opus'`）——即"你听不到对方/Siri"。`LegacyNativeOpus.Decoder` 已写好，**下一步把它接进播放器**（离线可做）。

## 7. 性能指标补全（本轮已落地）

为让「顺不顺 / 跟不跟手」可量化（原来 `touch2frame`/`touchSendMax` 都从"开始写"计时，**排队时间看不见**）：

- `TouchLatencyProbe`：改为记录「手指状态被接受」的时刻，随状态经 `TouchDispatchGate`（新增 `TouchState(contacts, enqueuedNs)`）传到 `AirPlaySession.sendTouch`；`touch2frame` 现在**包含排队**，并新增 `touchQueueMax`。
- `VideoStats`：新增 `shownP50/shownP95`（**上屏**间隔分位，区分"典型运动"与"突发"）、`dropped`（被 250ms 年龄规则拒绝上屏的解码帧数）、`queueMax`（解码队列峰值）；`log` 出口改为可注入，便于宿主 JVM 单测断言。
- `AndroidMediaSink`：`drainOutput` 区分"呈现/拒绝陈旧帧"，每轮上报队列深度。
- 测试：`VideoStatsTest`（5 例，含分位、静止不计抖动、丢弃/队列计数、触控从手指起算）+ `TouchDispatchGateTest`（6 例，含 `TouchState` 载荷）。**踩坑记录**：测试里 `5_000 * 1_000_000` 在 Kotlin 按 Int 相乘溢出（5e9 → 705,032,704），导致"窗口未到"而断言失败；已改为按毫秒推进。生产代码同类写法审计为**零命中**。

## 8. 待验证 / 未竟

1. **音频**：**音乐数据层已完全打通**（§6ter：~240~250 kbps AAC_LC、零丢包零乱序）。**麦克风/通话的根因已定位并修复**（§6quater：本 ROM 无 Opus 编解码器 → 改为按能力只广播 PCM），**待上车复测**（Siri 双向 + 打电话双向）。
2. **导航通道**：本车有独立 `STREAM_NAVI_TTS(12)`/`STREAM_NAVI_NOTIFICATION(13)`，我们的默认值 14＝`STREAM_AUX`（疑错）。已在车上把偏好改为 12（§6quinquies），待用 Apple 地图导航播报验证；确认后应改代码默认值并考虑打开通道选择 UI。
2. ~~**清晰度 A/B**~~ **已测并结案**（§6bis）：1080p 确实更清晰，但 `recoveries=1~2`、`maxGap=1.8s`、`shownP50` 33→50ms，**代价不可接受 → 保持 720p**。未采数据的中间档 `1440x810`+`density 180` 留作将来可选项。
3. **性能指标基线 + 节奏器决策**：§7 的新指标已在 1080p/720p 两轮实采（§6bis）——**结论倾向"不做节奏器"**：720p 下 `shownP50=P95=33ms`（节奏本身很稳），瓶颈在链路突发（`maxGap`）与解码重建，节奏器无法解决。
4. **从"热点关闭"到"自动开启"**：首亮时车机热点是**车机自己先开着的**，`LegacySoftApControl.requestEnable()` 只走到 `already up (state=13)`。**"关→开"这条路径仍未实测**；需要把车机热点关掉再触发一次连接。
5. **重启后自愈**：车机重启 + iPhone 重连的完整回归。
6. **触控写阻塞本身**（~200ms 级，疑为 DTIM/省电导致的上行排队）未消除；指标口径已修（`touch2frame` 含排队）。1080p 那轮 `readsOver250Ms=13`（音频 socket 也在被卡）说明是**整条空口/上行**的现象，不是触控特有。
7. **精简进度**：A(CH341)+B(I2C MFi)+D(LocalOnlyHotspot)+E(MapEmbed) = `2b971f1`；**C（BYD/DiLink 全套）+ 领克品牌 = `65e500a`（206 文件、−14091 行，已验收：两源集失败集合与基线逐条一致）**。**已完成**。
8. **已知既存测试失败**（都在 `c67f094` 干净副本上复跑证实，与本轮改动无关）：
   - `:shared` 16 例：`BufferedAudioStreamTest` 10 + `MicrophonePacketizerTest` 3（根因 `android.util.Log not mocked`：`:shared` 未开 `returnDefaultValues`）+ `HotspotJoinNormalizedTransactionTest` 3（STORAGE_FAILED）。
   - `:common` 6 例：`UsbPermissionSetupTest` 3（本机 MSYS `sh` 引号改写）+ `CarPlayControllerUsbServiceTest` 3（测试模拟 USB 服务缺失，`compat.systemService()` 非空返回遇 null 抛 NPE）。
   - 可选改进：给 `:shared` 单元测试开 `unitTests.returnDefaultValues = true`，可让这 13 例"Log not mocked"转为真实断言（或暴露真实缺陷）。

## 9. 复现链（一条 logcat 对照）

```
car hotspot auto-enable: Car hotspot is on
wireless Bonjour services started mode=interface iface=wlan0
hotspot candidate iface=wlan0 index=6 ... evidence=platform_ap ap=yes
hotspot interface confirmed iface=wlan0 index=6
generation=1 manual hotspot adopts live AP configuration ssid='Lynk&Co' security=WPA_PSK
wireless AirPlay listener attached bind=192.168.43.1 port=7000
iap2 rx=0x4e0d wireless-carplay-update available=true
iap2 availability wired=true wireless=true themeAssets=false
iap2 tx=0x4301 carplay-start-session
airplay rx POST /auth-setup cseq=2 → 200
airplay rx SETUP ... → screen stream type=110 dataPort=45905
Video: first frame rendered
video stats rx=33.9fps shown=16.2fps kbps=3707 touch2frame avg=83ms n=13
```
