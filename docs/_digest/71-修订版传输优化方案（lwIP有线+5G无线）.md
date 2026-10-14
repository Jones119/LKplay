# 71 · 修订版传输优化方案（有线=用户态 lwIP 桥；无线=5GHz）· 取代 doc 70 的对应部分

日期：2026-10-13 · 触发：用户更正两点——①有线已从内核 cdc_ncm 方式**整体更换为 lwIP 模式**；②无线使用的是 **5GHz** 频段。doc 70 引用了内核时代数据与 2.4GHz 假设，本文修订。

> **变更依据（均已核实）**：
> - 有线：内核路径已于 2026-10-13 删除（`2ab6be92` 删除 Cs11NetdevBootstrap/attachSystemNetdev、`e0c4ec61` 退役 `ncm_legacy` 属性），**无内核兜底**（doc 63）；lwIP 桥实车上屏成功，判据 `rx=29.0fps shown=29.0fps maxGap=85ms dropped=0`（doc 65）。当前代码：API19 有线=用户态 NCM + lwIP 2.2.0（[UserSpaceSessionNetwork.kt](../../CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/transport/UserSpaceSessionNetwork.kt)，全部 AirPlay 通道 bind/connect 走 JNI→lwIP）；API21+ 才是原生 USB NCM VPN；两者不可用则明确降级为仅控制通道。
> - 无线：5GHz（用户确认；与"无线段视频指标不比调优后有线好"的实测不矛盾——SoC 仍是瓶颈）。

---

## 0. 两条更正各自推翻/保留了什么

### 0.1 有线换 lwIP ⇒ 内核时代的链路级数据**整体作废**

| 旧结论（内核 cdc_ncm 时代） | 现在 | 处置 |
| --- | --- | --- |
| `Udp6InErrors` 3.6/s→0、交互期 0.65%（doc 22/51 §2） | **内核 UDP6 计数器与我们的音频包无关了**——lwIP 自带 TCP/UDP 协议栈，内核只看到 NCM USB 帧流；`Udp6InErrors`、`Udp6RcvbufErrors`、`NoPorts`、`RetransSegs` 这些归因判据对有线**全部失效** | doc 70 §2.1 的 A1（lossprobe4 裁定）**降级**：lossprobe4 仍可测"USB→内核 NCM 帧"一段，但应用层缺包现在只能靠**应用/lwIP 侧计数**归因 |
| 永久缺包 0.42%、缺口成串 ≈1–3 NTB（doc 54 ADR-12） | 那是**内核 NCM 时代**的数据；lwIP 桥上屏判据是 `dropped=0`（doc 65）——**新栈的丢包基线尚未量化** | 重新采集：lwIP 路径的应用层音频窗统计（miss/late/underruns）+ 长会话基线 |
| 收包核被突发打满（CPU1 irq+sirq 57%） | 内核软中断现在只处理 NCM 帧搬运；**RX 处理主体移到我们自己的线程**（`ncm-us-rx`：USB bulk IN→NTB 解析→JNI inject→lwIP tcpip_input） | 性能模型改为"应用线程 + lwIP 内部锁"，不再是 IRQ/softirq 风暴 |
| `cs11_tune.sh`：IRQ 亲和/RPS/backlog/rmem_max | **这些是内核网络栈旋钮，对 lwIP 数据面基本无效**（RPS 作用于内核网卡收队列——现在内核里没有承载 AirPlay 的网卡） | 无线仍需要（wlan0 路径），有线作废；`taskset` 对我们自己的线程仍有效 |
| 视频接收窗 8KiB 请求=实际 16KiB 起 pacing（`debug.diplay.video_rcvbuf`） | 关键发现：lwIP 路径上 `receiveBufferSize` 的 setter 只是**记录+尽力 setsockopt**（[UserSpaceSessionNetwork.kt:190-195](../../CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/transport/UserSpaceSessionNetwork.kt)，注释明说 lwIP 无 SO_RCVBUF getsockopt）；真正 pacing 发送方的是 **lwIP 的 `TCP_WND=16KiB`（lwipopts.h 全局常量）** | ①核实 native 侧 SO_RCVBUF 是否为 no-op（批 0 一项）；②有线"调接收窗"的正确旋钮变成 **lwIP TCP_WND（需改 lwipopts.h 重编 so，不再是运行时属性）**——先 A/B 不动，因为 16KiB 恰是已验证窗口 |
| 有线音频 134.9pps/1565kbps LPCM、2.8× 包率挤同一条 NCM 队列 | **仍然成立**——这是 iOS 按 USB 链路选的格式（ADR-13），与传输层无关；只是现在"队列"= lwIP pbuf/内存池/我们的线程调度，不再是内核 socket 队列 | 保留，改挂到 lwIP 资源池（§2.1） |

