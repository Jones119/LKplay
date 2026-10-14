# 58 · 用户态 NCM 桥（自带 TCP/IP 栈）路线评估与迁移清单（2026-10-12）

> **目标（用户）**：有线 CarPlay 在**车机重启后开箱可用**，不再需要每次点火手动跑 root 引导。
> EasyPlay（领克版 DiPlay v74）做到了这一点，本文评估"照它做"（记为 **A**）的真实成本，并列出
> 更便宜的替代路线（**B**/**C**）的判据与探针。

## 1. 为什么 A 不是"改一行传输层"，而是"自带一套 TCP/IP 栈"

| 事实 | 证据 |
|---|---|
| 本 ROM **没有 TUN 驱动** | doc 11 §3、doc 16：`/proc/misc` 无 `tun`、`/proc/devices` 无、无 `tun.ko`、`/dev/tun` 不存在；`VpnService.establish()` 报 AOSP `Vpn.java` 的 `Cannot create interface`；doc 54 §379 已定性"经典 VpnService + 用户态 NCM 桥在本机物理不可行" |
| 因此不能借系统协议栈 | 用户态 NCM 只搬以太帧，没有内核网卡可绑 `Socket`；`java.net` 无处落脚 |
| EasyPlay 的答案就是自带协议栈 | APK 内 `lib/armeabi-v7a/libdiplay_lwip.so`（379,372 B）+ `assets/licenses/lwIP.txt`；Java 侧 `network/LwipSessionNetwork.java`（≈89 KB）；实机日志 `wired userspace NCM network started` |
| 我们目前没有原生构建链 | `D:\Dev Environment\android-sdk` 下**无 NDK、无 cmake**（全盘搜索确认）；仓库仅有 `shared/src/main/jni/{Android.mk,local_hotspot_radio.c}`（该项目因 NDK 版本约束被禁用）+ 预编译 `libdiplay_opus.so` |

## 2. 迁移面（已量化：12 个文件 / 13 个 socket 建点）

| 文件 | 建点 | 通道 | 有线用户态路径是否必须迁移 |
|---|---|---|---|
| `airplay/AirPlaySession.kt` | 2 | 控制 RTSP 监听 + 接受的连接 | ✅ |
| `airplay/ScreenStream.kt` | 1 | 视频 TCP（type=110） | ✅ |
| `airplay/AudioStream.kt` | 1 | 实时音频 UDP（有线 100 LPCM / 无线 102 AAC） | ✅ |
| `airplay/BufferedAudioStream.kt` | 1 | 缓冲音乐 TCP（103） | ✅ |
| `airplay/VideoSettingsChannel.kt` | 1 | 视频设置通道 | ✅ |
| `airplay/IapTunnel.kt` | 3 | iAP2 隧道（type=130） | ✅ |
| `airplay/NtpClock.kt` | 1 | NTP 时间同步 UDP | ✅ |
| `media/MicrophoneUplink.kt` | 1 | 麦克风上行 UDP | ✅ |
| `network/AirPlayPortSelector.kt` | 1 | 端口探测 ServerSocket | ✅ |
| `airplay/VideoInCar.kt` | 1 | 车内视频（可选通道） | 视是否启用 |
| `network/CarPlayBonjour.kt` | 1 | mDNS 多播 + HTTP 探针 | ❌ **有线不需要**（iPhone 从 iAP2 `0x4301 start-session` 拿到地址/端口）；无线路径继续走内核/AP 栈 |
| `media/ClusterArtworkHttpServer.kt` | 2 | 127.0.0.1 回环图床（仪表封面） | ❌ 回环，与 CarPlay 链路无关 |
| `adb/LocalAdb.kt` | 1 | 本地 adb 通道 | ❌ 与 CarPlay 无关 |

**结构性代价（比列表更重的一点）**：这些通道现在直接用 `java.net.Socket`/`DatagramSocket`/`ServerSocket`
与 `InputStream/OutputStream`。迁移意味着**先造一层 socket 抽象**（接口 + `java.net` 实现 + lwIP 实现），
再把上述文件逐个改成依赖抽象；而**无线路径必须继续用内核栈** ⇒ 抽象要按"当前传输"分派，两条路都要能跑、
都要有测试。等于给现有可用的有线路径做一次**协议栈级重构**。

## 3. 分阶段计划（若最终选 A）

| 阶段 | 内容 | 产出/判据 |
|---|---|---|
| **0. 工具链** | 装 NDK（≤ r26，匹配既有约束）；vendor lwIP(BSD) 到 `shared/src/main/cpp`；加 CMake/ndk-build；产出**只报版本号的 stub `.so`** 并装车验证 | 车机 logcat 打印 lwIP 版本；零风险，且顺带为"复活热点原生模块"准备工具链 |

**阶段 0 现状（2026-10-12 已落地一部分，车机离线期间完成，纯离线可验证）**：

- ✅ `shared/src/main/jni/legacy_usb.c`（新）：`setInterface(fd, ifnum, alt)` = 先 `USBDEVFS_SETINTERFACE`，
  失败再退到 `USBDEVFS_CONTROL` 的 `SET_INTERFACE(0x0B)`（返回 0 或负 errno，不跨 JNI 抛异常）；
  另有 `version()` 供车机日志自证库已装载。
- ✅ `shared/src/main/jni/Android.mk`：新增 `legacy_usb` 模块（沿用既有 `-Wall -Wextra -Werror` 风格）。
- ✅ `shared/src/main/java/com/shilapi/xcertplay/transport/LegacyUsbNative.kt`（新）：`loadLibrary` 用
  `runCatching` 包住 ⇒ **没有 .so 时 `available=false`，绝不会崩**；调用方必须把 false 当作
  "本机不可用用户态桥"（回退内核网卡），而不是让有线路径失败。`:shared:compileDebugKotlin` 已通过。
- ⏳ 仍缺（需要 NDK）：装 NDK → 打开 `shared/build.gradle:20` 处被注释的 `externalNativeBuild { ndkBuild { … } }`
  → 装车后由 `version()`/`setInterface()` 的日志证明工具链与"原生改 altsetting 是否真生效"（**这是阶段 0 的关键未知项**）。
- ⏳ 阶段 0 的第二个产物（lwIP 本体 + JNI）属阶段 1，暂不动。
| **1. JNI + socket API** | lwIP `tcp`/`udp`/`netif` 与 NCM 收发线程对接（复用现有 `Ntb16Codec`/`NcmUsbBridge` 分帧）；Java 侧提供 `open/connect/send/recv/close` | JVM 可测部分单测；车机上能 ping 通 iPhone `fe80::2` 的邻居 |
| **2. 抽象 + 逐一迁移** | socket 抽象层 + 按通道迁移（控制 → NTP → iAP 隧道 → 视频 → 音频 → 麦克风 → 缓冲音频），**每迁移一条都保留内核路径开关**，一次点火 A/B 一条 | 每条通道的实车判据（上屏/出声/连上/触控）逐条打勾 |

**阶段 2 已开工（2026-10-12 第四轮，提交见下）**：
- ✅ **抽象层落地**：`transport/DatagramNetwork.kt` —— `DatagramNetwork`/`DatagramTransport`/`ReceivedDatagram`
  + `KernelDatagramNetwork`（`java.net.DatagramSocket` 的 1:1 实现；`reuseAddress` 与"绑 netdev 链路本地"
  这两条 ROM 约束原样搬进来）。设计要点：`receive` 在端点关闭时**抛异常**（既有接收循环正是靠这个语义退出），
  接口形态与 lwIP 的 BSD socket API（socket/bind/sendto/recvfrom）可直接对应。
- ✅ **样板通道 NTP 迁移完成**：`NtpClock` 改为从 `DatagramNetwork` 取端点；新增
  `NtpClockNetworkTest`（3 例，用假 transport 让测试扮演手机：`listen` 返回端点端口、`close` 传到 transport、
  收到的 `PT_REQUEST` 会被回以"把请求的 transmit 戳抄进 originate"的 `PT_RESPONSE`、发送线程会向对端
  timing 端口发 `PT_REQUEST`）。行为零变更：`:shared 1013/0`、`:common 590/0`。
- ⏳ **剩余 8 条通道**（按 §2 表，工作量从小到大）：`NtpClock` ✅ → `AudioStream`(UDP) ✅ →
  `MicrophoneUplink`(UDP) ✅ → `AirPlayPortSelector` → `VideoSettingsChannel` → `IapTunnel` → `ScreenStream` →
  `BufferedAudioStream` → `AirPlaySession`（控制监听 + accept，且 `:838` 处还有一个 keep-alive
  `DatagramSocket` 要走 `DatagramNetwork`）。

**已迁移的 5 条（2026-10-12 第四/七/八轮）**：`NtpClock`、`AudioStream`（RTP 数据 + RTCP 控制两个端点）、
`MicrophoneUplink`、`VideoSettingsChannel`、`ScreenStream`。**剩余 4 条**：`BufferedAudioStream`、`IapTunnel`、
`AirPlaySession`（含 keep-alive `DatagramSocket`），以及**级联的一步**：`AirPlayPortSelector`
（其返回类型是对外 API 的 `ServerSocket`）→ `CarPlayVpnService:223,227` → `AirPlaySession`，要一起改并同步
`AirPlayPortSelectorTest`/`AirPlayDualListenerTest`。
- `DatagramTransport.receiveBufferSize`（音频路径要抬高接收缓冲再读回实际值，平台可能压回；用户态栈映射到 lwIP `SO_RCVBUF`）；
- `MicrophoneUplink` 的失败路径（`Log.e` + `stats.failure(SOCKET_CREATION)`）**必须保留**，迁移只换端点来源。
- 测试取向上刻意克制：`AudioStream` 用假 `DatagramNetwork` 验证"两个端口正确 + 接收缓冲请求确实下发 + close 触达两个端点"；
  `MicrophoneUplink` **不加** JVM 测试（`start()` 需要 `AudioRecord`，为测试而加接缝不划算），改为依赖编译期类型检查 +
  迁移前后逐行对照；`NtpClock` 已有 3 例行为测试。
- 基线：`:shared 1015/0`、`:common 590/0`、干净 APK 10,919,606 B（提交 `703b83c7`、`25d88895`）。

---

## 6. 阶段 2 的 TCP 侧抽象设计（2026-10-12 定稿，供下一步实施）

**先量了真实用量**（6 个 TCP 通道全量 grep，结论比预想窄得多）：
- **只有 listen/accept，没有任何客户端 `connect()`**（AirPlay 的 TCP 连接都是手机连过来）；
- **没有用到任何 TCP 选项**（`setTcpNoDelay`、`setSoTimeout` 均零命中）；
- 流一律是 `getInputStream()`/`getOutputStream()`，再包成 `BufferedInputStream`/`BufferedOutputStream`/
  `DataInputStream`；
- 服务端形态统一：`ServerSocket()` → `bind(InetSocketAddress(addr, port[, backlog]))` → `localPort` → `accept()`
  （`AirPlayPortSelector` 需要"绑失败即返回 null"的探测语义；`IapTunnel` 有"同一端口绑另一个地址"的**双栈绑**；
  `AirPlaySession` 的 `ServerSocket.bind(..., 50)` 用了 backlog）。

**因此抽象保持最小**（与 `DatagramNetwork` 同风格，BSD socket 可 1:1 对应 lwIP）：

```kotlin
interface DuplexSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    /** 对端地址+端口；控制通道把它**原样**写进日志，所以保持成一个值（不能拆成 address+port）。 */
    val remoteSocketAddress: InetSocketAddress?
    /** 量出来的三个选项：视频流抬高接收缓冲并关 Nagle，缓冲音频也抬缓冲，控制/保活通道开 keepAlive。 */
    var receiveBufferSize: Int
    var tcpNoDelay: Boolean
    var keepAlive: Boolean
}

