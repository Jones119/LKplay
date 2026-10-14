# 64 - 用户态链路的 AirPlay 服务广播（mDNS）：上屏的最后一块缺口

日期：2026-10-13　基线提交：`1fba7a21`（用户态 lwIP 有线连接已实车跑通）

## 1. 现状（实车验证过的部分）

用户态 lwIP 有线链路**已经完全工作**（车机日志证据）：

```
NCM configuration candidates=2 ids=[5, 6] chosen=6 appleEthernet=3
NCM claim platformOffset=20 controlIndex=25 dataIndex=27
NCM legacy stage=ready iface=4/1 input=16384 output=16384 status=0x87
userspace NCM bridge ready configuration=6; lwIP carries the wired link
userspace NCM AirPlay listener attached on lwIP address=fe80::2
wired AirPlay transport attached userspaceLwip=true
IAP2 READY [wired] ready=true / identification accepted / authentication accepted
wired iap2 tx=0x4301 carplay-start-session
userspace NCM link rxFrames=40 rxBytes=5824 txFrames=9 txBytes=774 txDropped=0 writeFailures=0 pending=0
```

**卡点**：**零入向 AirPlay 连接**（日志里没有 accept / `GET /info` / `SETUP` / `RECORD`），前台是我们的 `CarPlayHostActivity` 等待页 ⇒ 画面起不来。

已**证伪**的假设：
- 不是手机侧需要"信任/解锁" ✗（`IAP2 READY` + 认证通过 + 已存配对记录 ✓）；
- 不是会话启动消息缺字段 ✗（`Iap2Messages.startSession(...)` 与 EasyPlay 逐字段一致 ✓，且 `CarPlayController.kt:2000-2001` 确实传了 `ipv6Addresses = listOf(config.linkLocal)` = `fe80::2` ✓、端口也在 ✓）；
- 反复出现的 `0x5001` 不是阻塞点 ✗（它是 `NowPlayingUpdate` 元数据 ✓ 正常推送 ✓）。

## 2. 本轮新发现（关键差异）

| | 参考实现 EasyPlay | 我们 |
|---|---|---|
| mDNS | `CarPlayBonjour.java:59` `AIRPLAY_SERVICE_TYPE = "_airplay._tcp"`，`:109 interfaceMdns`，`:112 localAdvertisedAddress` ⇒ **既发现也广播** ✓ | `CarPlayBonjour.kt` 只有 `Discovery/Resolved/Probe*` 语义 ⇒ **只发现，从不广播** ✗（全仓库 `advertise|registerService` 零命中） |

**为什么内核路径以前能行、用户态路径不行**（同一条代码）：
- 内核 `cdc_ncm` 路径下 `fe80::2` 出现在**平台网络栈**（`usb1`）上 ⇒ JmDNS（`javax.jmdns`，走平台 socket）**可以绑定并广播** ✓；
- 用户态路径下 `fe80::2` **只存在于 lwIP 内部** ✗ ⇒ 任何走平台 socket 的广播都**到不了这条链路** ✗（JmDNS 无从绑定 ✓）。

⇒ 这与"iAP2 全通但零入向连接"完全吻合：**iPhone 没有任何途径发现"链路上有 AirPlay 接收端"**。

## 3. 实施方案：用 lwIP socket API 自己发 mDNS/DNS-SD

不能用 JmDNS ✗（平台栈 ✗）。用我们已打通的 lwIP UDP socket（`UserSpaceNetworkNative` 的 `nativeSocket/nativeSendTo/nativeRecvFrom` ✓，`AF_INET6`=10 / `SOCK_DGRAM`=2 与 lwIP 一致 ✓）实现一个**最小 mDNS 响应器**：