**仍然有效（app 层，与传输层无关）**：doc 69 审计的全部 app 层发现（触摸拼包、BufferInfo、AEAD 参数、rtp 每包数组、1ms TX 轮询、executor 命名）；doc 41 的解码侧结论（PTS/feed 时刻、operating-rate、AAC 解码器枚举）；doc 54 的 GC/分配工作与其 ADR-12/13 的**教训**（缓冲不治丢包、格式不可协商）。

### 0.2 无线=5GHz ⇒ "空口突发竞争"从头号嫌疑降级

- 5GHz 信道宽、干扰少，视频 3.5Mbps + 音频 252kbps 的空口压力很小；doc 51 §9/§10 的实测"无线段视频指标并不比调优后有线更好"在 5G 下反而**强化了 SoC 侧结论**（瓶颈从来不在空口）。
- doc 70 §3.1 A2w（无线接收窗压空口突发）、§3.1 A5w（换 5G/查频段）**作废或预答**：频段已确认 5G；收窗 A/B 仍可做但预期收益从"空口"转为"iPhone 发送侧拥塞窗口的平滑"（低优先级）。
- 仍然成立：WiFi SDIO IRQ→CPU1、wlan0 RPS、app 绑 CPU2-3（cs11_wireless_tune.sh——无线的收包路径仍是内核协议栈，这些旋钮继续有效）；AAC 软解 25% 单核、maxWriteMs 250ms（CPU 侧病灶）；60fps 是 SoC/喂帧/GC 的墙，与频段无关。

---

## 1. 有线（lwIP 模式）修订方案

**数据面现状（代码已核实）**：iPhone NCM 帧 → USB bulk IN（常驻异步请求）→ `Ntb16Codec.parse copyOfRange` → JNI `nativeInjectFrame` → lwIP `pbuf_alloc+pbuf_take`（cs11_netif.c）→ lwIP TCP/UDP → JNI socket 读拷贝 → AirPlay 各通道；反向 TX：`nativePollTxFrame`→`copyOf`→`Ntb16Codec.build`（每帧 block+copyInto，边界时 +1 字节块）→ bulkTransfer。已知：单帧 **4–5 次拷贝**（doc 58 §12.2）；TX 线程 **1ms 固定轮询**（doc 69 P0-1）；`TCP_WND=16KiB`、`PBUF_POOL=64×1600B`、`MEM_SIZE=256KiB`、校验和全关 `CHECKSUM_CHECK_*=2`、`MEMP_NUM_NETCONN=32`（errno 23 修复后）、`LWIP_NETIF_TX_SINGLE_PBUF=1`、`LWIP_STATS=0`（当前关）。

### 1.1 音质稳定不丢包（lwIP 版）

| # | 动作 | 机理 | 验收 |
| --- | --- | --- | --- |
| W-A1 | **【前置】新栈音频基线采集**：长会话（≥30min）跑应用层 5s 窗统计，同时打开 lwIP 侧计数（见 W-A3） | 0.42% 缺包是否在 lwIP 栈消失/仍在，是后续一切决策的前提 | miss/lateOrDuplicate/underruns 数据表；缺口是否仍成串（成串→NTB/pbuf 环节仍在丢） |
| W-A2 | **每包对象收敛**（doc 69 P1-8，lwIP 路径实测每包 `ByteArray(16)+IntArray(1)+getByAddress`，135pps）+ **rtp 每包新数组**（AudioStream.kt:167，doc54 A1 挂账） | 音频 135pps 的稳态分配直接威胁音频线程的 GC 时刻表 | alloc 对照 + underruns 保持 0 |
| W-A3 | **lwIP 可观测性**：`LWIP_STATS=0→1`（dev 构建）或至少 `memp`/`pbuf` 源耗尽计数上报到现有 stats 行；**pbuf 池（64×1600B）与 TCP 插队列在交互期是否触顶**是成串缺包的头号怀疑 | 交互期视频突发 + LPCM 135pps 共同消耗 pbuf；池耗尽=静默丢 | pbuf/memp 峰值曲线 vs 音频 miss 窗对齐 |
| W-A4 | 若 W-A3 触顶：`PBUF_POOL 64→128`（+约 200KB RAM）或按需扩 `MEM_SIZE`；**不改 TCP_WND**（16KiB 是已验证窗口） | 资源池扩容量 | miss 归零 + 内存占用记录（车机闲 539MB，余量充足） |
| W-A5 | 若缺口仍成串且 pbuf 未触顶：查 **NTB 聚合/拆分**（`Ntb16Codec.parse` 的 NTB 帧数分布、USB bulk 读到的 NTB 大小统计）与 **`tcpip_input` 等待**（RX 线程注入是否被 lwIP 核心锁阻塞） | doc 54 时代"缺口≈1-3 NTB"的机理若延续到 lwIP，位置只能在这两处 | NTB 帧数/大小直方图 + 注入延迟 |
| W-A6 | lwIP 路径的**写剥离与 GC**（doc 41 §2.1b 思路重述）：音频 PCM 写（LPCM 无解码，主要是 AudioTrack 阻塞写）与收包/解密线程的分工核查 | Dalvik STW 落在写路径=瞬断 | maxWriteMs 峰值 |
| W-A7 | （若 W-A1 显示有 miss）**lwIP TCP_WND/缓冲 A/B**：改 lwipopts.h 重编 so，TCP_WND 16→24/32KiB 单变量 A/B——注意这**等价于旧时代"调接收窗"**，且 ADR-12 的教训（缓冲不治丢包）在此仍适用：先有归因再动 | 只在"缺口=排队溢出"被证实后做 | 同 W-A1 口径 |

