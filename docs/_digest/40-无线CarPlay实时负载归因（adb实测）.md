# 39 · 无线 CarPlay 实时负载归因（adb 实测，2026-10-08 夜）

> 目的：回答「车机当前最大的负担是什么，为什么 CarPlay 不够顺滑」。
> 方法：**全部为 adb 实时采样**（`/proc/stat`、`/proc/interrupts`、`/proc/net/dev`、`dumpsys cpuinfo`、
> `/proc/<pid>/task/*/stat` 逐线程、`DiPlay-VideoStats`/`DiPlay-AudioStats` 流式抓取、`kill -3` Java 栈、
> `run-as` 读 app 私有 prefs）。**不引用历史文档结论**，与旧结论冲突处以本轮实测为准。
> 设备：CS11 `0123456789ABCDEF`（i.MX6Q 四核 A9 @996MHz、Android 4.4.3、屏幕 override 1280×720/160）。
> 会话：**无线** CarPlay（车机当 AP，`wlan0` 承载 AirPlay 流；`usb0` 几乎无流量），音乐播放中，屏幕静止→人工滑动。

## 0. 一句话结论

**没有单一资源被打满，但整机在滑动时已经跑到 82%，而"顺滑"是被三件事一起吃掉的：**
① 厂商常驻应用（应用商店 24–38% 单核等）先占掉约 0.5–0.7 核；
② **所有外设中断都钉在 CPU0**（车机内网以太网常驻 1,100 次/秒 + 滑动时 Wi-Fi SDIO 852 次/秒 + ecspi + USB），
   滑动时 CPU0 84%（其中内核 sys+softirq 占 52%），app 又没绑核，收包/解码只能跟它们抢；
③ 我们 app 自己 86–126% 单核，其中真正把"帧间隔"拉出 80–150ms 的是 **Dalvik GC**（滑动时每 1.2–3s 一次、
   每次阻塞线程 37–67ms）。而上限本身只有 **30fps**（`display_fps=30`）。

## 1. 关键实测数据

### 1.1 空闲态（画面静止，仅音乐；`rx≈0.8–2fps`，`kbps 45–830`）

| 指标 | 实测 |
|---|---|
| 整机 CPU | c0 33–42%，c1–c3 25–32%（aggregate ≈31%）；load 2.7–4.6 |
| app（pid 19791 `com.shihab.diplay.hudtest`） | **26–28% 单核** |
| app 热点线程 | carplay-audio 5.0、gle.aac.decoder 3.7、carplay-video 3.7、MediaCodec_loop 3.4+1.2、pool-10 3.0、airplay-audio-rx 1.5、main 1.4、GC 1.4 |
| 厂商常驻 | **com.ecarx.appstore 24–28%（25% user）**、IHU_CarSignalService.elf 7.1–7.3%、system_server 6–7%、surfaceflinger 2–2.3% |
| 中断（全部落 CPU0，CPU1–3 的 IRQ 列恒为 0） | **ethernet `2188000.ethernet` 1,085–1,132/s**、ecspi 288–350/s、mmc1(Wi-Fi) 105–172/s、USB 75–114/s、mmc2 25–41/s、sdma 129–217/s |
| 网卡 | eth0 恒定 **1.7–1.8 Mbps / ~740 pkt/s**（车机内网流量，与我们无关）；wlan0 rx 0.3 Mbps；usb0 ≈0 |
| app 日志量 | **55 行/秒**（`xcertplay-usb` 37/s、`DiPlay-Cluster` 11.7/s、厂商 `DimInteractionImpl` 5.5/s）→ 30 秒填满 256KB 主缓冲区（本轮因此丢过两次采样窗口） |

### 1.2 真实滑动期（用户手指连续滑动，21:19:57–21:20:12）

视频统计（`DiPlay-VideoStats`，5 秒窗口）：

```
rx=28.5fps shown=28.1fps maxGap=122ms shownP50=31ms shownP95=83ms dropped=0 queueMax=5 kbps=2597
   touch2frame avg=63ms max=198ms n=88 touchSendMax=161ms touchQueueMax=103ms
rx=26.8fps shown=27.2fps maxGap=151ms shownP50=32ms shownP95=80ms dropped=0 queueMax=3 kbps=2142
   touch2frame avg=56ms max=163ms n=50 touchSendMax=133ms touchQueueMax=44ms
```

