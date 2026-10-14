# R.10.53-20 平板无线 Bench 与无线 CarPlay 首亮

> 日期：2026-10-06 深夜 ｜ 状态：**里程碑 —— 无线 CarPlay 在 Xiaomi Pad 6 Max 14（Android 15）上完整跑通（画面+音频+触控）**，全程 PC 驱动（adb），未动用 CS11 车机。

## 0. 背景与结论

- 用户问题："无线连接开发是否必须使用车机？用 Android 15 平板是否可行？"
- **结论（实测）：不必须，平板完全可行。** 无线 CarPlay 的"车机"角色 = 任何跑 DiPlay 的安卓设备；平板成为无线开发的正式 Bench，**车机只需留作最后的 4.4 下行适配与终验**。
- "Android 15 版本高"反向利好：上游无线功能主打 Android 9+；15 在支持范围内。真正需要适配特例的反而是 CS11 的 4.4。

## 1. 环境（Bench）

| 项 | 值 |
|---|---|
| 设备 | Xiaomi Pad 6 Max 14（2307BRPDCC），Android 15 / SDK 35 / arm64-v8a |
| adb | `adb -s 5329c7f4`（USB） |
| app | 同一只 CS11 standalone APK（`com.shihab.diplay.hudtest`，0.2.13-cs11.1-hud-test），`install -g` |
| 平板热点 | SSID `Xiaomi Pad 6 Max 14` / WPA2 / **5 GHz**（apps 当"车机热点"） |
| iPhone | 系统蓝牙配对 + 手动加入平板热点（IP 192.168.119.101） |

## 2. 首亮流程（日志实锤，2026-10-06 22:32）

```
airplay listener ready family=IPv4 port=7000 (bind 192.168.119.8, wlan2)
wireless Bonjour services started mode=interface iface=wlan2
RFCOMM connected → iAP2 CSM channel opened over RFCOMM
IAP2 READY → identification accepted
MFi 本地离线认证：0xaa02 challenge → 0xaa03 signature → 0xaa05 succeeded
iap2 rx=0x5702 request-wifi-configuration
iap2 tx=0x5703 accessory-wifi-configuration（热点 SSID/密码）
iap2 rx=0x4e0d wireless-carplay-update available=true   ← iPhone 认可
0x4e0e device-transport-identifier → 重发 0x5703
→ carPlayStartSessions=1（iPhone 发起启动）
→ RFCOMM EOF（交接）→ iAP2 转入 iAP tunnel
→ AirPlay: screen type=110 dataPort=34171 / audio type=102 dataPort=60045 (AAC_LC)
```

## 3. 实测指标

| 指标 | 值 |
|---|---|
| 视频 | 35fps 稳态，3.1–3.9 Mbps；地图重绘瞬时 13–17 Mbps；**seqGaps=0** |
| 音频 | AAC_LC 48kHz（type=102 buffered/media 路径），underruns=+0，queue 健康 |
| 触控 | touch2frame avg **28–41ms** / max 167ms；touchSendMax 7–9ms |
| 屏幕 | `_analysis/tablet/scr11.png`（高德地图 + Apple Music 播放中） |

## 4. 关键经验（踩坑记录）

1. **首次握手卡点**：iPhone 还没连上热点时发 0x5703 → 手机不启动（startRequests=0）。**先让 iPhone 加入平板热点，再连接；若仍卡住，断开重连一次即通。**
2. `channel=0 / configReadable=false`（Android 15 不给读热点配置）——**实测不影响**（手动凭证已够）。
3. app 里 BYD 专有服务探测（`com.ts.car.someip`）在平板上报 bindService=false——无害噪音。
4. 平板上 app 以窗口模式显示 CarPlay；会话页 `CarPlayHostActivity`。

## 5. 对 CS11 无线化的意义（下一步）

- 无线协议栈 / 会话生命周期 / 断连重连逻辑 = **已在 Bench 验证可行**，不再需要在车上摸黑试错。
- 车机侧剩余专属工作：4.4 API 下行（wireless 后端含 ConnectivityManager 21+ 依赖，ExistingWifiManager 已标注 4.4 不支持——需评估替代实现）、nFore 蓝牙栈、车机热点（192.168.43.1）。
- Bench 归档：`_analysis/tablet/`（BENCH-NOTES.md / scr*.png / diplay_tablet.log / hotspot_config.png）

## 6. 质量实测（用户要求，2026-10-06 23:40）

