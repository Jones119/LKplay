# 23 · 有线 CarPlay 协议对照（官方 vs 我们）

> 2026-10-07 · 问题：官方有线 CarPlay 的工作协议/方式是什么？与我们的是否一样？
> 结论：**协议层完全一致（同一套官方分层与消息）；差异只在"身份授权/集成方式/发现机制选型"三处。**

## 0. 官方有线 CarPlay 协议栈（自下而上）

```
USB 复合设备：iPhone(device, VID 0x05AC) ↔ 车机(host)
│   iPhone 暴露三类接口：usbmux(配对) / iAP2(vendor bulk) / NCM(网络, control+data)
│   （配件板卡接法需一次 USB role switch；标准车机 USB 主机口直连无需）
▼
iAP2 Link Layer（帧头 FF 5A）
│   握手 FF 55 02 00 EE 10 → 参数协商 → 识别(identification) → MFi 认证
│   MFi：aa00 请求证书 → aa01 证书 → aa02 challenge → aa03 response → aa05 结果
▼
NCM 网络建立 → 双方 IPv6 link-local（fe80::）
▼
端点发现（两条官方行为族路径，取一即可）：
│   ① 车机在 NCM 链路 Bonjour 广播 CarPlay._airplay._tcp.local（mDNS）
│      iPhone airplayd 浏览→解析→ /info → RTSP   ← VW MIB2Q 实录（真实车机）
│   ② iAP2 carplay-start-session 直接携带车机 AirPlay 端点
│      （IPv6 地址列表 + 端口 + 公钥）→ 手机直连 ← LIVI/盒子系实现
▼
AirPlay 会话：RTSP :7000（bplist 控制）
│   画面流 type 110/111（TCP，H.264/HEVC，ChaCha20-Poly1305）
│   音频流 type 100/101/102（实时 RTP/UDP：LPCM/AAC）· 103（缓冲 AAC over TCP）
│   事件/时序/保活通道（event/timing/keepAlive ports）
▼
iAP2 继续承载：触控回传、媒体键、车辆数据（温度/续航/NowPlaying 等）
│   （有线：iAP2 留在 USB 上；无线：iAP2 经 AirPlay 隧道穿回）
```

**公开证据**：USENIX VehicleSec'25《CarPlay at Risk》抓包实录（role switch C0 53/40 51、FF5A 帧、aa00→aa05 认证链、IPv6 link-local + Bonjour）；wiomoc.de iAP2 逆向（"USB Host Mode 下 accessory 提供 NCM 端点为 AirPlay 建立会话"；无线时 iAP2 经 AirPlay 隧道）；MIB2Q 车机 debug（"mdnsd registers CarPlay._airplay._tcp on carplay0；airplayd → APEndpointCarPlay → /info → RTSP"）；OSU 论文（NCM 接口描述符、IPv6+Bonjour 步骤、盒子以 05AC:12NN 呈现）。

## 1. 我们的实现映射（代码实锤）

| 层 | 官方 | 我们（CarPlay-CS11） | 判定 |
|---|---|---|---|
| USB 复合设备 | iPhone device / 车机 host | 相同：CS11 host，iPhone config 6（usbmux+iAP2+NCM），内核 `cdc_ncm` 绑 `2-1.4:6.3` → `usb1` | ✅ |
| iAP2 link | FF5A / 握手 / 参数协商 | 相同（`Iap2WiredControlClient` 实机日志：`iap2 rx=0x4300 availability` → `tx=0x4301 start-session`） | ✅ |
| MFi 认证 | 认证协处理器（**硬件芯片**） | 协议流程相同（实机 `0xaa02/0xaa03/0xaa05` succeeded）；**身份=软件证书**（离线完成同一验签流程） | ⚠️ 协议同、**授权来源不同** |
| 网络层 | NCM + IPv6 link-local | 相同：`usb1` + `fe80::2`（EUI-64 剪除后钉住固定地址，`WildcardAddress.kt` 全库 scoped 绑定） | ✅ |
| 发现机制 | ①Bonjour 广播 `_airplay._tcp` 或 ②iAP2 直递端点 | **有线=②**：`Iap2WiredCarPlayEndpoint(ipv6Addresses, airPlayPort, publicKey, sourceVersion)` 经 0x4301 递出 → 手机直连 fe80::2:7000；**无线=①**：`CarPlayBonjour`（interface-bound JmDNS，`useInterfaceMdns=true`，在热点接口广播） | ✅ 两条官方路径各取一支 |
| 控制/媒体 | RTSP7000 + 流 110/100-103 + ChaCha20 | 相同（`AirPlaySession.kt` / `ScreenStream.kt` / `AudioStream.kt` / `BufferedAudioStream.kt`） | ✅ |
| 回传/辅助 | 触控/媒体键/车辆数据 iAP2 | 相同（控制客户端含 location/vehicle-status 上报 tick） | ✅ |
| 集成方式 | 车厂固件系统级（MFi 授权） | 普通 Android app + root 辅助（内核 NCM 引导 / IRQ 调优等 CS11 适配） | ⚠️ 实现层不同 |

**CS11 特有工程**（官方栈不需要，纯平台适配，不改协议）：API19 类型隔离、libcore IPv6 scope 修补、内核 NCM 直连（无 tun）、USB IRQ 迁移、RPS/taskset 调优。