| 指标 | 实测 | 含义 |
|---|---|---|
| rx vs shown | **26.8–28.5 fps ≈ 持平** | 收到多少就上屏多少，**没有积压**（queueMax 3–5） |
| dropped | **0** | 250ms 老化规则一帧都没丢 |
| **shownP95 / maxGap** | **80–94ms / 122–151ms** | 平均 27fps（≈37ms/帧）里，5% 的帧间隔 2–4 个帧周期 → **肉眼卡顿就在这里** |
| 分核 | c0 **84.1%**（sys 36.1 + softirq 16.0 + irq 7.7）、c1 82.0%、c2 80.7%、c3 81.1%，aggregate 82% | 四核一起忙，**没有余量吸收抖动** |
| app 总占用 | **126% 单核**（另一窗口 85%） | 见下 |
| 厂商 | **appstore 36–38%**、system_server 22%、baidu.recommend 16–38%、CarSignal 7–11% | 与我们抢 CPU |
| 链路 | wlan0 rx **346–706 KB/s（2.8–5.7 Mbps, 321–609 pkt/s）**；mmc1 IRQ 769–852/s、bcmsdh 102–110/s | 带宽余量很大，瓶颈不在吞吐 |
| 音频 | `maxGapMs 390–476`（收包间隔）、`maxWriteMs 249–255`（单次 AudioTrack 写阻塞 250ms）、缓冲 288–500ms | 音频链路本身不抖（有 300ms+ 缓冲垫） |

app 逐线程占用（滑动窗口，% of one core，取 `/proc/<pid>/task/*/stat` 差值）：

| 线程 | % | 身份 |
|---|---|---|
| **pool-9-thread-1** | **27.5** | `CarPlayController.touchExecutor` —— 触摸上行（推断见 §2） |
| **main**（.diplay.hudtest） | **20.7** | UI 线程；**4.4 无 RenderThread，TextureView 每帧 GL 提交、触摸回调、2s 轮询都在这里** |
| carplay-audio | 11.7 | 音频下行接收/解密/写 AudioTrack |
| carplay-video | 8.6 | `AndroidMediaSink.VideoDecoder.run`（喂解码器 + 取输出） |
| **GC** | **8.0** | Dalvik 并发 GC |
| pool-10-thread-1 | 7.2 | `tunnelExecutor` → `Iap2WirelessControlClient.run`（无线 iAP2 隧道，逐报文打日志） |
| airplay-screen | 6.7 | `ScreenStream.run`（recvfrom→ChaCha20→入队） |
| MediaCodec_loop ×2 | 6.5 + 4.6 | 视频 + **音频**两条解码器回调线程 |
| **gle.aac.decoder** | **6.5** | **软件 AAC 解码**（音频合计 ≈25% 单核，与整条视频链 15% 相当甚至更高） |
| CodecLooper | 4.9 | vendor 解码器内部线程 |
| airplay-event-accept | 3.6 | 事件通道读 |

### 1.3 GC（Dalvik）——"卡顿"的直接来源

```
静止期（每 5.4s 一次）:
D/dalvikvm(19791): GC_CONCURRENT freed 4034K, 38% free 12680K/20212K, paused 4ms+7ms, total 67ms
D/dalvikvm(19791): WAIT_FOR_CONCURRENT_GC blocked 51ms
D/dalvikvm(19791): WAIT_FOR_CONCURRENT_GC blocked 50ms
```

- 静止期：**每 5.4s 回收约 4MB（≈750 KB/s 分配）**，total 67–92ms，pause 4–13ms，
  且 **`WAIT_FOR_CONCURRENT_GC blocked 37–67ms`（每次 3–5 条）**。
- 滑动期：间隔缩到 **1.2–3s**，每次 4.0–6.6MB（分配率 **2–4 MB/s**）。
- 与视频指标对齐：`maxGap 122–151ms`、`shownP95 80–94ms` ≈ 一次 GC + 3–5 个线程各被挡 40–67ms。
- 这与静态分析的预测一致：每帧 ≥2 个 N 字节新数组（`ScreenStream.readFully`、`AirPlayCrypto.chachaOpen`）
  + 每触摸事件的对象复制，Dalvik 大对象空间 + 跨线程存活 → 密集 GC。

## 2. 线程点名（`kill -3` + `/data/anr/traces.txt` 实证）

```
"pool-10-thread-1" TIMED_WAIT
  at Iap2LinkChannel.waitFor(Iap2LinkChannel.kt:331) ... Iap2Session.recv(...)
  at Iap2WirelessControlClient.run(Iap2WirelessControlClient.kt:93)
  at CarPlayController.startWirelessTunnelControl$lambda$79(CarPlayController.kt:1226)
```
→ **pool-10 = `tunnelExecutor`**（与 `CarPlayController.kt:1221 tunnelExecutor.execute {...}` 一致）。

