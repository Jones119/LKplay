# 24 · EasyPlay（领克版 Android 4.4）逆向与安全评估

> 样本：`D:\迅雷下载\EasyPlay-Lynk-Android4.4-10-preview36.apk`
> 归档：`_analysis/easyplay/EasyPlay-preview36.apk` · SHA-256 `95c194c4…96a2b68` · 7,683,804 B
> 定位：**第三方修改版——只做逆向参考，不安装、不运行**（用户纪律）。反编译全量：`_analysis/easyplay/src/`

## 0. TL;DR

- **身份**：DiPlay 上游（`github.com/shihabal3amri/DiPlay`）的**领克适配分支**。命名空间原样保留（`com.shilapi.xcertplay`），包名 `com.shihab.diplay.legacy`，渠道 `lynk-api19`，`0.2.7-lynk-api19-preview36`（26 版迭代史，2026-10-01~02 密集发布）。适配 **CS11(4.4) + E03(Android 10)**，有线+无线。
- **无线机制（本次最大收获）**：4.4 上**用隐藏 API 反射自动开热点**——`getWifiApConfiguration()` 读原车热点 → `setWifiApEnabled(cfg, true)` 开 → `getWifiApState()` 轮询；配 BT 直调 `BluetoothAdapter.enable()`。**无 root、无 shell**（`CHANGE_WIFI_STATE` 即可）——这就是 CS11 无线能跑的关键部件。
- **安全结论**：未见恶意代码/提权/外联（无 exec/su、无遥测、域名仅更新源）；但**工程卫生差**（更新无哈希校验、URL 可改、允许 HTTP、debuggable、自签测试证书）→ 维持"不安装"纪律，**机制可移植**。

## 1. 物料与身份

| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.shihab.diplay.legacy` / `0.2.7-lynk-api19-preview36`（versionCode 36） |
| SDK | minSdk 19 / targetSdk 28 / compileSdk 37 |
| 签名 | `CN=DiPlay Legacy Test, O=Local Test`（自签**测试**证书；非官方发布 key） |
| 标志位 | `debuggable=true`、`usesCleartextTraffic=true`、`extractNativeLibs=true` |
| dex | classes.dex(1.2M) + classes2.dex(2.4M) + classes3.dex(10.4M) + `assets/ecarx-modern/classes.dex`(4.8K 垫片) |
| 原生库 | `libdiplay_crypto.so`（原生 ChaCha20 解密）、`libdiplay_lwip.so`、`libdiplay_opus.so` |
| MFi 资产 | `offline-mfi/identity.pk8`+`certificate.p7b` **与我们 fork 逐字节相同**（同源公开身份） |
| 组件 | Activity: LegacyActivity；Service: LegacySessionService + CarPlayVpnService；Receiver: LegacyMediaKeyReceiver + LegacyBootReceiver |

## 2. 无线（CS11）完整配方（代码实锤）

```
应用打开/开机自启
  └─ LegacyHotspotController.ensureReady()（状态机 + 重试 + 可取消）
     ① WifiState 检查 → setWifiEnabled(true)
     ② getWifiApConfiguration() 读原车热点配置（"沿用原热点配置"）
        usableConfiguration：OPEN/WPA_PSK 可用；WEP/UNSUPPORTED → newConfiguration() 兜底
     ③ 反射 setWifiApEnabled(config, true)   ← LegacyHotspotController.java:1117
     ④ 轮询 getWifiApState() 至 ENABLED（超时/重试；HotspotException.permissionDenied 单独标记）
     ⑤ 蓝牙：BluetoothAdapter.enable() 直调（PlatformBackend；BLUETOOTH_ADMIN）
     ⑥ 上游无线握手：Iap2WirelessMessages 经 0x5702/0x5703 把热点信息交给 iPhone
        → 手机加入热点 → Bonjour 发现 → AirPlay 会话（上游逻辑，我们 fork 也有）