1. **socket**：`AF_INET6` + `SOCK_DGRAM`，绑定 `[::]:5353`（或 `fe80::2%scope:5353`），加入组播组 **`ff02::fb`**（scope 必须是我们 netif 的接口索引 ✓）；`SO_REUSEADDR` 用 **lwIP 的 `0x0004`** ✓（见 `docs/_digest` 与提交 `1fba7a21` 的常量对照表）。
2. **记录**（与 `AirPlayInfoPlist.kt` 已产出的 TXT 字段保持同源，避免两份真相）：
   - `_airplay._tcp.local` PTR → `<host>._airplay._tcp.local`
   - SRV：port = **实际绑定的 AirPlay 端口**（`service.boundPort()` ✓），target = `<host>.local`
   - TXT：`deviceid` / `features` / `srcvers` / `flags` / `model` / `pi` / `pk` / `gid` / `psi` …（按 EasyPlay 与本项目既有 `/info` 取值 ✓）
   - `AAAA`：`fe80::2`（link-local，scope=netif ✓）
3. **行为**：启动后主动 announce（两次，间隔约 1 s）；对收到的 **QUERY**（含 `QM`/`QU`）按标准应答；对 `PTR`/`SRV`/`TXT`/`AAAA` 查询逐项回复；忽略无关查询 ✓；TTL 按 4500 s 惯例 ✓。
4. **验证判据（车机日志）**：出现入向连接（AirPlay 监听 accept ✓）与 `GET /info`、`SETUP`、`RECORD` ✓；随后视频解码 ⇒ **上屏** ✓；同时截屏取证 ✓。
5. **工程约束**：实现放在 `shared` 的 transport 层（纯 Kotlin + 现有 JNI ✓，不加原生代码 ✓），带纯函数单测（DNS-SD 报文编码/解析 ✓），并在 `debug.diplay.mdns=off` 下可关闭以便 A/B ✓（ADR-8）。

## 4. 备选/并行线索

若补齐广播后仍无入向连接，则回到 iAP2 侧核对：把我们的 `startSession` **实参**与 EasyPlay 的 `Iap2CarPlayMessages.startSession(airPlayPort, publicKey, sourceVersion, wiredIpv6Addresses, wiredReserved, wireless, clusterAsset)` 调用点逐个对照（尤其 `wiredReserved` 与 `clusterAsset` 的有无 ✓），并观察 iPhone 是否以 `0x4300 CarPlayAvailability` 之类消息表达拒绝 ✓。

## 5. 状态

- 连接层：**完成** ✓（EasyPlay 式 lwIP 用户态方案，实车跑通 ✓）；
- 上屏：**待此项**（mDNS/DNS-SD over lwIP）✓；
- 仓库与 `1fba7a21` 一致、`:shared 1043 / 0`、`:common 590 / 0` ✓。

---

## 6. 落地进度与剩余（2026-10-13 更新）

已提交，且每部分都有单测覆盖（无需车机即可验证）：

- `d410bac4` `shared/src/main/java/com/shilapi/xcertplay/transport/mdns/DnsSdRecords.kt` —— 记录编码（PTR/SRV/TXT/AAAA）与 mDNS 报文布局，7 条单测；不做名字压缩（链路不缺字节，正确性优先）；测试顺带抓出并修好了 DNS 根名的边界情况。
- `56dce716` `…/mdns/DnsSdMessages.kt` —— 广播与应答决策：announce 四条记录、解析入向查询的问题段（含 unicast 位）、PTR 询问回全部四条、其它询问附带 PTR、与本服务无关则不应答、畸形报文一律忽略，6 条单测。

剩余部分（需要原生改动 + 上车验证）：

