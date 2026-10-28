# CS11 开发调试环境（不用模拟器）

> 两台设备各司其职：**PC 编译与取证**、**车机做最终验收**。平板台架已退役（`targetSdk 19` 的 APK 在 Android 14+ 默认无法安装）。iPhone 是黑盒发送端，不可调试。
>
> 本文是**活文档**：环境变化就改这里。会话结论与历史见 `D:\Development\DiAuto-DiPlay\docs\_digest\`（当前最新 92 篇）。

---

## 0. 角色分工

| 设备 | 角色 | 能做什么 | 不能做什么 |
|---|---|---|---|
| **PC**（Windows） | 编译、单测、装机、取证 | 跑全量单测；构建 APK；`adb`；抓 logcat/tombstone；**把 tombstone 还原成源码行号** | 跑不了应用（没有 Android USB 主机 + 我们是 arm 原生库） |
| ~~平板~~（小米 `2307BRPDCC`，Android 15） | **已退役** | 历史材料：曾用于真机回归与 ASan 行号 | 因 `targetSdk 19` 无法安装，不再参与验证 |
| **车机**（领克 CS11，API 19） | **最终验收** | 唯一能证明"车上能用"的设备 | 车必须通电；无 root；`adb` 需车机侧允许 TCP 5555 |
| **iPhone** | 黑盒 CarPlay 发送端 | 插上就能测；可看它触发的现象 | 装不了任何调试工具；日志不可读 |

平板台架退役后，所有功能结论都必须来自 CS11 实机；编译、单测和 lint 只证明代码正确。
历史材料里"平板能连/车机不能"的对照实验结论仍可作为排查参考，但不能再作为回归手段。

---

## 1. PC 环境（**已就绪**，实测于 2026-10-19）

| 项 | 值 | 状态 |
|---|---|---|
| JDK | OpenJDK **21.0.12**，`JAVA_HOME=D:\Dev Environment\Java\jdk-21.0.12+8` | ✅ |
| Android SDK | `D:\Dev Environment\android-sdk`（`ANDROID_HOME` 已设，`local.properties` 已指） | ✅ |
| 平台 | `android-35`、`android-37.0` | ✅ |
| build-tools | `35.0.0`、`36.0.0` | ✅ |
| NDK | `25.2.9519653`、`26.1.10909125`、`26.3.11579264` | ✅ |
| cmake | `3.22.1`（SDK 内）+ 系统 `cmake 4.4.2` | ✅ |
| Gradle | wrapper **9.5.0**（腾讯镜像，可 `--offline`） | ✅ |
| 主机 C 工具链 | clang **22.1.8**（MSYS2 MinGW x86_64）、ninja、`-fsanitize=address` 可用 | ✅ 原生层电脑测试用 |
| 符号化 | NDK 内 `llvm-symbolizer.exe`（**无** `ndk-stack`） | ✅ 见 §6 |

### 1.1 三条固定命令

```powershell
cd 'D:\Development\DiAuto-DiPlay\CarPlay-CS11'

# 单测（必须 --rerun-tasks；判定看 BUILD SUCCESSFUL，不要信控制台摘要）
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --console=plain --offline --rerun-tasks

# 构建装机包
.\gradlew.bat :mobile:assembleStandaloneDebug --console=plain --offline