开机链：LegacyBootReceiver(BOOT_COMPLETED, autoConnect 默认 true) → LegacySessionService(ACTION_PREPARE)
状态联动：LegacySessionService 监听 android.net.wifi.WIFI_AP_STATE_CHANGED
```

## 3. 可移植实现对照（vs 我们 CarPlay-CS11 fork）

| 特性 | 他们的做法 | 我们现状 | 移植价值 |
|---|---|---|---|
| **热点自动化** | 反射三件套 + 状态机（见 §2） | **无线未在 CS11 跑通（缺此件）** | ★★★ |
| 帧缓冲复用 | `ScreenStream` 内 `VideoFramePool(3 帧)` 复用 | 每帧分配（池化已计划） | ★★★（消 GC 抖动） |
| 视频解密 | `LegacyNativeCrypto.open/openInto`（原生 + 解密进复用缓冲） | Conscrypt 原生（-55%） | ★★（openInto 零拷贝思路） |
| 断连/画面恢复 | v33/34：窗口重建先显示连接页，**等新画面首帧就绪再全屏**；"断连与画面恢复修复" | **hot-replug 黑屏挂起中** | ★★★（直接参考） |
| 语音/麦克风 | 原生 Opus（libdiplay_opus）+ 麦克风初始化优化 | 未做 | ★★（Siri/通话） |
| 媒体键 | `LegacyMediaKeyReceiver`：ACTION_MEDIA_BUTTON + KEY_EVENT | **已实现同路**（互相印证 ✓） | — |
| 仪表导航 | 反射 `com.ecarx.dim_interaction`（IAdapterDI.getINavigationDI → turnByTurn 发仪表） | 未做 | ★（加分） |
| 媒体源注册 | 反射 `ecarx.xsf.mediacenter.IMediaCenterSvc$Stub`（新旧双代 API） | 未做 | ★ |
| 更新器 | TLS1.2 工厂 + latest.json + 浏览器下载 | 无 | — |

## 4. 安全评估（"别人改的"能不能信）

| 检查项 | 结果 |
|---|---|
| 命令执行（exec/su/ProcessBuilder） | **无**（源码+dex 双扫） |
| 外联域名 | 仅 `https://easy-obd.oss-cn-beijing.aliyuncs.com`（更新清单）+ 标准库 URL；**无遥测/统计 SDK** |
| 敏感权限 | 无 SYSTEM_ALERT_WINDOW、无 REQUEST_INSTALL_PACKAGES、无设备管理 |
| LocalAdb 客户端 | 类存在（含自生成 adb 密钥对）但**全项目无调用点**（上游 BYD 遗留；非静默通道） |
| 导出组件 | 仅 MainActivity + BootReceiver |
| ⚠️ 供应链 | 更新 URL **用户可编辑** + 允许 HTTP + **清单无哈希字段、官方自述"不进行 APK SHA-256 校验"** + `debuggable=true` + 自签"Test"证书 |

**结论**：良性业余项目、无恶意迹象；但供应链卫生不足以改变"只作参考、不安装"的纪律。

## 5. 我们的无线对齐计划（把 CS11 无线跑起来）

1. 自研 `Cs11HotspotController`：反射 `getWifiApConfiguration`/`setWifiApEnabled`/`getWifiApState` + WiFi/BT 开关 + 状态机（成功/失败/超时/权限三种语义，参照其结构）。
2. 接入我们 fork 的既有无线栈（`shared` 的 WirelessHotspotManager / Bonjour / `Iap2WirelessMessages` 0x5703 下发）。
3. 开机自恢复（我们已有 BootReceiver；补 autoConnect 开关）。
4. 测试路径：设置"车载热点"模式 → 开热点 → iPhone 蓝牙连接 → 0x5702/0x5703 → 手机入热点 → 首帧。**先用平板/模拟器回归，再上车**。

## 6. 证据索引

- 反编译：`_analysis/easyplay/src/`（5219 类；关键：`legacy/LegacyHotspotController.java`(1117 反射点)、`legacy/LegacyBluetoothController.java`(285)、`legacy/LegacyMediaKeyReceiver.java`、`legacy/LegacyBootReceiver.java`、`legacy/LegacySessionService.java`、`legacy/EasyPlayUpdateRepository.java`、`legacy/BuildConfig.java`、`airplay/VideoFramePool.java`、`adb/LocalAdb.java`+`AdbKeys.java`）
- 更新史：`_analysis/easyplay/releases.json`（v11–v36 全量注记，含无线/热点/E03/媒体键/优化的演进）
- 归档与哈希：`_analysis/easyplay/SHA256SUMS.txt`