## 2. 媒体流实现细节（有线）

### 视频流（ScreenStream.kt）
- 建立：SETUP type=110/111 → `screen.listen()` 在 `fe80::2` 上开临时 TCP 端口 → 端口回给手机 → 手机连接（accept，设 `rcvbuf=64KB + TCP_NODELAY` 节流发送端）
- 帧协议：128B 头（bodyLen u32le@0，opcode@4：0=帧 / 1=配置）+ body
- 加密：ChaCha20-Poly1305，key=DataStream output key，nonce=8B LE 计数器，AAD=128B 头
- 组帧：`avcC/hvcC` 检测 → MediaCodec csd；NAL 长度前缀 → 就地改写为 Annex-B（start code 00 00 00 01）
- 解码：MediaCodec（VPU 硬解）→ `releaseOutputBuffer(render)` → Surface；PTS=入队时刻
- 实测：3–4.4Mbps / 35–60fps 档位 / 交互突发更高

### 音频流（AudioStream.kt，有线 type=100 实时）
- 建立：SETUP → 绑定 UDP data + control 两个端口（`fe80::2`），dataPort/controlPort 回给手机
- 包协议：`[12B RTP 头][密文][16B tag][8B LE nonce]`；AAD=RTP 头后 8 字节（timestamp+SSRC）
- 解密后重组 RTP=头+载荷；LPCM → `byteSwapS16`（BE→LE，16bit 立体声）→ `AudioTrack`（MODE_STREAM，首写预缓冲→play，fade-in 一次）
- AAC 分支：ADTS 封装 → MediaCodec 音频解码 → PCM → AudioTrack；UDP rcvbuf 512KB、`URGENT_AUDIO` 线程
- 缓冲路径（type=103）：TCP + AAC-LC + 32B shk，受 `bufferedAudioOutputEnabled` 开关控制
- 音频策略：**原始流，不做补洞**（用户要求）

## 2.5 链路带宽预算（分层保守估计）

| 层 | 数值 | 说明 |
|---|---|---|
| USB 2.0 HS 信令 | **480 Mbps** | CS11 是 USB2.0 主机口、iPhone(05ac:12a8) HS 枚举（待 `speed` 文件实锤） |
| USB bulk 现实上限 | 240–320 Mbps（30–40 MB/s） | 理论极值 53.2 MB/s=426 Mbps；外部存储实测普遍 30–40 MB/s |
| CDC-NCM 封装后 | ~250 Mbps | NTB 16KB 块封装开销 1–2%；usbnet 调度有一定损耗 |
| i.MX6 主机栈 + 老 Android 现实 | **保守 ~100 Mbps（≈12 MB/s）** | 单核处理 100Mbps 级 NCM 约占单核 5–15%（估算）；给"不破坏实时管线"留足余量 |
| **保守可用给应用层** | **~100 Mbps** | 工程保险值（推算，非实测；待上车跑一次吞吐基准） |
| 我们的实际需求 | **常态 5–6.5 Mbps**（视频 3–4.4 + LPCM 音频 1.536 + 控制 kbps 级） | 突发（滑动/重绘）15–25 Mbps 瞬时 |

**结论**：即便按最保守的 100 Mbps，也有 **15–25× 余量**——卡顿/丢包与"带宽不够"无关，根因在突发调度与中断延迟（见 18/22）。
**注**：`cdc_ncm` 早前报 `100mbit` 是 NCM 管理字段/驱动默认的信息值，**不是物理上限**。
**待验证（下车日）**：`cat /sys/bus/usb/devices/2-1.4/speed`（期望 `480`）；可选：空闲窗口用大文件吞吐测一次实际上限。

## 3. 差异清单（只有这三处）

1. **MFi 身份**：官方=授权认证芯片；我们=软件证书（同一验签协议，非授权身份）→ 合规边界：实验/自用可，商用不可。
2. **集成方式**：官方=固件系统级原生集成；我们=普通 app + root 辅助（CS11 上还必须如此，老车机没有官方路径）。
3. **实现来源**：官方=Apple 与车厂（AIS/CarPlay Communication Plug-in，NDA）；我们=开源复刻（DiPlay/LIVI 血统）+ 实机行为对齐。

## 4. 证据索引

- 发现机制代码：`transport/Iap2WiredControlClient.kt`（endpoint 类 L175+，0x4300/0x4301 处理 L92-102）、`network/CarPlayBonjour.kt`、`orchestration/CarPlayController.kt:1267`（无线启用、`useInterfaceMdns=true`）
- 媒体代码：`airplay/ScreenStream.kt`（128B 头/ChaCha20/64KB 节流）、`airplay/AudioStream.kt`（UDP/RTP/LPCM 字节序/512KB）、`media/AndroidMediaSink.kt`（AudioTrack L992+、writePcm L1301+、LPCM/AAC 分支 L1175+）
- 绑定：`airplay/WildcardAddress.kt`（fe80::2 scoped 绑定）
- 公开资料：USENIX VehicleSec'25 slides（CarPlay at Risk）、wiomoc.de/misc/posts/mfi_iap.html、mib2q-carplay-rgi docs（Bonjour on NCM）、OSU 论文（Magic Box RE）
