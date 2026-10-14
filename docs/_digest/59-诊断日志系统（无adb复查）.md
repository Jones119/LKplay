# 59 - 诊断日志系统（无 adb 环境下的复查）

日期：2026-10-13　适用：CS11（Android 4.4.3 / API19）

## 1. 起因

正常使用时数据线插在**车机自己的 USB 口（USB1）**、USB 调试关闭，此时：

- 车机上**没有可用的 `adb logcat`**；
- 出问题时现场只有"黑屏/无声音/连不上"这类现象，**没有任何可事后复查的记录**；
- 而调试时只能插 USB2，说明"调试环境能跑"并不等于"正常环境能跑"。

## 2. 改造前的状况（回答"有没有日志系统"）

**只有 logcat，没有落盘**：

| 通道 | 实现 | 关闭 USB 调试后能否复查 |
|---|---|---|
| `CarPlayController.debugLog(String)` | `Log.i(TAG, …)` + `uiListener.onDebugLog`（界面镜像） | ✗ 只进 logcat 环形缓冲，重启即丢、无 adb 读不到 |
| `debugLog(String, Throwable)` | `Log.w(…, error)` + 界面镜像 | ✗ 同上 |
| `connectionDiagnostic(...)` | 走 `debugLog`，形如 `CONNECTION_DIAGNOSTIC attempt=… phase=… stage=…` | ✗ 同上 |
| 车机 `/sdcard/DiPlay/` 目录 | 只有车机**封面图**用（`ClusterArtworkTransport`） | —（不是日志） |

结论：**当时没有任何可离线复查的日志**。本轮补上。

## 3. 现在：`DiagnosticLog`（落盘日志）

新增 `shared/…/diagnostics/DiagnosticLog.kt` + `DiagnosticLogPolicy.kt`（纯逻辑，可测试）。

**落盘位置（按顺序取第一个可写的）**

1. `/sdcard/DiPlay/logs/diplay-YYYYMMDD-HHmmss.log`　←　**首选**，与封面图同一棵树：这一路径已被实车证明"**本进程以外的读者也能读到**"（`adb shell cat /sdcard/DiPlay/…` 返回真实字节；而 `Environment.getExternalStorageDirectory()` 给的 `/storage/emulated/0` 在别的进程里读不到，doc 48/`ClusterArtworkTransport` 有实车证据）。**必须用字面 `/sdcard` 别名**。
2. `Android/data/<pkg>/files/logs/`（`getExternalFilesDir`）
3. 应用私有 `files/logs/`（最后的兜底；外部进程读不到）

**行为**

| 项 | 值 | 说明 |
|---|---|---|
| 生命周期 | 每次 App 运行一个文件 | 在 `CarPlayController.init` 启动（本应用**没有** Application 子类，放这里是唯一可靠的钩子） |
| 轮转 | 保留**最近 4 份**（含本次） | 文件名定宽，按名字排序即按时间排序 |
| 单文件上限 | 1 MB | 达到后写一行说明并停止写该文件（不刷爆存储卡） |
| 落盘时机 | 最多 1 秒一次；**错误与崩溃立即落盘** | 崩溃时链式调用原处理器，先写栈再交给系统 |
| 单行上限 | 4000 字符（超出截断并注明） | 一行超长载荷不会埋掉前后因果 |
| 抗故障 | 任何失败都被吞掉 | 车机**绝不能**因为写日志失败而影响 CarPlay 显示 |
| 内容 | 控制器已有的**结构化日志行** | 含 `CONNECTION_DIAGNOSTIC …`、`userspace NCM bridge ready …`、`userspace NCM link rxFrames=… rxBytes=…`、`wired AirPlay transport attached kernelNetdev=… userspaceLwip=…`、`ncm using hostMac=…` |

**为"判断插在哪个口"新增的一行**（原来的发现日志只有 VID/PID）：

```
wired iPhone discovered vid=0x5ac pid=0x12a8 device=/dev/bus/usb/001/002 bus=001 interfaces=6
```
`device=` 是 API19 上唯一能拿到的物理口线索（总线号/地址），事后看日志即可知道这次是 USB1 还是 USB2。

## 4. 没有 adb 时怎么把日志拿出来

日志文件**持久保存在车机上（最近 4 份）**，所以有三种取法：

1. **车机文件管理器**：打开 `/sdcard/DiPlay/logs/`，把 `.log` 拷到 U 盘（这一路径对外可读，正是为此选的）；
2. **下次开 USB 调试时补取**：文件跨会话保留 ⇒ 之后任意一次调试窗口用
   `adb -s <serial> pull /sdcard/DiPlay/logs/ D:\Development\DiAuto-DiPlay\work\car-logs\`
   就能把**正常使用期间**留下的日志整批取回；
3. 若车内装了具备存储权限的文件管理器，也可直接查看。

## 5. USB1（正常使用口）能否工作的结论

| 环节 | 是否与 USB 口有关 | 依据 |
|---|---|---|
| iPhone 发现 | **无关** | 走 `UsbManager` 按 VID/PID 枚举，哪个口都一样 |
| 用户态 NCM 桥（默认传输） | **无关** | 直接 claim 枚举到的那个 `UsbDevice` 的 NCM 接口，不依赖任何网卡名 |
| 告知手机的地址 | **无关** | `CarPlayRuntimeConfig.linkLocal` 是**常量** `"fe80::2"`，由我们自己的 netif 固定持有，不从内核网卡取 |
| 内核 `cdc_ncm` 兜底（隐藏调试） | **有关** ✗ | `Cs11NetdevBootstrap`/`serverBindAddress()` 找的是内核网卡 `usb1`；而它本身还需要 root+adb ⇒ **只用于调试**，不是正常使用路径 |

⇒ **正常使用（USB1 + USB 调试关闭）应当走的正是用户态桥这条不需要 root/adb 的路径**。这一点**尚未实车验证**（写入本文时 `adb devices` 为空、车机未连接）；验证方法见下。

## 6. 实车验证步骤（USB1 + 关闭 USB 调试）

```powershell
# 1) 装上这一版（可以用 USB2 调试口装，装完拔掉、关调试、把线插回 USB1）
adb -s 0123456789ABCDEF install -r -d mobile\build\outputs\apk\debug\mobile-debug.apk

# 2) 正常使用若干分钟（发起 CarPlay、放歌、微信语音、触摸）

# 3) 下次调试窗口里取日志（文件在正常使用期间已写好）
adb pull /sdcard/DiPlay/logs/ D:\Development\DiAuto-DiPlay\work\car-logs\
```

**期望在日志里看到**（这几行齐全 == 正常环境下用户态路径工作）：

```
wired iPhone discovered vid=0x5ac … device=/dev/bus/usb/00X/00Y bus=00X interfaces=…
userspace NCM bridge ready configuration=…; lwIP carries the wired link
userspace NCM AirPlay listener attached on lwIP address=fe80:0:0:0:0:0:0:2%…
wired AirPlay transport attached kernelNetdev=false userspaceLwip=true
userspace NCM link rxFrames=… rxBytes=… txFrames=… written=…   （每 5 秒一条，数值要增长）
```

**若不正常**，日志足以定位到阶段：`CONNECTION_DIAGNOSTIC … phase=… stage=…`（卡在哪一步）、`userspace NCM bring-up failed: …`（桥/描述符/协商失败原因）、`CS11 degraded mode: …`（回退原因）、以及崩溃时的栈。把该文件发回即可复查；AUTO 模式在用户态失败时会自动回退内核网卡，**用车不受影响**。