interface DuplexServer : Closeable {
    val localPort: Int
    /** 阻塞直到对端连入；端点关闭时抛 IOException（既有 accept 循环正是靠这个语义退出）。 */
    @Throws(IOException::class) fun accept(): DuplexSocket
}

interface StreamNetwork {
    /** [port]=0 时由系统选端口；绑不上时抛 IOException（`AirPlayPortSelector` 据此返回 null）。 */
    @Throws(IOException::class)
    fun listen(address: InetAddress, port: Int = 0, backlog: Int = 50, reuseAddress: Boolean = true): DuplexServer
}

/** 一个会话所用的整张网络：内核栈（今天）或用户态栈（有线 API19 将来）。 */
interface SessionNetwork : DatagramNetwork, StreamNetwork {
    companion object { val kernel: SessionNetwork = KernelSessionNetwork }
}
```

**实施顺序（每条都保留内核默认值，行为零变更）**：
1. `AirPlayPortSelector`（纯探测，最易验证）→ 2. `VideoSettingsChannel` → 3. `IapTunnel`（含双栈绑）→
4. `ScreenStream` → 5. `BufferedAudioStream` → 6. `AirPlaySession`（控制监听 + `:838` 处还有一个
`DatagramSocket`（事件通道）要走 `DatagramNetwork`）；
7. 最后把 `SessionNetwork` 注入 `CarPlayMediaEngine`/`CarPlayController`，由**传输**决定注入内核实现还是
   用户态实现（属性 `debug.diplay.ncm_legacy`）。

**第 7 步的注入点清单（2026-10-12 量好，全仓只有 3 个文件）**：
| 位置 | 要传 network 的构造/调用 |
|---|---|
| `airplay/CarPlayMediaEngine.kt` | `AudioStream`(:194)、`ScreenStream`(:117)、`IapTunnel`(:343)、`BufferedAudioStream`(:248)、`VideoSettingsChannel`(:407)。给该类加 `network: SessionNetwork = SessionNetwork.kernel` 并在这些点透传 |
| `airplay/AirPlaySession.kt` | `NtpClock()`(:100)，以及它自己持有的 event server / keep-alive UDP 端点 |
| `network/CarPlayVpnService.kt` | `AirPlayPortSelector.bind`(:223)、`bindAll`(:227)，以及监听/accept 路径 |
| 由谁决定 | `orchestration/CarPlayController` 按 `NcmLegacyPolicy`（`debug.diplay.ncm_legacy`）在"内核实现 / 用户态实现"之间选，注入给上表三者；**默认内核 ⇒ 行为零变更** |

**lwIP 侧映射**：`lwip_socket(AF_INET6, SOCK_STREAM, 0)` / `lwip_bind` / `lwip_listen` / `lwip_accept` /
`lwip_recv` / `lwip_send`，Java 侧把每连接包成 `InputStream`/`OutputStream`（阻塞 read/write 直接映射）；
UDP 侧 `lwip_socket(SOCK_DGRAM)` + `lwip_sendto`/`lwip_recvfrom`。双栈绑与"同端口不同地址"由 lwIP 自身支持。

**风险点（实施时逐条验）**：① `AirPlayPortSelector` 的失败语义必须仍是"异常→null"，不能变成静默成功；
② `IapTunnel` 的第二个 server 绑 `bound.localPort`，用户态栈必须允许同端口绑不同地址；
③ `accept()` 的超时/中断语义（现有实现靠 `close()` 打断阻塞 accept）在 lwIP 侧要保持。

**`reuseAddress` 必须做参数，不能做默认行为**（量出来的差异，别想当然）：
TCP 服务端里 `IapTunnel:67,94`、`ScreenStream:58`、`VideoSettingsChannel:32`、`BufferedAudioStream:47`
都设了 `reuseAddress = true`，但 **`AirPlayPortSelector` 故意不设**（它靠"绑不上"来判断端口占用，
可复用的端口会把被占的端口误判为空闲），`ClusterArtworkHttpServer:71-76` 更是在注释里写明**故意**用 `false`。
所以 `listen(..., reuseAddress: Boolean = true)` 由调用点显式传值。

**已落地（2026-10-12 第六轮）**：`transport/StreamNetwork.kt` —— `DuplexSocket` / `DuplexServer` /
`StreamNetwork` / `SessionNetwork`（`SessionNetwork.kernel` = `KernelSessionNetwork`，把内核的 UDP 与 TCP
实现合到同一个可注入接缝），`listen` 的 `reuseAddress` 按上面量到的事实做参数。

**迁移进度（2026-10-12 第十二轮末，阶段 2 的通道迁移全部完成）**：**9/9 条通道**都走接缝了 ——
`NtpClock`、`AudioStream`（RTP+RTCP 两端点）、`MicrophoneUplink`、`VideoSettingsChannel`、`ScreenStream`、
`BufferedAudioStream`、`IapTunnel`（含双栈绑）、`AirPlaySession`（控制 socket + keep-alive UDP + event 监听）、
`AirPlayPortSelector` → `CarPlayVpnService`（监听与 accept 全链）。

**收尾时又量到 3 项并补齐**（同样都不是预想的）：
- `DuplexServer.soTimeout`（测试用它给 `accept()` 设上界）；
- `DuplexServer.isClosed`（"替换失败必须证明旧监听已释放"要能断言）；
- `KernelSessionNetwork.wrap(Socket)` —— 让**已经持有 `java.net.Socket`** 的调用方/测试能把它呈现为 `DuplexSocket`。
- **`TcpLiveness` 的逃生口**：它靠 `getFileDescriptor$()` 设 keepalive 选项，**只有内核栈有 fd** ⇒ 新增
  `DuplexSocket.kernelSocket()`（用户态栈返回 null，调用点跳过）。这是"抽象要为真实差异留门"的典型一例。

**测试代价（诚实记账）**：为这次级联改了 **13 个 shared + 12 个 common** 测试用例——它们原本直接往
`AirPlaySession.eventSocket` / `CarPlayVpnService.serverSocket` 里塞 `java.net.Socket`/`ServerSocket`，
现在改成 `KernelSessionNetwork.wrap(...)`；**没有降低任何断言强度**（`ClusterContentTest`/`DashboardContentDeliveryTest`
的"事件通道未就绪不得提前认领"这类断言原样保留）。

基线：`:shared 1017/0`、`:common 590/0`、干净 APK 10,921,819 B。

**剩余（阶段 1 的前置）**：装 NDK（**待用户批准**）→ 见 §7 的 lwIP 实施要点 → 注入 `SessionNetwork`（§6 第 7 步的清单）。

抽象因此累计补了 5 项（**每一项都是编译/读代码时才发现的，没有一项是预想的**）：
`receiveBufferSize`（音频）、`tcpNoDelay`（视频）、`keepAlive`（控制/保活）、`remoteSocketAddress`
（日志原样输出对端地址）、`DuplexServer.localSocketAddress` + `DuplexSocket.setSoLinger`（iAP 隧道日志与
"掉线即 RST"语义）。**教训：先读调用点，再定接口**——用属性语法写的 socket 选项（`socket.tcpNoDelay`）
不会被 `setTcpNoDelay(` 这类方法名 grep 命中。

**两个踩过的坑（写下来免得重复）**：
1. **TCP 选项是用 Kotlin 属性语法写的**（`socket.tcpNoDelay`、`socket.receiveBufferSize`），第一遍按
   `setTcpNoDelay(` 之类的方法名 grep **全部漏掉**——抽象因此少了一轮，是编译到 `ScreenStream` 才发现。
2. **新参数的位置要按调用点风格定**：`ScreenStream` 的主调用点用**位置参数**传 lambda
   （`ScreenStream(key, session::logDebug)`），而测试用**尾随 lambda**；`VideoSettingsChannel` 的调用点
   则是尾随 lambda。结论：新参数放在 lambda 参数**之前**，并把测试改成具名传参（`onDiagnostic = { … }`），
   否则任一边必然被顶掉。
| **3. 实车 A/B 与回退** | 用户态桥 vs 内核网卡：`maxGap`/`kbps`/`touch2frame` + **重启后是否开箱可用**；内核路径保留为属性开关 | 结论写回 doc 28/57，目标达成 |

## 4. 更便宜的替代路线（先探针，后决策）

| 路线 | 做法 | 成本 | 待验证 |
|---|---|---|---|
| **B. app 自举 root** | 提权链是 `/sbin/adb-ec`(setuid root) + PATH 劫持 `pppd`。若**应用自身 uid** 就能执行它，则让 app 在开机/USB 接入时自动重建 rootd，**内核网卡路径一行不改** | 极低 | `tools/cs11_probe_rootless.ps1` 的 B 段（`run-as` 试执行 + 看 `root_out.txt`） |
| **C. 借力 ROM 网卡管理** | ROM 有 `EthernetDataTracker`、`adb-ec` 的 carbit 子系统（USB 事件→路由 + `sys.carbit.tun`）即**原生 CarPlay dongle 支持栈**；若有特权服务/属性可拉起 `usb1` | 低（若存在） | 同脚本 C 段（`service list`/`dumpsys ethernet|connectivity`/carbit 属性 + `ip link set usb1 up` 失败证据） |
| **D. 维持现状 + 顺手恢复** | 保留 root 点火脚本，改进失败可见性（doc 54 B3）与一键恢复 | 零 | — |

**推荐顺序**：B/C 探针 → 有便宜解就走 → 否则按 §3 分阶段做 A。
A 的**阶段 0 与 B/C 结论无关**（无论走哪条，NDK + lwIP 工具链都是前置，热点原生模块也受益），
可在车机离线时先做。

---

## 5. 施工图：API19 用户态路径具体要新增/改造什么（2026-10-12 逆向 EasyPlay v74 后定稿）

证据缩写：`EP/`=`_analysis/easyplay/src/sources/com/shilapi/xcertplay/`，`V74/`=jadx 反编译
`_artifacts/apk-reference/EasyPlay-Lynk-Android4.4-10-74.apk`（实机 pid 3780 跑的就是 74 版）。

| # | 件 | EasyPlay 做法与证据 | 我们的现状 | 工作量 |
|---|---|---|---|---|
| 1 | **API19 NCM 发现** | **不用** `UsbDevice.getInterface()`：`LegacyUsbConfiguration.ncm()` 自解析 `connection.getRawDescriptors()`——校验 vid=0x5AC；从 `controlTransfer(0x80,0x08,…)` 取 active config；在该 config 内找 control=class2/subclass13/alt0（且唯一带中断 IN 端点）与 data=class10/alt1；CDC Union 必须自指；Ethernet 功能描述符 type=0x0F 取 iMACAddress/wMaxSegmentSize；NCM 描述符 type=0x1A 取 capabilities（`EP/transport/LegacyUsbConfiguration.java:574-839,1063`） | `transport/NcmFunctionDiscovery.kt:31-58` 是纯 API21+（`UsbConfiguration`）版；API<21 无实现 | **照抄级**（解析逻辑可直接照搬判据）。✅ 已落地 `LegacyNcmDescriptors.kt`（39 例单测）+ **序号匹配**抽成纯函数 `LegacyNcmSelection`（4 例单测，2026-10-12 第十三轮）——探针最易错的一步已前移到单测，不必再靠点火窗口发现 |
| 2 | 造成 `UsbInterface`/`UsbEndpoint` | **反射**构造器 `UsbInterface(int,int,int,int,Parcelable[])`、`UsbEndpoint(int,int,int,int)`（`:997-1012`，仅 `SDK_INT<21` 走） | 无 | 照抄级 |
| 3 | 切到 CarPlay 配置（config 6） | **反射** `libcore.io.Libcore.os.ioctlInt(fd, USBDEVFS_SETCONFIGURATION=0x80045505, MutableInt(id))`，再复核 active id（`:30,1015-1061`） | 无（我们依赖厂商内核已选好配置） | 照抄级（已 active 可跳过） |
| 4 | **`SET_INTERFACE`** | **原生 JNI**：`LegacyUsbNative.setInterface(fd, ifnum, alt)`，随 `libdiplay_lwip.so` 装载（`EP/transport/LegacyUsbNative.java:13,19`）；顺序 **alt0 → 协商 → alt1**（`V74/transport/NcmUsbBridge.java:1349-1352`） | ⚠️ 我们 `NcmUsbBridge.kt:498-512` 用 `controlTransfer(0x0B)` —— **不等价**：原生调用这一事实说明仅靠 controlTransfer 不足以让内核更新 altsetting | **需要一个小 .so**（A 路线的最小原生件；也是必须先装 NDK 的直接原因） |
| 5 | **NCM 类请求（协商）** | `NcmNegotiation`：`GET_NTB_PARAMETERS(0xA1/0x80)` → input/output=16384、divisor4/rem0/align4；`SET_NTB_FORMAT(0x1B)=1`；cap 位4 → `SET_CRC_MODE(0x1E)=0`；`SET_NTB_INPUT_SIZE(0x1F)`；cap 位0 → `SET_ETHERNET_PACKET_FILTER(0x43)=14`（`EP/transport/NcmNegotiation.java:224-258`） | **我们完全没有这类请求** | 照抄级（5 个 controlTransfer） |
| 6 | claim/重试 | control→data 逐个 claim，**5 次 ×100 ms**（`V74/NcmUsbBridge.java:1105-1137,1322-1345`） | 有 claim，**无重试**（`NcmUsbBridge.kt:369-399`） | 照抄级（上游 `5a5b5328` 同款，见 doc 56 §4） |
| 7 | 分帧/读块 | NTB16(NTH16+NDP16)，读块 = `inputSize`（**16384**），`bulkTransfer(in, buf, 16384, ≤100 ms)`，512 对齐时补 1 个 0；队列上限 256 帧/1 MiB | `Ntb16Codec.kt` 可复用（divisor4/rem0/align4 布局一致）；`NcmUsbBridge` 读块需改为 16384 | 小改 |
| 8 | **用户态 TCP/IP 栈** | 自带 **lwIP**：`libdiplay_lwip.so`（379 KB）+ 31 个 JNI（`LwipNative.java`）+ `LwipSessionNetwork`（`usesKernelSockets=false`，`EP/network/WiredSessionNetwork.java:19-21`） | 无 | **≫ 其余全部之和**（vendored lwIP + JNI + socket 抽象 + §2 的 9 条通道迁移） |
| 9 | 地址/路由 | **不固定 `fe80::2`**：lwIP 用 iPhone 的 iMACAddress 派生 **EUI-64** 链路本地（实测 `rtsp://fe80::9c92:4fff:fe09:bf8b`），ND/ICMPv6 由 lwIP **ND6** 完成 | 我们全线假设 `fe80::2%usb1`（`Cs11NetdevBootstrap`/`WildcardAddress`/`serverBindAddress`/iAP2 上报） | 见下注 |
| 10 | `CarPlayVpnService` 的角色 | API19 **只当可绑定的普通 Service**（只走 `attachWireless`），**从不 `establish()`**，因此不弹 VPN 授权（`V74/network/CarPlayVpnService.java:414,478-508,545-546`） | 我们同类只支持内核 `ServerSocket` | 随 #8 一起改 |

**第 9 条的重要缓解**：iPhone 是从 **iAP2 `0x4301 CarPlayStartSession`** 里拿到我们的地址与端口的
（我们的 `Iap2WiredControlClient` 已在发），所以**我们可以自己指定本端链路本地地址**——即可以让 lwIP
沿用 `fe80::2`，从而**不必**改动 `serverBindAddress()`/`WildcardAddress`/上报逻辑这一整片假设。
若无此简化，EUI-64 会波及 iAP2 上报、NTP、事件通道、音频/视频绑定与无线路径的共用代码。

**结论（工作量排序）**：#8（新栈）≫ #1~#7+#10（照抄/小改）；#4 使 **NDK 成为 A 的前置**（哪怕在引入 lwIP 之前）。
因此 A 的合理起点仍是 §3 的**阶段 0**：装 NDK → 出一个最小 `.so`（先只做 `setInterface`+版本号自证）→ 车机验证
工具链与 `SET_INTERFACE` 是否真能让 altsetting 生效（**这一步本身就是关键未知项**，值得先用最小代价证伪/证实）。

**已有雏形（降低 #1/#4/#5 的改动量）**：我们并非从零开始——
`shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt:492-525` 已有
`selectDataAlternatesViaControlTransfer(connection)`（对每个 class 0x0A 数据接口用 `controlTransfer(0x0B)`
发 alt=1）与 `dataInterfaceNumbers(raw)`（扫 `connection.rawDescriptors` 收集数据接口号）。
阶段 1 要做的是把这段**升级**为：完整校验（union/ethernet/NCM 功能描述符、alt0/alt1、bulk 成对）→
改用**原生 `LegacyUsbNative.setInterface`** 真正改 altsetting（controlTransfer 只能当回退，见 #4）→
补 `NcmNegotiation` 的 5 个类请求 → 读块改 16384。

**已落地（2026-10-12 第二轮，离线可验证）**：
- `transport/NcmNegotiation.kt`（新）：`GET_NTB_PARAMETERS` 响应解析与校验、TX 数据报偏移推导、
  RX 的 NTB16/NDP16 链校验，以及 `configure()`/`enablePacketFilter()` 的请求时序。
  **请求码采用规范值**（0x80/0x1B/0x1E/0x1F/0x43）——依据是**内核 `cdc_ncm` 正用这些码与这台 iPhone
  谈成**（我们的内核网卡路径就是它跑通的）；反编译版本里 jadx 把字面量误标成了 TLS 常量，不可用。
  时序/取值/校验规则沿用 legacy 版本（`format16`、`crcOff`、`inputSize`、`packetFilter flags=14`）。
  单测 `NcmNegotiationTest`（10 例）钉住：合法参数集（车机日志那组 16384/16384/4/0/4）、双向上限收敛到
  16384、NTB16 能力位、<2048 拒绝、alignment 必须 4 起 2 的幂且小于输出上限、remainder ≥ divisor 拒绝、
  长度/自述长度校验、NDP16 链（含自环/错签名/错表长）拒绝。
- ⚠️ **两个待车机确认的点（阶段 0/1 必做）**：
  1. **`GET_NTB_PARAMETERS` 的真实字节布局**：legacy 版本按 output@16 / divisor@20 / remainder@22 /
     alignment@24 解析，与内核 `ntb_parameters` 结构的常见排布（output@12…）相差 4；由于其车机日志给出的
     数值自洽（16384/16384/4/0/4），我们**先沿用 legacy 的偏移**，并应在车机上把 28 字节原样 dump 出来核对
     （`configure()` 里加一条 hex 诊断即可，成本 1 行）。
  2. **TX 数据报偏移**：我们的 `Ntb16Codec` 用固定 `DATAGRAM_INDEX = 28`，而按协商值（divisor=4/rem=0/align=4）
     算出的偏移是 **30**——因为规范要求 `(offset + 14) % divisor == remainder`（legacy 的
     `ethernetRemainder = (remainder - 14) & (divisor - 1)`）。阶段 1 应把 TX 偏移改为
     `NcmNegotiation.encodeFrameOffset(parameters)`，并在车机上确认 iPhone 是否真的校验这一点。

---

## 7. 阶段 1（引入 lwIP）实施要点（2026-10-12 备，**前提：用户批准装 NDK**）

**为什么要自带而不是借用**：见 §1（本 ROM 无 TUN）。EasyPlay 的实证方案就是 lwIP（BSD），我们照做。

### 7.1 工具链与目录
- 装 NDK（`sdkmanager --install "ndk;<r26>"`，与仓库既有约束一致：`shared/src/main/jni/Android.mk` 那套
  是 ndk-build 风格，且当年因 NDK 版本被禁用；`local_hotspot_radio` 与我们的 `legacy_usb` 都在同一个 `Android.mk`）。
- 目录建议：`shared/src/main/cpp/lwip/`（vendored 上游，BSD，保留 LICENSE）+ `shared/src/main/cpp/bridge/`
  （我们的 netif 胶水 + JNI）。构建用 CMake 或 ndk-build 皆可——**优先 ndk-build**，与既有 `Android.mk` 一致。
- **只出 armeabi-v7a**（车机是 i.MX6Q 32 位）；`mobile/build.gradle.kts` 的 `ndk.abiFilters` 已含它。

### 7.2 lwIP 配置要点（`lwipopts.h`）
`LWIP_IPV6=1`、`LWIP_IPV6_ND=1`（ND6 负责邻居发现与链路本地地址）、`LWIP_TCP=1`、`LWIP_UDP=1`、
`LWIP_DHCP=0`、`LWIP_ARP=0`（IPv6-only 链路）、`LWIP_NETIF_TX_SINGLE_PBUF=1`（避免链式 pbuf 拼包）、
`MEM_SIZE`/`PBUF_POOL_SIZE` 按 16 KiB NTB 输入与视频突发调大、`LWIP_SO_RCVBUF=1`（我们抽象要
`receiveBufferSize`）、`SO_REUSE=1`（`AirPlayPortSelector` 与 `IapTunnel` 的双栈绑需要）、
`LWIP_SO_LINGER=1`（`IapTunnel` 用 `setSoLinger(true,0)`）、`TCP_MSS≈1400`、`LWIP_NETCONN=0`/`LWIP_SOCKET=1`
（用 socket API 直接对应我们的接缝）。

### 7.3 netif 胶水（与已有代码的接缝）
- **TX**：`netif->linkoutput` 把 pbuf 内容交给我们的 `Ntb16Codec`+`NcmNegotiation.encodeFrameOffset()`
  组一个 NTB16 块，再经 `NcmUsbBridge` 的 bulk OUT 写出（**不要**另写一套分帧）。
- **RX**：`NcmUsbBridge` 的解帧结果（一个以太帧）直接 `netif->input(pbuf, netif)` 注入 lwIP；
  注意在 lwIP 线程上下文里调用（`tcpip_input`）。
- **地址**：建议给 netif 配**静态 `fe80::2/64`**（而不是 EasyPlay 的 EUI-64）。理由：iPhone 是从 iAP2
  `0x4301 CarPlayStartSession` 里拿到我们的地址与端口的，所以我们可以自选；这样 `serverBindAddress()`、
  iAP2 上报、NTP/事件/音频/视频的绑定逻辑**一处都不用改**（见 §5 第 9 条）。
- **线程模型**：用 lwIP 的 `tcpip_thread` + `sys_mbox`（`NO_SYS=0`），我们只在两端触碰它：RX 注入、
  socket 调用走 `tcpip_callback`/netconn 包装。**别**自己写 TCP。

### 7.4 JNI 面（正好对应我们的接缝，不需要更多）
`open(AF_INET6, SOCK_STREAM|SOCK_DGRAM)` / `bind` / `listen` / `accept` / `recv` / `send` / `sendto` /
`recvfrom` / `close` / `setsockopt`（`SO_RCVBUF`/`TCP_NODELAY`/`SO_KEEPALIVE`/`SO_LINGER`）。
Java 侧把每个连接包成 `InputStream`/`OutputStream`（阻塞 read/write），实现
`DatagramTransport` / `DuplexServer` / `DuplexSocket` 三个接口 —— 即 `UserSpaceSessionNetwork`
（`SessionNetwork` 的第二个实现，与 `KernelSessionNetwork` 并列）。

### 7.5 风险与验收
- **风险**：NDK 版本与 `-Werror` 冲突（lwIP 自身会有 warning ⇒ 只对我们自己的文件开 `-Werror`）；
  `SO_RCVBUF`/`TCP_NODELAY` 语义差异（抽象已允许抛异常，调用点都有 `runCatching`）；
  单线程 lwIP 与视频/音频线程的优先级竞争（车机 4×A9，需实测）。
- **验收（车机）**：`NCM legacy stage=ready` → 用户态网络起来 → 控制连接建立（`airplay TCP accepted`）
  → 上屏/出声/触控 → 跑 A/B 指标（`maxGap`/`kbps`/`touch2frame`）→ **断电重启后开箱可用**（本路线的最终目的）。

---

## 8. 级联迁移的行为等价专项复核（2026-10-12 第十三/十四轮，基于 diff 逐条对照）

复核方法：对提交 `f5ce425c`（`AirPlayPortSelector` / `CarPlayVpnService` / `AirPlaySession` 三处级联）
逐条取 `git show` 的 before/after 对照，而不是凭记忆。

| # | 复核点 | 旧行为 | 新行为 | 判定 |
|---|---|---|---|---|
| 1 | selector 候选端口失败时的释放 | `bindPort` 在 `bind` 抛错时 `close()` 半绑定的 `ServerSocket` 再抛 | 内核 `listen` 原先直接冒泡 ⇒ **会漏掉这次释放** | ❌ **已修**：`KernelSessionNetwork.listen` 失败即 `close()`（并在注释里写明原因） |
| 2 | `SO_REUSEADDR` | selector 与 event server **依赖平台默认**（关） | 内核实现**显式** `reuseAddress=false` | ✅ 等值且更明确（Java/Android 该默认即 false；显式化后不再依赖实现细节） |
| 3 | `AirPlaySession.localAddress` | `socket.localAddress`：**未连接** socket 上返回通配 `0.0.0.0` | `localSocketAddress?.address`：未连接时为 **null** | ✅ 生产路径的 socket 永远是 `accept()` 来的已连接连接 ⇒ 等值；仅测试里传裸 `Socket()` 的情形不同，且那些用例已改为包装 |
| 4 | `host` / `peerAddress` | `socket.inetAddress` | `remoteSocketAddress?.address` | ✅ 已连接 socket 上两者同值 |
| 5 | 监听地址判族（日志 family） | `ServerSocket.inetAddress` | `localSocketAddress?.address` | ✅ 同值 |
| 6 | `isInternalAirPlayPeer` 入参 | 直接传 `getInetAddress()/getLocalAddress()`（非空） | 先取两个可空地址，**任一为 null 则判为"外部"** | ✅ 生产不可能为 null；null 分支取的是原语义下的合理默认（无 listener identity 时按外部处理） |
| 7 | `TcpLiveness` | 对控制 socket 直接调 | 经 `kernelSocket()`（**只有内核栈有 fd**）；用户态栈跳过 | ✅ 内核路径逐字不变；用户态本就没有 fd 可用 |
| 8 | keep-alive UDP 端点 | `DatagramSocket(null)` + `reuseAddress=true` + 绑 `serverBindAddress()` | `network.open(serverBindAddress())`（这两点**内置在内核实现里**） | ✅ 等值 |
| 9 | event 监听 | `ServerSocket()` + `bind(addr, 50)` | `listen(addr, port=0, backlog=50, reuseAddress=false)` | ✅ backlog 保留；复用位显式化为原默认 |
| 10 | `accept()`/`close()` 语义 | 关闭时 `accept()` 抛异常 | 同（`DuplexServer` KDoc 明确写死这条契约） | ✅ |
| 11 | **日志措辞（ADR-9 验收项）** | — | `airplay listener ready…`、`airplay TCP accepted…`、`airplay connection accepted from…`、`airplay event connection accepted from … noDelay=…` 全部**逐字未改** | ✅ diff 中无一条日志字符串被修改 |

**新增的回归防线**：`KernelSessionNetworkTest`（2 例，走**真实 socket** 而非假件）——
① 绑忙端口必须抛 `IOException`（这正是 `AirPlayPortSelector` 回退逻辑所依赖的性质），且失败后端口仍可用；
② accept 到的连接能双向传输字节，且 `localSocketAddress`/`remoteSocketAddress` 正确。
---

## 10. 有线微信语音"无声"根因与修复（2026-10-13，实车已验收）

**症状**：有线 CarPlay 下微信语音**录不到声音**（麦克风指示正常出现、发出去的语音无声）；**无线正常**；EasyPlay 有线也正常。

**排查路径（关键是拿"无线正常"做对照，而不是继续猜）**：

| 步骤 | 结果 |
|---|---|
| 车机日志：微信按住说话 | `SETUP type=100 audioType=speechRecognition input=true audioFormat=16384 dataPort=…`；我们 **确实开了麦**：`Microphone: start … source=VOICE_RECOGNITION`，`level peak=6742`，`udpSent=245 sendErrors=0` |
| 接口计数 | `usb1_tx` 增长与我们的 `udpSent` 相符 ⇒ **包确实从 usb1 发出了**（排除发错接口） |
| 打包器逐字节对比 EasyPlay | **完全相同**（`header[0]=0x80`、PT&0x7f、seq@2、ts@4、SSRC=0、AAD=header[4..12]、nonce 小端@4、尾部 nonce）⇒ 包格式没问题 |
| **无线 vs 有线对照（用户提示）** | 无线：`codec=**OPUS**`；有线：`codec=**LPCM**` ⇒ 手机在两种链路用**不同编码**，而**无线的 Opus 路径是好的** |
| EasyPlay 有线编码 | `Audio negotiated type=100 audioType=speechrecognition codec=**LPCM** rate=48000 rtpClock=48000 channels=1` ⇒ 它也走 LPCM，所以不是"编码选择"问题 |
| **包大小（根因）** | 我们 LPCM 每帧 **20 ms**：48 kHz 单声道 = 1920 B 载荷，加 RTP 12 + tag 16 + nonce 8 = **1956 B ⇒ 超过 NCM 链路 1500 B MTU，每个麦克风包都被 IPv6 分片** ✗；而**手机自己**发下来的 LPCM 帧只有 **756 B**（`wireBytes=756`）⇒ 手机用小帧。无线正常正是因为 Opus 帧只有几十~几百字节，不分片 |

**修复**：新增 `airplay/MicrophoneFramePolicy.kt`，把 **非 Opus**（有线 LPCM）上行帧限制到 MTU 安全长度：
`MAX_WIRE_BYTES = 1200`（留出 IPv6+UDP 头）⇒ 48 kHz 单声道封顶 **12 ms**（线上 1188 B）、48 kHz 立体声 6 ms；
手机已指定的、本来就装得下的帧长保持不变；属性 `debug.diplay.mic_frame_ms` 可做单窗口 A/B（1..60 ms，且仍受 MTU 上限约束）。Opus/无线路径不受影响。

**实车验收（2026-10-13）**：装机后日志 `Microphone: start … frameMs=12`、`udpSent=412/411/17 sendErrors=0`，**用户确认微信语音有声音了** ✓。

**留作回归防线**：`MicrophoneFramePolicyTest`（5 例，覆盖 48k 单/立体声上限、本来装得下的帧长不被改、属性 override 生效但不可越过 MTU、以及全部支持的 PCM 组合都在 MTU 内）。
---

## 11. 阶段 1 进展：lwIP 已编入 APK（2026-10-13，提交 `51d2becf` + `d24a4901`）

**已完成（原生侧全部打通）**：

| 项 | 结论/证据 |
|---|---|
| NDK 版本 | **r25c（25.2.9519653）**，不是 r26——AGP 明确拒绝：`Platform version 19 is unsupported by this NDK`（r26 最低 21）；r25c 的 `meta/platforms.json` 里 `"min": 19` ✓。装在 `android-sdk\ndk\25.2.9519653`（腾讯镜像直下，11 MB/s） |
| 构建系统 | **CMake 3.22.1**（AGP 的 ndkBuild 分支在本版本既无 `arguments()` 又不接受目录，且不传 `NDK_PROJECT_PATH` ⇒ ndk-build r25 直接拒绝）。原 `jni/Android.mk`/`Application.mk`/`cpp/Android.mk` 已删除，**单一 CMakeLists** 出三个库 |
| 产物 | `liblwip_bridge.so`（**armeabi-v7a 465 KB** / arm64 570 KB）、`liblegacy_usb.so`、`liblocal_hotspot_radio.so`；**已打包进 APK**（APK 11,164,243 B） |
| 自写移植层 | `bridge/arch/cc.h`（类型/打包属性/**诊断走 logcat**）、`bridge/arch/sys_arch.h`（pthreads 系类型）、`bridge/sys_arch.c`（信号量/邮箱/互斥/线程）、`bridge/cs11_netif.c`（netif）、`bridge/cs11_lwip_jni.c`（18 个 JNI 符号，`llvm-nm` 已核对齐全） |
| 关键配置决定 | `fe80::2` **固定**（iAP2 已告知手机，所有 bind/上报零改动）；`TCP_WND = 16 KiB`（**沿用内核路径实车调优值**：接收窗就是手机视频发送端的节流阀，doc 51）；`LWIP_TIMEVAL_PRIVATE=0` + `SOCKLEN_T_DEFINED`（bionic 已提供）；IPv4/DHCP/DNS/ARP 全关 |
| JNI 文件注意 | **不要**同时包含平台 `<sys/socket.h>` 与 `lwip/sockets.h`（`sockaddr`/`iovec`/`linger`/`SO_*` 全面重定义）⇒ 只用 lwIP 自己的定义 |

**下一步（Kotlin 侧，唯一剩下的部分）**：
1. `transport/UserSpaceNetworkNative.kt`：`System.loadLibrary("lwip_bridge")` + 18 个 `external fun`（签名见 `bridge/cs11_lwip_jni.c`）；
2. `transport/UserSpaceSessionNetwork.kt`：实现我们已铺好的 `SessionNetwork` 接缝（`DatagramTransport`/`DuplexServer`/`DuplexSocket`），地址用 16 字节 IPv6；
3. **RX/TX 与 `NcmUsbBridge` 对接**：读线程把以太帧交给 `nativeInjectFrame`，写线程轮询 `nativePollTxFrame` 后经桥发出（NTB16 分帧复用既有 `Ntb16Codec` + `NcmNegotiation.encodeFrameOffset`）；
4. 把 `UserspaceNcmTransport.stackImplemented` 置 true，让 `AUTO` 真正优先用户态；实车验证 `ncm_legacy=force`。
---

## 12. 阶段 2 完成：用户态桥成为有线默认传输（2026-10-13，提交 `b1de5423`）

### 12.1 现在的有线行为（API 19）

```
AUTO（默认）: readiness == READY（库在 APK 里）  →  用户态 lwIP 桥
              openUserspaceNcm 失败           →  自动回退内核 cdc_ncm 网卡（日志写明原因）
FORCE       : 只走用户态；缺库/失败时明确报错并给出原因（不再静默回退）
KERNEL      : 隐藏调试兜底 debug.diplay.ncm_legacy=kernel
```
- 判定在 `openDataPaths`，挂接在 `runStack`：用户态时**跳过** `attachSystemNetdev()` 与 `attachVpn()`（本 ROM 无 TUN），改用无线路径同款的**只建监听器**挂接，网络同时交给引擎与 VPN 服务；
- 绑定地址用**告知手机的那个地址**（`config.linkLocal`），而不是 `serverBindAddress()`——后者查的是内核网卡 `usb1` 的带 scope 地址，而这条路径故意没有该网卡；并把它发布为会话绑定地址，保证 iAP 隧道等通道也一致；
- 链接拥有自己的 USB claim 与 netif，随会话销毁（`close()` 与 `runStack` 的 finally 都按 `ncmOwnedLocally` 同款守卫，避免成功路径误关）。

### 12.2 性能优化（本轮）

| 项 | 值 | 依据 |
|---|---|---|
| **RX 校验和校验** | `CHECKSUM_CHECK_{IP,UDP,TCP,ICMP} = 2`（**2=从不**，注意 0 反而是"总是校验"） | NCM 段是**点到点 USB**，USB 自带 CRC；EasyPlay 在同一台车的用户态桥也是关校验。1 GHz i.MX6Q 上这是每包 CPU，视频流全程受益 |
| 校验和**生成** | 保持开启 | IPv6 的 UDP/TCP 校验和是**强制**的，发零校验和会被 iOS 直接丢弃 |
| **TCP 接收窗** | 16 KiB（维持） | 与内核路径实车调优值一致：接收窗就是手机视频发送端的节流阀（doc 51） |
| 内存/缓冲 | 堆 256 KiB、64×1600 B pbuf、`LWIP_NETIF_TX_SINGLE_PBUF=1` | 16 KiB 窗口下最多约 12 个满帧在途，其余为音频/控制通道余量 |
| 统计开销 | `LWIP_STATS 0` | 统计行由我们自己的计数器产出（5 秒一条） |

### 12.3 一个只有实车才会暴露、现已静态拦下的 bug

Kotlin 侧 18 个 `external fun` 原本没有 `native` 前缀（`start`…），而 C 侧符号是 `nativeStart`… ⇒ **每次调用都会 `UnsatisfiedLinkError`**，`System.loadLibrary` 却是成功的，实车日志只会显示有线起链莫名失败。
现在由 `tools/check_jni_symbols.ps1` 交叉核对两侧名字（Kotlin `external fun` ↔ C 的 `Java_..._` 符号），并已列入**上机前必跑**：

```powershell
pwsh -File tools/check_jni_symbols.ps1     # 期望：OK - every 18 name matches: …
```

### 12.4 上机点检流程（turnkey）

```powershell
# 0) 上机前：JNI 名核对（不跑可能白跑一趟车）
pwsh -File D:\Development\DiAuto-DiPlay\tools\check_jni_symbols.ps1

# 1) 装机并冷启（务必 force-stop，顺序影响会话能否建立）
$adb='d:\Dev Environment\platform-tools\adb.exe'; $s='0123456789ABCDEF'
& $adb -s $s install -r -d mobile\build\outputs\apk\debug\mobile-debug.apk
& $adb -s $s shell am force-stop com.shihab.diplay.hudtest
& $adb -s $s shell monkey -p com.shihab.diplay.hudtest -c android.intent.category.LAUNCHER 1

# 2) 确认走的是用户态（AUTO 默认即用户态）
& $adb -s $s shell "logcat -d -v time -s xcertplay-usb:* | /system/bin/busybox grep -E 'userspace NCM|wired AirPlay transport attached'"
#   期望三行：userspace NCM bridge ready … / userspace NCM AirPlay listener attached on lwIP … /
#              wired AirPlay transport attached kernelNetdev=false userspaceLwip=true

# 3) 确认真的有流量（每 5 秒一条；rxBytes 长期为 0 就说明 netif 没收到东西）
& $adb -s $s shell "logcat -d -v time -s xcertplay-usb:* | /system/bin/busybox grep 'userspace NCM link' | /system/bin/busybox tail -5"

# 4) 对照与回退（同一窗口内切换要重新起链）
& $adb -s $s shell setprop debug.diplay.ncm_legacy kernel   # 内核网卡对照
& $adb -s $s shell setprop debug.diplay.ncm_legacy force    # 只走用户态，缺库时明确报错
& $adb -s $s shell setprop debug.diplay.ncm_legacy ''       # 回到 AUTO

# 5) 回归项：音频（含微信语音双向）、触摸、视频流畅度、电话/Siri 麦克风
```
**判定**：
- 第 2 步三行齐全且第 3 步 `rxFrames/rxBytes` 增长 ⇒ 用户态栈通了（可开始性能对比）；
- 只到第 2 步、第 3 步全 0 ⇒ netif 收不到帧（ND/邻居/MTU/MAC 方向）⇒ 收集 `userspace NCM link` 与 ND 相关日志再定；
- 用户态路径失败不影响用车：AUTO 会自动回退内核网卡（日志写明原因）。