### 视频（H.264 会话）
- 协商 2880×1800 @100%（原生全屏），H.264，解码 `c2.qti.avc.decoder`
- 稳态 27–35fps（rx≈shown 零帧丢失）；静态内容 1.8–3.2 Mbps，maxGap 54–60ms
- 运动压力（拖图）：瞬时 6.5–14.4 Mbps，**seqGaps=0、recoveries=0**
- 静态画质（原图放大逐字检查）：地图文字锐利、专辑封面细节完整、无块状/蚊噪/振铃
- 视图不在前台 → iOS 自动暂停视频（省流），回前台即恢复（设计内行为）
- 触控 touch2frame avg 17–41ms

### 视频（HEVC 会话，开关已开）
- 解码 `c2.qti.hevc.decoder`，2880×1800；30fps 稳态 / 43fps 突发
- **静态同场景 ~612 kbps（H.264 要 1800–3100）≈ 3 倍效率**；轻运动 ~1.6 Mbps；seqGaps=0

### 音频
- AAC_LC 48kHz（type=102 buffered 媒体通道），实测 **~215–258 kbps**（Apple Music 档位）
- underruns=+0、dropped=0、rebuffers=0；音乐缓冲设置 300ms（另有"缓冲音乐(实验性)"可提前 ~2 分钟）

## 7. ⚠️ 车机配置落差（用户指出；跨界结论必须带此警示）

**平板 = 旗舰 SoC + WiFi6 + 现代蓝牙**；**CS11 = i.MX6Q 4×A9@996MHz + 2GB + 2013 级 WiFi/蓝牙(nFore)**。

- **HEVC 是平板专属结论；CS11 必须 H.264** —— i.MX6 的 VPU 只支持 H.264/MPEG4 等，**无 HEVC 硬解**，软解不可行（app 里 HEVC 开关的提示"关闭后可兼容更多车机"即此意）。
- 车机无线参考预设：H.264、分辨率起步 ≤720p/1080p 档、帧率 30 封顶，按实测余量再上调。
- 有线调试时 CPU 已处紧平衡（见 digest 18），无线在车上还要叠加 WiFi 收包 + 同量级解码负荷，性能余量须重新评估。
- **Bench 的价值 = 协议与软件验证 + 画质上限参考**；车机实际数值必须上车测（且车机无线还有 4.4 下行适配未做，尚未到"车机无线"实施阶段）。
- 平板 HEVC 开关当前已开，仅影响平板会话；将来车窗测试前记得切回 H.264。

### 质量证据文件
`_analysis/tablet/`：q1_current.png（H.264 仪表盘）、q8.png、q9_motion.png（运动中）、q10_hevc.png（HEVC）

## 8. 有线 vs 无线：编解码对照（用户提问，代码+日志双实锤）

| 环节 | 有线（CS11） | 无线（平板 bench） |
|---|---|---|
| 视频流 | screen type=110，H.264 | screen type=110，H.264 **或 HEVC（可协商）** |
| 视频解码 | MediaCodec 硬解：`OMX.Freescale.std.video_decoder.avc.v3.hw-based`（i.MX6 VPU） | MediaCodec 硬解：`c2.qti.avc.decoder` / `c2.qti.hevc.decoder` |
| **音频流** | **LPCM 未压缩 48kHz（type=100 实时流）** | **AAC_LC ~256kbps（type=102 buffered 通道）** |
| 音频处理 | **零解码**直写 AudioTrack（代码：`plays wired LPCM directly`），仅 byteSwap | MediaCodec 解码 AAC→PCM→AudioTrack，**带缓冲+锚定时钟**（300ms 设置） |
| 流解密 | ChaCha20-Poly1305（同一实现） | 同 |
| 认证 | MFi 本地离线 | 同 |
| 传输 | USB NCM（内核网卡 usb1） | BT 握手 + WiFi |

**关键含义：**
1. 音频档位差异是 **Apple 按传输方式的协议选择**（有线=LPCM 低延迟裸流；无线=AAC 压缩+缓冲），不是 app 可随意切换的。视频管线则是同一套（MediaCodec hw）。
2. **有线“滋啦”的结构性根源**：LPCM 裸流无任何缓冲掩蔽，链路丢包=直接听见（见 digest 18）；无线 AAC 缓冲路径**天然免疫**此类抖动——这正是 bench 上零断感的原因。
3. 车机将来跑无线 → 音频自动切到 AAC buffered（与 bench 一致），视频继续 H.264 硬解；代码路径共用同一 media engine，bench 验证对车机有效（除硬件档位差异，见 §7）。