1. **组播加入能力**：lwIP 的 IPv6 组加入需要 `struct ipv6_mreq`（组地址 + 接口索引），而现有 JNI 只有 `nativeSetSockOptInt`（整型选项）⇒ 需新增一个原生函数（如 `nativeJoinGroupV6(fd, addressBytes, interfaceIndex)`）；同时在 `shared/src/main/cpp/bridge/lwipopts.h` 打开组播支持（lwIP 2.2.0 需要 `LWIP_IPV6_MLD=1` 以及 `MEMP_NUM_MLD6_GROUP` 等），并按 lwIP 常量传参。
2. **UDP socket**：`AF_INET6` + `SOCK_DGRAM`，绑定 `[::]:5353`，以本 netif 的接口索引加入 `ff02::fb`，`SO_REUSEADDR` 用 lwIP 的 `0x0004`（注意：平台的 `2` 会被 lwIP 判为 ENOPROTOOPT，见提交 `1fba7a21` 的常量对照）。
3. **时序与开关**：在 `CarPlayController.attachUserspace` 里 AirPlay 监听挂接成功之后启动；先 announce 两遍（间隔约 1 秒），随后对入向查询按 `DnsSdMessages.answers` 应答；提供 `debug.diplay.mdns=off` 以便 A/B（ADR-8）。
4. **验收判据**：车机日志出现入站 AirPlay 连接（accept 或 `GET /info`、`SETUP`、`RECORD`）⇒ 视频解码 ⇒ **上屏**（截屏取证）。
---

## 7. 已逐步证伪的假设（2026-10-13，均附实车或代码证据）

| 假设 | 结论与证据 |
|---|---|
| 手机需要解锁/点信任 | ✗ 证伪：`IAP2 READY [wired] ready=true`、`identification accepted`、`authentication accepted` ✓ |
| 0x4301 会话启动消息缺字段 | ✗ 证伪：`Iap2CarPlayMessages.startSession(...)` 与 EasyPlay 字段一一对应，且 `CarPlayController.kt:2000` 传了 `ipv6Addresses = listOf(config.linkLocal)`（= fe80::2）与端口 ✓ |
| iAP2 顺序反了（应先 0x4300 再 0x4301） | ✗ 证伪：实测 `14:23:39.453 rx=0x4300 carplay-availability` → `14:23:39.468 tx=0x4301 carplay-start-session`，与 EasyPlay `case CARPLAY_AVAILABILITY → send(carPlayStartSession)` 一致 ✓；且 `wiredAvailable=true usbTransport=000081100016256C3A11401E` ✓ |
| iOS 需要 mDNS 才能发现 | ✓ 成立但**不充分**：通告已在车机发出（`mdns: announcement 1/2 sent (285 bytes)`，提交 `de7d8f9c`）后仍无入向连接 ✗ |
| 通告端口 ≠ 监听实际端口 | ✗ 证伪：`CarPlayVpnService.kt:215` 绑定后写回 `server.localPort`（`:185` KDoc 亦如此说明），且车机日志无 `AirPlay port … is in use` 警告 ⇒ 实际绑定即 7000 ✓ |
| IPv6 邻居发现配置错、地址非 PREFERRED | ✗ 证伪：`cs11_netif.c:119-120` `netif_ip6_addr_set` + `IP6_ADDR_PREFERRED`、`netif_set_up` 在其后；`lwipopts.h` `LWIP_IPV6=1`、`LWIP_IPV6_ND=1`、`LWIP_ICMP6=1`；且我们的 netif 是全量注入 USB 帧 ⇒ 无 MAC 组播过滤问题 ✓ |

## 8. 唯一剩余候选与执行清单

**候选：iPhone 要求"查询—应答"这一轮 mDNS 交互才认定服务可用**（我们只发了通告，未加入组播、听不到查询）。

1. 原生新增 `nativeJoinGroupV6(fd, addressBytes, interfaceIndex)`（`lwip_setsockopt(IPPROTO_IPV6, IPV6_JOIN_GROUP, struct ipv6_mreq)`）；
2. `shared/src/main/cpp/bridge/lwipopts.h`：`LWIP_IPV6_MLD` 由 **0 改 1**（已核实当前为 0），并设 `MEMP_NUM_MLD6_GROUP`；
3. 发送通告前 `nativeBind` 到 `[::]:5353` 并入组 `ff02::fb`；随后用已提交且已单测的 `DnsSdMessages.answers()` 应答查询（编码层 `DnsSdRecords` 7 单测、决策层 `DnsSdMessages` 6 单测、发送层 `MdnsAnnouncer` 3 单测）；
4. 验收：车机日志出现入站连接（accept 或 `GET /info`、`SETUP`、`RECORD`）⇒ 视频解码 ⇒ **上屏**（截屏取证）。