`CarPlayController.kt:187-190` 三个同序单线程池字段：`executor`(187) → `touchExecutor`(188) →
`tunnelExecutor`(190)。既然 `tunnelExecutor=pool-10`，则 **`pool-8=executor`、`pool-9=touchExecutor`**，
与"pool-9 只在滑动时飙升（27.5%→静止 2.9%）"的行为一致。

触摸上行每个状态的代价（`AirPlaySession.kt:336-359` → `sendCommandLocked:313-334`）：
`contacts.map{ copy }` 复制列表 → `AirPlayHid.touchReport` → `linkedMapOf` 包装 →
**`trace("airplay event tx headers=$head bodyHex=${body.toHex()}")` 每次都把整包 hex 拼成字符串**
（`debug_logs_enabled=false` 也照拼）→ `cipher.encrypt(head + body)` → socket write + flush。
`AirPlayHid`/`TouchDispatchGate` 注释自述：**事件通道单次写可阻塞 200–380ms**（本轮 `touchSendMax=161ms`）。

## 3. 本轮实测**否定**的两条旧结论

1. **`ssi@0202c000`（SSI2/DIRANA 音频 I2S）48kHz 中断风暴不存在**：
   两次采样相隔 220 秒，`/proc/interrupts` 第 79 行计数 **完全不变（0/s）**；CPU0 的 `irq` 只有 2.1–2.4%（静止）。
   → 它至少不是**当前**的负担（doc 29 的结论需要加条件重测）。
2. **没有"单核被打满"**：滑动峰值 c0 84%、其余 74–82%，`procs_running` 2–3。所以不是"算力不够"，
   而是**四核同时高位 + 全部中断挤在 CPU0 + GC 周期性阻塞**。

## 4. 复核：哪些"看起来像负担"的其实是测量副作用

`adb shell input swipe` **每次都会起一个 Dalvik `app_process`**。我注入 18 次滑动 → 日志里出现
18 个新 pid 连续 `DexOpt: mismatch dep name .../ecarx.vr.impl.jar...` + `Trying to load lib libjavacore.so`
+ `Note: class Landroid/app/ActivityManagerNative; has 181 unimplemented methods`。
**这是 `input` 命令自身的开销，不是车机在刷进程**（40 秒静默窗口内新进程只有 2 个 kworker）。
→ 教训：用 `input` 注入做性能测试时，被测机要额外承担一个 Java VM 启动 + dexopt 校验。

## 5. 结论：负担排行（按"占用 × 可回收性"）

| # | 负担 | 实测 | 能否动 |
|---|---|---|---|
| 1 | **厂商常驻应用**：`com.ecarx.appstore` 24–38% 单核、`IHU_CarSignalService.elf` 7–11%、`system_server` 6–22%、`com.baidu.recommend` 偶发 16–38%、`com.baidu.che.codriver` | 空闲即 0.5–0.7 核 | 车机设置里关自动更新/语音唤醒；`pm` 被 ROM 挡（doc 29 已证） |
| 2 | **中断/软中断全在 CPU0**：ethernet 1,100/s（常驻 1.7Mbps 内网流量）+ Wi-Fi SDIO 852/s + ecspi 340/s + USB 100/s | 滑动时 CPU0 84%，内核占 52% | 可（IRQ 亲和 + RPS + app 绑核）——**但重启后已全部丢失**，当前 `/proc/interrupts` 里 CPU1–3 的列恒为 0、app `Cpus_allowed=0-3` |
| 3 | **app 自身 86–126% 单核**：main 20.7（TextureView/GL）+ touchExecutor 27.5 + 音频 25（含软件 AAC）+ 视频 15 + 隧道 7 | 见 §1.2 | 代码可改（§6） |
| 4 | **Dalvik GC 抖动**：滑动每 1.2–3s 一次，阻塞线程 37–67ms | 对应 maxGap 122–151ms | 代码可改（降分配） |
| 5 | **30fps 上限**：`display_fps=30` | 滑动实际 27fps | 有意为之（60fps 会积压），需权衡 |

## 6. 建议（按收益/风险）

**系统侧（立竿见影、可逆）**
1. **重放 doc 29 的调优**——本轮实测它已经全丢：Wi-Fi SDIO/USB 中断 → CPU1、`wlan0` RPS 开启、
   `netdev_max_backlog`/`rmem` 放大、**app 绑 CPU2-3**（当前 `Cpus_allowed: 0-3`）。