**明确不做/降级**：`Udp6InErrors` 相关的一切裁定（对有线失效）；`rps_cpus`/`netdev_budget`/`rmem_max`（内核栈旋钮，对 lwIP 无效）；IRQ 亲和（仍可保留——USB bulk 帧的搬运 IRQ 在内核，但收益已从"救 UDP"变成"保 USB 吞吐"，保持现状即可）。

### 1.2 视频帧率高（lwIP 版）

doc 65 基线：**29fps/29fps、maxGap 85ms、dropped=0、1331kbps** —— 新栈起点优于旧栈调优后的水平（旧：17–28 迟到丢帧、maxGap 239–331ms）。这佐证：旧栈的突发病很大程度是内核 NCM 队列造成的，lwIP 直连已消掉大头。

| # | 动作 | 机理 | 验收 |
| --- | --- | --- | --- |
| W-B1 | **先确认基线的统计口径与稳定性**：85ms maxGap 是短窗还是长会话？补 ≥30min 交互会话的 shownP95/maxGap/dropped | 长会话才见 GC/内存池老化 | 数据表 |
| W-B2 | **TX 路径拷贝收敛**（§1.1 同批）：`copyOf(length)` 与 `Ntb16Codec.build` 每帧 block+copy → TX 缓冲预留 28B NTB 头一次成帧 | 视频 TCP 发送方向每帧省 2 拷贝 | alloc + shown 不劣化 |
| W-B3 | **解码三小项**（跨链路通用）：receivedNs 作 PTS、operating-rate、BufferInfo 提字段（doc69 P1-7/doc41 §2.2-2.3） | 同 doc 70，不变 | shownP95 |
| W-B4 | **60fps 分档实验**（前置=W-B1/B2/B3 稳定 + 30min 零 dropped）：`display_fps` 30→40/50/60，一档一档，recoveries>0 回退 | 5G 空口不再是变量，**纯 SoC/VPU/喂帧测试**；lwIP 每帧 4–5 拷贝在 60fps 下 ×2 帧率——W-B2 先做 | recoveries=0 持续 + shown≈请求 |
| W-B5 | 渲染：isOpaque=true（零风险）；仍不做 SurfaceView（黑屏风险不变） | 同 doc 70 | — |

### 1.3 触控不卡顿（lwIP 版）

触控链路：CarPlayController → touchGate → AirPlaySession.sendTouch → **lwIP TCP**（event 通道）→ iPhone。app 层三招不变（拼包直写/executor 独占/锁缩短，doc69 P0-4/5）；新增一条 lwIP 特有核查：