**若第 4 步仍无入站连接**，下一个要查的是 iPhone 的连接尝试是否到达 lwIP（在 RX 注入处按 IPv6 协议号分类计数：TCP SYN / ICMPv6 NS 各多少），据此区分"手机没连"与"连了但我们没接住"。
---

## 9. 逐文件触点（照抄即可，避免踩 JNI 面与测试假实现两个坑）

前置已完成：`50b1e132` 已把 `LWIP_IPV6_MLD` 置 1 并设 `MEMP_NUM_MLD6_GROUP=4`。

**① 原生实现**（`shared/src/main/jni/legacy_usb.c` 末尾追加；该库已链接 `log`，无需改 CMake）：

```c
// Joins an IPv6 multicast group on the lwIP socket the announcement/answering path owns. lwIP handles
// IPV6_JOIN_GROUP only when LWIP_IPV6_MLD is on (enabled in 50b1e132); the interface index selects our
// netif, because a link-local group is meaningless without one.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeJoinGroupV6(
        JNIEnv *env, jobject self, jint fd, jbyteArray group, jint interface_index) {
    (void) self;
    if (fd < 0 || group == NULL || (*env)->GetArrayLength(env, group) != 16) return -EINVAL;
    struct ipv6_mreq request;
    memset(&request, 0, sizeof(request));
    (*env)->GetByteArrayRegion(env, group, 0, 16, (jbyte *) &request.ipv6mr_multiaddr);
    request.ipv6mr_interface = (unsigned int) interface_index;
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_JOIN_GROUP, &request, sizeof(request)) == 0) return 0;
    return -errno;
}
```
（注意：这条走的是 **lwIP 的** `setsockopt`，所以要用 lwIP 的 `IPV6_JOIN_GROUP`/`ipv6_mreq`（`lwip/sockets.h`），而不是 Linux 头里的同名常量 —— 与 `1fba7a21` 那次常量教训同源。）

**② JNI 面三处必须同步改**（否则 `tools/check_jni_symbols.ps1` 或编译会红）：
1. `UserSpaceNetworkNative.kt` 的 **interface** `UserSpaceNetworkJni`：加一行
   `fun nativeJoinGroupV6(fd: Int, group: ByteArray, interfaceIndex: Int): Int`
2. 同文件 **object** `UserSpaceNetworkNative`：加一行
   `external override fun nativeJoinGroupV6(fd: Int, group: ByteArray, interfaceIndex: Int): Int`
3. **测试假实现**：`shared/src/test/.../UserSpaceSessionNetworkTest.kt` 里的 fake JNI（实现同一 interface）**必须补上 override**，否则整个测试源集编译失败 —— 这是最容易漏的一处。

**③ 接线**（`MdnsAnnouncer` 或其调用点）：发通告前 `nativeBind(fd, 全零 16 字节, 5353)` 并 `nativeJoinGroupV6(fd, ff02::fb 的 16 字节, netif 索引)`；随后循环 `nativeRecvFrom` 收查询，用已单测的 `DnsSdMessages.questions()` 解析、`answers()` 生成应答、`nativeSendTo` 回发（应答目标通常为发送方地址；多点应答按 mDNS 惯例也可发回 `ff02::fb:5353`）。

**④ 验收**（车机）：日志出现入站 AirPlay 连接（accept 或 `GET /info`、`SETUP`、`RECORD`）⇒ 视频解码 ⇒ **上屏**（截屏取证）。若仍无入站连接，则在 RX 注入处按 IPv6 下一头部逐类计数（TCP SYN、ICMPv6 NS、UDP），据此区分"手机根本没连"与"连了但被我们丢掉"。