2. 车机设置里关掉**应用商店自动更新**与**语音唤醒**（回收 ~0.3–0.4 核；`pm disable` 在本 ROM 无效）。

**app 侧（按投入产出）**
3. **触摸上行去分配 + 去掉无条件 hex 拼接**：`trace("...bodyHex=${body.toHex()}")` 改为惰性（先判 debug 开关），
   `contacts.map{copy}` 复用数组；把 `sendTouch` 的每状态开销压下来（对应 pool-9 的 27.5%）。
4. **iAP2 隧道日志降级**：`debugLog("iAP tunnel $message")` 每秒 37 行、30 秒写满环形缓冲——按等级门控。
5. **降 GC**：复用读/解密缓冲（`ScreenStream.readFully` 的 `ByteArray(N)`、`AirPlayCrypto` 的输出数组），
   把分配率从 2–4 MB/s 压到接近 0；并用 **`android.os.Debug.startAllocCounting()`（API19 可用）**
   把"分配字节数 + GC 次数/耗时"打进 `DiPlay-VideoStats` 行，让 GC 抖动可被量化验收。
6. **音频**：`gle.aac.decoder`（软件 AAC）占 6.5% 单核；查车机是否有 AAC 硬解 OMX 可用。
7. **TextureView → SurfaceView/overlay**（收益最大、风险中高）：去掉每帧 1920×1080×4 ≈ 8.3MB 的
   窗口 FBO 写 + 混合读，并把 UI 线程从每帧 GL 提交里解放；但必须处理 vendor 解码器重建 surface
   掉进 client-side buffer 的黑屏（doc 36），并保留回退开关。

## 7. 复现命令

```bash
ADB='d:\Dev Environment\platform-tools\adb.exe'
# 分核 + 中断 + 网卡（两次采样求差；设备端 toolbox 无 paste/awk 时本地算）
$ADB shell "cat /proc/stat; cat /proc/interrupts; cat /proc/net/dev"
# 逐线程 CPU（100Hz tick，差值/窗口秒数=单核百分比）
$ADB shell "for t in /proc/<pid>/task/*; do cat \$t/stat; done"
# 视频/音频/GC 流式抓取（-d 会被 256KB 环形缓冲冲掉，必须流式）
$ADB shell logcat -v time -s DiPlay-VideoStats:V DiPlay-AudioStats:V dalvikvm:V
# Java 栈（run-as = 应用自身 uid，可对自己的进程发 SIGQUIT）
$ADB shell "run-as com.shihab.diplay.hudtest kill -3 <pid>"; sleep 3
$ADB shell "cat /data/anr/traces.txt"     # 0666，取最后一个 "Cmd line: com.shihab.diplay.hudtest" 段
# 设备实际生效设置（fps/分辨率/热点模式）
$ADB shell "run-as com.shihab.diplay.hudtest cat /data/data/com.shihab.diplay.hudtest/shared_prefs/xcertplay_airplay.xml"
```

本轮原始采集脚本与产物：`_live_sample.ps1`（分核/逐线程/中断/网卡差值采样）、
`_live_probe.ps1`（`input swipe` 注入 + 滑动中途抓栈）、`_traces_19791.txt`、`_trace_load1/2.txt`。

## 8. 「外设中断全钉在 CPU0」能不能解决？

**能，但要分三类，而且必须承认哪一类改不动。**

### 8.1 可运行时改（**需 root**，易失，重启即失）

CS11 实测：shell 身份 `cat /proc/irq/{55,72,79,150}/smp_affinity` 全部 `Permission denied`
（`/proc/cmdline` 亦不可读）→ 这条路**只能经 rootd / app_process 通道**走。可做的：

| 项 | 命令要点 | CS11 对应 IRQ |
|---|---|---|
| Wi-Fi SDIO 中断 → CPU1 | `echo 2 > /proc/irq/55/smp_affinity` | 55 `mmc1`（滑动时 769–852/s）、243 `bcmsdh_sdmmc` |
| USB 中断 → CPU1 | `/proc/irq/72`、`/proc/irq/75` | 有线 NCM / USB |
| **车机内网以太网 → CPU1** | `/proc/irq/150` | **150 `2188000.ethernet`（常驻 1,085–1,132/s，最大的一个）** |
| eMMC → CPU1 | `/proc/irq/56` | `mmc2` |
| ecspi（身份未定，可能是触摸） | `/proc/irq/65` | **动之前先确认，别把触控搞坏** |
| `wlan0` RPS | `echo 2 > /sys/class/net/wlan0/queues/rx-0/rps_cpus` | 让收包软中断也落 CPU1 |
| 网络旋钮 | `netdev_max_backlog=4000`、`rmem_max=2MB`、`rmem_default=512KB`、`ksoftirqd/*` renice −5 | — |
| app 绑核 | `taskset -p c <pid>`（CPU2-3） | 当前 `Cpus_allowed: 0-3`，**未绑** |
| 厂商重进程挪走 | `taskset -p 8 <appstore / codriver / recommend pid>` | 比 `pm disable`（本 ROM 无效）更现实 |