# 装机 + 启动（用脚本，见 §7）
.\tools\dev\install-and-run.ps1
```

### 1.2 本仓库自带的开发脚本

| 脚本 | 用途 |
|---|---|
| `tools\dev\install-and-run.ps1` | 选设备 → 装 APK → 停旧进程 → 启动 → 打印前台 |
| `tools\dev\capture-crash.ps1` | 一次性抓齐取证材料（logcat、tombstone、应用日志、内存统计）到 `_artifacts\crash-<时间>\` |
| `tools\dev\archive-native-symbols.ps1` | 把当前构建的**带符号 .so** 归档到 `_artifacts\symbols\<git sha>\` |

---

## 2. 平板环境（**前置条件：必须先加 arm64**）

### 2.1 ⚠️ 硬阻塞：当前 APK 只有 `armeabi-v7a`

```kotlin
// mobile/build.gradle.kts
ndk { abiFilters += "armeabi-v7a" }     // ← 车机是 32 位 ARM，够用
```

**Android 15 的 arm64 设备大多还能装 32 位应用，但"64 位-only"机型会直接 `INSTALL_FAILED_NO_MATCHING_ABIS`。**

好消息：`shared` 模块**已经同时编出两个 ABI**（`shared/build/intermediates/cxx/Debug/*/obj/arm64-v8a/liblwip_bridge.so` 587 KB 确实存在），
所以平板测试只需把过滤放开：

```kotlin
// mobile/build.gradle.kts —— 为平板测试放开 arm64（车机仍走 v7a）
ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
```

> 注意：`libdiplay_aac_fsl.so` 是运行时 `dlopen` 车机 `/system/lib` 里的厂商库；平板上没有，会走**回落路径**（已知行为，不是 bug）。

### 2.2 平板侧准备

1. **开发者选项 + USB 调试**：设置 → 关于平板 → 连点"版本号"7 次 → 开发者选项 → 打开"USB 调试"
2. **确认 USB 主机能力**：
   ```powershell
   adb shell pm list features | Select-String 'usb.host'
   ```
   必须有 `android.hardware.usb.host`。没有则平板方案不成立（退回电脑侧测试）。
3. **连接电脑**：`adb devices` 应看到设备；首次需在平板上点"允许 USB 调试"
4. **安装**：
   ```powershell
   adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
   ```
   - 报 `INSTALL_FAILED_NO_MATCHING_ABIS` ⇒ §2.1 的 arm64 没加
   - 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ⇒ 签名变了，先 `adb uninstall com.shihab.diplay.hudtest`
5. **iPhone 直插平板**：需要一个 **USB-C OTG 转 Lightning/USB-C 数据线**（普通充电线不行）
   - 平板上会弹 **"允许访问该 USB 设备"** → 必须点允许（我们的代码会请求权限）
   - 若一直不弹：`adb shell dumpsys usb` 看设备是否被系统识别
6. **抓日志**：
   ```powershell
   adb logcat -c                                     # 清缓冲，避免旧日志干扰
   adb logcat | Select-String 'xcertplay|DiPlay|FATAL|Fatal signal'
   ```
   应用自己的日志（`run-as` 只在 debug 包可用）：
   ```powershell
   adb shell run-as com.shihab.diplay.hudtest cat files/logs/diplay.log
   ```

---

## 3. iPhone 侧（黑盒，没有开发环境可搭）

**能做**：

- 插上就能当发送端；平板上首次连接要**信任此电脑/配件**
- 若要做 USB 层抓包：Windows 装 Apple 移动设备驱动 + **USBPcap + Wireshark**，可以看枚举与描述符
  （用途：验证 `LegacyNcmDescriptors` 的解析；**抓不到** CarPlay 会话，那需要完整 iAP2+MFi 握手）

**做不到**：装调试工具、读日志、模拟发送端。

**关于"模拟 iPhone 发送端"的调研结论**（doc 92 §7.4）：没有公开的官方实现；Apple 的 `CarPlay Simulator` 是**反方向**
（让 Mac 当车机）；开源 `node-CarPlay`/`UxPlay`/`RPiPlay` 都是接收端。`shaonialife/cardeck` 声称做发送端但未能核实。

**替代方案（推荐）**：车机录制真实 NCM 帧 → 电脑回放。需要给应用加 `debug.diplay.capture` 开关（**尚未实现**）。

---

## 4. 车机侧（最终验收）

```powershell
# 车机已开 persist.adb.tcp.port=5555
adb connect 192.168.x.x:5555
adb devices                        # 序列号形如 0123456789ABCDEF
adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
```

**注意**：

- 车机是 **user build**，`adb root` 不可用；`/data/tombstones` 读不了，但 `dumpsys dropbox --print SYSTEM_TOMBSTONE` 可以
- 应用日志用 `run-as`（debug 包才有权限）：`adb shell run-as com.shihab.diplay.hudtest cat files/logs/diplay.log`
- 存储命名空间不同：adb 侧用 `/sdcard/...`，`run-as` 侧用 `/data/data/...`
- **`adb reboot` 之后 USB 调试会被关掉**（用户 2026-10-28 确认）。重启后先到车机设置里重新打开
  USB 调试，再 `adb kill-server` + `adb devices`；否则会误判成"车机没起来"。
  点火窗口内**不要**为了修连接去 reboot——重启会连带关掉调试通道，代价比插拔一次手机大得多

---

## 5. 三条链路拓扑与排障顺序

```
[PC] ──USB──> [平板] ──USB-OTG──> [iPhone]         ← 日常回归（能拿行号）
[PC] ──TCP 5555──> [车机] ──USB──> [iPhone]        ← 最终验收
[PC]                            ──USB──> [iPhone]  ← 只能抓枚举（可选）
```

**排障顺序（从下往上，别跳步）**：

1. `adb devices` 能看到目标设备吗？看不到 → 换线/换口/重开 USB 调试
2. APK 装得上吗？装不上 → ABI / 签名（§2.2 第 4 步）
3. 应用能启动吗？不能 → `logcat | Select-String 'FATAL'`（API 19 上很多是 `NoSuchMethodError`，见 doc 89）
4. iPhone 能被发现吗？不能 → `dumpsys usb`；车机上还要看是不是被 `adbusbmuxd` 抢占
5. 连上了能出画面吗？不能 → 看 `STEP` 链卡在哪个节点 + `userspace NCM link` 统计
6. 画面动了但操作无反应 → 看 `touch2frame`、`written` 是否增长

---

## 6. 崩溃取证：**不用 ASan 也能拿到源码行号**

车机/平板给的 tombstone 里，`liblwip_bridge.so (... +N)` 形式的帧可以用 NDK 自带的
`llvm-symbolizer` 还原成 **文件名:行号**：

```powershell
$sym = 'D:\Dev Environment\android-sdk\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-symbolizer.exe'
$lib = 'shared\build\intermediates\cxx\Debug\122a2m6u\obj\armeabi-v7a\liblwip_bridge.so'   # 未 strip，带符号
& $sym --obj=$lib --functions=linkage 0x1f521
# → tcp_write  shared/src/main/cpp/lwip/src/core/tcp_out.c:509:23
```

### ⚠️ 唯一铁律：**必须用崩溃当次构建的 .so**

本轮就踩了这个坑：拿最新构建的库去解旧地址，`0xf889` 被解成 `tcpip_init`（而崩溃时那个地址是
`tcpip_thread_handle_msg+40`）—— **代码一改，地址全变，符号化必错**。

所以流程必须是：

1. 每次构建原生库后 → `tools\dev\archive-native-symbols.ps1`（按 git sha 归档两个 ABI 的 .so）
2. 崩溃取证时 → 先记下应用版本/git sha，再用**对应那一份** .so 符号化
3. `capture-crash.ps1` 会把当前 sha 一起写进取证目录，方便回溯

### tombstone 的正确读法

1. `adb logcat -d | Select-String 'Fatal signal'` → 拿到 **pid / tid / 线程名**
2. `adb shell 'dumpsys dropbox --print SYSTEM_TOMBSTONE' > tomb.txt`
3. 在 `tomb.txt` 里搜 **`tid: <崩溃线程 tid>`** —— 同一进程的所有线程都在里面，
   **第一块往往不是崩溃线程**（本轮两次都先撞上 `FinalizerWatchd`）
4. 崩溃线程块里的 `signal …` + `backtrace:` 才是答案

---

## 7. 日常工作流（一条命令链）

```powershell
cd 'D:\Development\DiAuto-DiPlay\CarPlay-CS11'

# 1. 改代码 → 单测
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --console=plain --offline --rerun-tasks

# 2. 构建 + 归档符号 + 装机启动
.\gradlew.bat :mobile:assembleStandaloneDebug --console=plain --offline
.\tools\dev\archive-native-symbols.ps1
.\tools\dev\install-and-run.ps1

# 3. 复现问题（人工操作设备）
# 4. 取证
.\tools\dev\capture-crash.ps1
#    → _artifacts\crash-<时间>\{logcat.txt, tombstones.txt, diplay.log, meminfo.txt, meta.txt}

# 5. 符号化关键帧（用 meta.txt 里记的 sha 找到对应 .so）
```

---

## 8. ASan（可选，只在需要**精确**内存错误时做）

比符号化更强：能直接指出**越界/重复释放/UAF**，不需要栈回溯。

需要改三处（**尚未落地**）：

1. `mobile/build.gradle.kts`：debug 变体加
   `externalNativeBuild.cmake.arguments += "-DCMAKE_C_FLAGS=-fsanitize=address -fno-omit-frame-pointer"`
2. `packaging { jniLibs.useLegacyPackaging = true }`（必须解出 .so，ASan 才能注入）
3. `src/debug/resources/lib/arm64-v8a/wrap.sh`：`LD_PRELOAD=libclang_rt.asan-aarch64-android.so`

**只在平板做**（车机 API 19 的 linker 不支持这套）。详见 NDK 官方 "Android Address Sanitizer" 文档。

---

## 9. 电脑侧原生测试（`native-tests/`，**WIP**）

不依赖任何 Android 设备，用 clang 把 vendored lwIP + 我们的 4 个 bridge 文件编成本机可执行程序，
配 ASan 灌假数据。设计与 4 个目标用例见 doc 92 §7.2。当前只有 `lwipopts.h`，`CMakeLists.txt` 与用例待写。

---

## 10. 环境自检清单（换电脑/重装后逐条过）

```powershell
java -version                                    # 21.x
echo $env:ANDROID_HOME                           # D:\Dev Environment\android-sdk
adb devices                                      # 至少能看到目标设备
cd 'D:\Development\DiAuto-DiPlay\CarPlay-CS11'
.\gradlew.bat :common:compileDebugKotlin --console=plain --offline     # 能编
Test-Path 'shared\build\intermediates\cxx\Debug'  # 原生构建目录存在（否则先 assemble 一次）
Test-Path 'D:\Dev Environment\android-sdk\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-symbolizer.exe'
clang --version                                  # 22.x（原生主机测试用）
```
