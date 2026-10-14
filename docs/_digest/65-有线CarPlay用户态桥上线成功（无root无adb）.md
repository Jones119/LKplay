# 65 - 有线 CarPlay 在用户态 lwIP 桥上线成功（CS11，无 root / 无 adb）

日期：2026-10-13　最终提交：`0cbbf728`

## 1. 结论

CS11 车机上，**有线 CarPlay 已经通过"用户态 NCM + 自带 lwIP 2.2.0"的桥完整上屏**，且：

| 配置 | 结果 |
|---|---|
| USB 调试 **开**，iPhone 插另一个 host 口 | ✅ 上屏（adbd 占用调试口，不干扰） |
| USB 调试 **关**，iPhone 插原调试口（此时为 host 口） | ✅ **上屏**（正常使用形态：无 adb、无 root） |

判据（车机日志 + 屏幕）：
```
Video: video stats rx=29.0fps shown=29.0fps maxGap=85ms dropped=0 kbps=1331   ← 30fps 目标、零丢帧
Receive: audio type=100 packets=679 bytes=985908
airplay rx POST /feedback → airplay tx status=200
```
屏幕：CarPlay 主页（Dock 侧栏 + iPhone 应用网格）。

## 2. 根因链（五处，每处都有实车证据）

| # | 病灶 | 关键证据 | 修复 | 提交 |
|---|---|---|---|---|
| 1 | 配置选择用 `singleOrNull`，而手机上报 6 个配置、其中 **2 个**同时含 usbMux+NCM ⇒ 恒为 null | `NCM configuration candidates=2 ids=[5,6] chosen=6 appleEthernet=3` | 收集全部候选并优先带 AppleEthernet 者（与参考 `find()` 一致） | `2398a60f` |
| 2 | 平台接口列表覆盖**全部配置**（31 项），我们按"当前配置内位置"索引 ⇒ 偏 20 位 ⇒ claim 到别的配置的接口，被内核 `cdc_ncm` 持有的接口从未 claim ⇒ `EBUSY(16)` | `platformOffset=20 controlIndex=25 dataIndex=27`（端点指纹逐项对齐） | 加平台偏移 | `21e775ca` |
| 3 | 把 netif 钉在常量 `fe80::2`，而**手机只拨它被告知的那个地址**；参考用的是 **MAC 推导的 EUI-64** | 参考 SETUP URL：`rtsp://fe80::9c92:4fff:fe09:bf8b/…`（= `9e:92:4f:09:bf:8b` 的 EUI-64） | C 侧 `netif_create_ip6_linklocal_address` + Kotlin `LinkLocalAddress` 同源推导 | `c2d3a5de` |
| 4 | 把参考里被 jadx 渲染成 TLS 常量名的**私有请求码**换成了 CDC 规范码 ⇒ 三个请求对设备无效、`inputSize` 被拒 | 参考 `CipherSuite.java`：`0xA1`/`0x84`/`0x8A`/`0x86`（GET/format/crc/inputSize）、`0x43` filter | 用真实码，协商一次全绿 | `258d511c` |
| 5 | ①缺 `NETIF_FLAG_MLD6` ⇒ IPv6 组播 NS 被 lwIP 丢弃 ⇒ 从不回 NA；②`MEMP_NUM_NETCONN` **从未设置** ⇒ 默认 **4 个 socket** ⇒ 手机 `SETUP` 时 `lwip could not create a stream socket (errno 23)` | ①TX 帧从 4→24（开始回 NA）②`airplay rx SETUP … cseq=6` 后 `openEvent failed errno 23` ⇒ `status=500` ⇒ 会话被弃 | ①补 MLD6 ②`MEMP_NUM_NETCONN=32`（+`MEMP_NUM_TCP_PCB=32`、`MEMP_NUM_UDP_PCB=24`）⇒ `SETUP` 变 200、媒体开流 | `0cbbf728` |

**另一个关键前提（非代码）**：**iOS 的"信任此电脑"**。旧配对记录虽可复用，但不会触发弹窗；**清掉记录让 iOS 弹窗并由用户确认后**，手机才开始启用 CarPlay 传输（因此"清记录 + 重新信任"是复现成功的最可靠路径）。

## 3. 方法学教训（本目标的副产品，值得长期遵守）

1. **jadx 渲染的常量不可信** ⇒ 判断参考行为要用**它的运行日志**或**它源码里的常量数值表**（第 4 条即由此定案）；
2. **"日志里没有 ≠ 线上没有"** —— 本次因此误判过三次（识别握手、iAP 隧道、NA）；
3. **C 侧诊断在本构建里被编译掉**（`LWIP_PLATFORM_DIAG` 不输出）⇒ 探针必须放在 **Kotlin 侧**；
4. **同机对照参考应用**是最强工具：`com.shihab.diplay.legacy`（APK 在 `_artifacts/apk-reference/EasyPlay-Lynk-Android4.4-10-74.apk`）可随时重装，它成功时的 `onProgress` 日志即"标准答案"；
5. **`debug.diplay.trace=1`** 打开后，iAP2/RTSP 的完整报文细节才会落日志（排查必备）。

## 4. 遗留（下一步工作）

1. **稳定性**：有时需要**重新配对**才会让手机开流（已保存记录的会话偶尔不被接受）⇒ 方向：在 `SETUP` 被拒/传输未启动时**主动重配对并重试**，并查明"已保存记录"为何有时不被接受；
2. **自动连接默认值**：清数据后默认不自动起链（需手动点【通过 USB 连接】）⇒ 正常使用形态下应考虑默认开启（或记住用户选择）；
3. **诊断代码清理**：`ipv4Rx` 计数与 RX/TX 帧采样可保留（长期诊断）或收敛为开关；
4. 之前挂起的 UI 收尾（三指浮层等）仍在队列中（见 docs 61/63 与相关提交）。