**不要动**：`29 twd`（每核定时器）、`IPI*`、`34 sdma` —— per-CPU 或不可迁移，改亲和无效甚至有害。
现成脚本：`tools/cs11_wireless_tune.sh`（doc 29）；本轮实测它**重启后已全部丢失**。

### 8.2 持久化（这才是"真正解决"）

1. **app 自绑核 —— 不需要 root，且重启后自动生效（推荐首选）**：
   进程/线程对自己调 `sched_setaffinity` 不需要 `CAP_SYS_NICE`，是允许操作。项目已有 JNI
   （`libdiplay_opus.so` / `LegacyNativeOpus`），加一个几行的 native 函数，在 `CarPlayController`
   初始化时把本进程钉到 **CPU2-3**，并把 `airplay-screen` / `carplay-video` / `carplay-audio` /
   `touchExecutor` 线程优先级提上去（`setpriority` / `Process.setThreadPriority`）。
   这样即使车机重启、root 链丢失，CarPlay 也不会再跟 CPU0 的中断抢核。
2. **root 侧开机钩子**：doc 16 证明旧 ROM 的 `/system` 可写（本轮车机已断开，**未能复核是否仍可写**）；
   可写就放 init 服务 / 自启脚本，否则退化为 `rootd` 队列 + `tools/cs11_watchdog.sh` 在设备上线时重放。
3. **无需 root 的持久项**：车机设置里关「应用商店自动更新」「语音唤醒」——直接减少 CPU0 上的竞争者。

### 8.3 改不动的

- 硬中断 handler 与 GIC 分发本身的开销：迁移只是**换核**，不减少总量。
- 车机内网那 **1.7 Mbps / ~1,100 IRQ/s** 的来源（厂商内部链路：SOME/IP / 功放 / 仪表），
  停不掉厂商服务，只能把它连同中断一起挪到 CPU1。
- 内核启动参数（`isolcpus` 之类）= 改 boot cmdline = 重刷 boot，不划算。
- SSI2 驱动级「每采样一次中断」（doc 29 的 48kHz 风暴）：**本轮实测 0/s**，当前不用管。

### 8.4 收益的诚实预期与验证口径

- 滑动窗口 CPU0 = 84%，其中 **`irq` 7.7% + `softirq` 16.0% = 23.7 个点是可迁移的**；
  而 `sys 36%` 的大头是 **app 自己 socket 调用的系统时间**——它跟着线程走，**只有绑核才会换核**。
- 所以 8.1 / 8.2 能把 CPU0 从 ~84% 压到 ~60%，让收包/解码各自拿到干净核；
  但**它不会消掉 80–150ms 的帧间隔**——那个的直接来源是 Dalvik GC（§1.3，每 1.2–3s 一次、
  阻塞线程 37–67ms）。想「更顺滑」，GC/分配治理与触摸上行去分配的收益更直接。
- 建议顺序：**① app 自绑核（免 root、持久）→ ② 重放 8.1 并做 A/B → ③ GC/分配治理**。
- 验证：改前/改后各 60s、同一负载（用户连续滑动，别用 `input swipe`——见 §4），
  比 `/proc/interrupts` 各列分布 + CPU0 的 `irq/softirq` 占比 + `DiPlay-VideoStats` 的
  `shownP95 / maxGap / dropped`。

> **本轮勘察勘误**：写本节期间有两条命令误打到另一台设备（小米平板 `5329c7f4`，Android 15 / 8 核 /
> GICv3 / erofs / `pangu`），其 `/dev/cpuset`、挂载表、中断表**均不属于 CS11，已作废**。
> CS11 侧有效证据仅为首次勘察：shell 读写 `/proc/irq/*/smp_affinity` 被拒 → 改中断落点必须 root；
> 以及 §1 的全部数据（采样期间 adb 只有 `0123456789ABCDEF` 一台设备在线）。
