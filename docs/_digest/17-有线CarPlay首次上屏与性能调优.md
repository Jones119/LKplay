# R.10.53-17 有线 CarPlay 首次完整上屏与性能调优

> 日期：2026-10-06 夜 ｜ 状态：**里程碑达成** —— 自研栈有线 CarPlay 在 CS11 上完整运行（画面 + 音频 + 触控），无盒子、无第三方 APK。

## 1. 里程碑证据（真机）

- 截屏 `_analysis/scr7.png`：CarPlay 仪表盘（地图导航渲染、App 侧栏、卡片区、状态栏）。
- 日志链（`_analysis/applogs/diplayI.log`）：
  ```
  20:56:42  CS11 netdev: AirPlay listener on fe80::2 port=7000 (attempt 1)
  20:56:43  airplay TCP accepted family=IPv6
  20:56:45  AirPlay session active
  20:56:45  airplay audio stream type=100 accepted → Audio: ready ... playing=true
  20:56:5x  airplay screen stream type=110 → Video: decoder=OMX.Freescale.std.video_decoder.avc.v3.hw-based
  20:56:56  Video: first frame rendered
  20:57:01  Video: video stats rx=22.2fps shown=18.6fps touch2frame avg=84ms
  ```
- 音频持续写入（writtenFrames 每秒增长），underruns=+0，playState=3。

## 2. 打通全链的三大技术突破（本轮）

| # | 问题 | 根因 | 解法 |
|---|------|------|------|
| 1 | App 每 33s 踢内核 NCM 绑定；TUN 不存在 | CS11 内核无 tun.ko；app 的 VPN/TUN 中继设计在此死路 | **内核 NCM 直连**：rootd 绑定 cdc_ncm → `usb1` → AirPlay 监听器绑 `fe80::2`（`Cs11NetdevBootstrap`） |
| 2 | SETUP 处理抛异常 → 每次回 500 → 手机断开（30+ 轮死循环） | **该 ROM 的 libcore 魔改：一切无 scope 的 IPv6 绑定被拒**（连 `::` 字面量与全零地址都拦，报错文本仍沿用官方 “Can't bind to a link-local…”） | 全仓库 IPv6 绑定改 `serverBindAddress()`（scoped fe80::2@usb1）；bind 竞态加 DAD 重试（`attempt 1/4`, EADDRNOTAVAIL→等1.5s） |
| 3 | 音频线程 `NoClassDefFoundError: android.media.AudioAttributes` → app 崩溃 | Dalvik **按方法惰性校验**：方法体中出现 API21+ 类型引用（哪怕在 `SDK_INT` 守卫内）也会在首次执行时急切解析 → 4.4 上没有该类即崩 | 全部 API21+/26+ 音频类型隔离进 `AudioApiBridge`（其方法只在 21+/26+ 调用点被执行） |

> 附带修：多处 socket 绑定统一走 `serverBindAddress()`（NtpClock/AudioStream/ScreenStream/BufferedAudio/Microphone/IapTunnel/事件/keep-alive），`localAddress` 加 `withLinkLocalScope()` 修复 scope。

## 3. 性能调优（用户反馈“声音卡顿/画面滑动卡顿”后）

三大 CPU 杀手（全部来自定桩日志）＋修复：

| 项 | 措施 |
|----|------|
| 手机 syslog 转发（120s 洪流）× | 删除 `com.apple.syslog_relay` 捕获块（CarPlayController） |
| usbmux 每帧日志（每秒数百行） | 删除 per-frame Log.i（Iap2UsbMuxHost） |
| wired-link 每包日志 + 256B 分片写 | 删除 `wireSummary` 逐包日志；恢复整块 `carkit.send(data)` |
| 线程优先级 | `iap2-usbmux-reader`/`airplay-audio-rx`/`carplay-audio` → URGENT_AUDIO(-19)；`carplay-video`/`airplay-screen` → DISPLAY(-4) |

**前后对比（同一车机、同一会话链）**：

| 指标 | 优化前 | 优化后 |
|---|---|---|
| 系统负载 (4核) | 7.65 | **3.01** |
| CPU 空闲 | 2.1% | **72.7%** |
| app CPU | 38.2% | **11.3%** |
| 音频写卡顿 maxWriteMs | 245ms | **0ms** |
| 音频读卡顿 readMaxMs | 194ms | **6ms** |
| 音频包间隔 maxGapMs | 89~127ms | **69~81ms** |
| 触控→上屏 touch2frame | 249ms | **84ms** |

- 辅助：rootd `renice -8` 常驻；`logcat -f` 抓包进程已杀（省 IO）。
- 调频：已是 `performance`（996MHz）。

## 4. 已知残留（下一步候选）

- 音频 maxGap 仍有 ~70-80ms 尖峰、`processMaxUs` 有 65-75ms 尖峰（疑似 GC/单包解密长尾）→ 候选：音频缓冲 300→500ms、排查 GC。
- 视频偶发 `recovery: backlog exceeded 250ms` → 关键帧重请求（每次 ≤1s 恢复）→ 候选：放宽 backlog 阈值/队列深度。
- rootd/USB1 状态重启即失（root 链需一键点火重建，`tools/cs11_root_fire.sh`）。

## 5. 关键文件（本轮改动）

- 新增：`network/Cs11NetdevBootstrap.kt`、`airplay/WildcardAddress.kt`（`serverBindAddress()`+`withLinkLocalScope()`）、`media/WildcardAddress.kt`、`media/AudioApiBridge.kt`
- 修改：`CarPlayController.kt`（净流改造+重试+删日志）、`CarPlayVpnService.kt`（`attachSystemNetdev`）、`Iap2UsbMuxHost.kt`、`NtpClock/AudioStream/ScreenStream/BufferedAudioStream/IapTunnel/CarPlayMediaEngine/MicrophoneUplink`
- 车机侧：`/data/.android/{mp.sh,rootd.sh,rootcmd.sh,rootout.txt}`、`usb1`（fe80::2 剪除 EUI-64）