| # | 动作 | 机理 | 验收 |
| --- | --- | --- | --- |
| W-C1 | app 三招（同 doc 70 §2.3 C1–C3） | GC/锁 | touch2frame p95 |
| W-C2 | **event 通道 TCP_NODELAY**：lwIP 路径 `tcpNoDelay` setter 存在且经 SO_LINGER 对等的 native 实现确认生效（[UserSpaceSessionNetwork.kt:197-201](../../CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/transport/UserSpaceSessionNetwork.kt)）——**核实事件通道 accept 后是否真的调用了它**（ScreenStream 视频侧调了；event 侧需 grep 确认） | Nagle 在 lwIP 同样存在，小事件包被聚合=固定延迟 | touchSendMax p50 |
| W-C3 | lwIP 发送侧 `LWIP_NETIF_TX_SINGLE_PBUF=1` 下，event 小帧与视频大帧在 NTB 组装时的排队次序核查（TX 轮询 1ms ⇒ 最坏 1ms+USB 调度的固定抖动） | touchSendMax 的 106–384ms 尖刺若再现，先看 TX 环 | TX 队列长度采样 |

---

## 2. 无线（5GHz）修订方案

| # | 动作 | 修订后机理 | 验收 |
| --- | --- | --- | --- |
| N-A1 | **保留：无线音频基线补测**（doc 70 §3.1 A1w 不变） | 与频段无关 | 数据表 |
| N-A2 | **AAC 路径优化升为主攻**（原 A3w）：vendor 枚举 + 解码/写剥离 + ADTS 每包拼消分配 | 5G 下空口压力小，**maxWriteMs 250ms 与 AAC 软解 25% 单核是无线音质的主要残余风险** | maxWriteMs、decoder name、CPU% |
| N-A3 | **降级：收窗压空口突发 A/B**（原 A2w）→ 只在 N-A1 测出空口侧异常时再做；预期收益低 | 5G | — |
| N-A4 | **删除原 A5w 频段调查**；保留：WiFi IRQ/RPS/tune 脚本重放核对（内核栈路径无线仍在用）；NTP 降频实验保留为可选 | 5G 已定 | tune 项在位 |
| N-V1 | 视频：30fps 打满确认 + 解码三小项（同 W-B3）；**60fps 分档实验**（同 W-B4，前置相同） | 5G 下 60fps 的唯一瓶颈=SoC（与 doc 27 时代一致，但当时结论的空口疑点已排除） | recoveries/ksps |
| N-T1 | 触控：app 三招 + event TCP_NODELAY 核查（与有线 W-C2 同点，共享改动）；**空口 RTT ~30ms 差值定为物理下限不再追** | 5G 降低了抖动方差，p50 有望向 50-60ms 收敛 | touch2frame p50/p95 |

---

## 3. 修订后的施工批次

| 批次 | 内容 | 说明 |
| --- | --- | --- |
| **批 0（数据批，修订）** | ①有线长会话音频基线 + lwIP pbuf/memp 可观测性（W-A1/W-A3，`LWIP_STATS` 开发构建）②有线 30min 视频基线（W-B1）③无线音频基线（N-A1）④核实 lwIP SO_RCVBUF 是否 no-op + event 通道 TCP_NODELAY 是否设置（W-C2、§0.1） | 纯诊断；`Udp6InErrors` 退出有线判据 |
| **批 1（通用，不变）** | 零风险三连 + AEAD 参数复用 + 触摸直写（逐字节等价单测） | 与传输层无关 |
| **批 2（有线 lwIP 专项，替换原批 2）** | 每包对象+rtp 池化（W-A2）→ 按 W-A3 结论扩池（W-A4）或查 NTB（W-A5）→ TX 拷贝收敛（W-B2） | 原批 2 的"收包旋钮/收窗 4KiB/CPU 绑核"作废 |
| **批 3（无线 + 摸顶，修订）** | vendor AAC+写剥离（N-A2）→ 60fps 分档（有线 W-B4 与无线 N-V1 可同窗做） | 原批 3 的空口项删除 |

---

## 4. 失效与保留清单（供后续会话引用，防止再用旧数据）

**失效**（内核 cdc_ncm 时代，勿用于有线）：doc 22 全部链路级丢包数据、doc 51 §2/§5 的 Udp6InErrors 归因与收窗 A/B、ADR-12 的 0.42% 数值（**教训保留**）、lossprobe4 的四分支判读（对有线）、cs11_tune.sh 的 IRQ/RPS/backlog/rmem 对有线的效果、"收包核被打满"假设。
**保留**：ADR-12/13 的教训、doc 41 解码侧、doc 69 全部 app 层、doc 54 GC/分配、`video_rcvbuf` 对**无线**（内核 TCP）仍有效。
**待确认**：lwIP SO_RCVBUF native 实现（预计 no-op）；doc 65 的 85ms maxGap 是否长会话口径；lwIP 栈下触控/音频的长会话稳定性（唯一未闭环验收项，doc 63 §5）。
