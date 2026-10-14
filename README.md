# DiAuto-DiPlay 工作区（领克 CS11 · CarPlay 接收端）

**这个工作区只做一件事**：在**领克 CS11** 车机上用**自己开发的 Android 应用**（不是外挂盒子、不改固件）
实现 iPhone 的 **CarPlay**（无线 + 有线），并把导航/媒体信息投到**液晶仪表**。软件用**离线 MFi 身份做
附件认证**（软模拟），不依赖认证服务器。

| 项 | 值 |
|---|---|
| **代码仓库** | `CarPlay-CS11\`（分支 `cs11-api19`；最新提交以 `git -C CarPlay-CS11 log -1` 为准） |
| **远端** | `gitee` = <https://gitee.com/meat-and-meat-first/diplay-cs11.git>（本项目自己的仓库）；`origin` = 上游 DiPlay（经 gh-proxy 镜像） |
| **工程笔记（知识库）** | `docs\_digest\`（`00`–`55`，**不在 git 里**，是本项目的核心知识资产） |
| **单测基线** | `:shared` **940 / 0 失败**、`:common` **590 / 0 失败**（**失败集必须保持为空**） |
| **当前 APK** | `CarPlay-CS11\mobile\build\outputs\apk\debug\mobile-debug.apk`（约 10.89 MB；⚠️ 打包前先 `:mobile:clean`，见 `docs\_digest\54` §5.6/26） |

**一句话状态（2026-10-12）**：无线与有线 CarPlay 都能连、能上屏、能出声；仪表导航卡片与媒体文字卡片
已实车验收；**专辑封面仍未闭环**；有线/无线各有若干稳定性缺口待修（`docs\_digest\54` §2.4/§6.2）。

## 第一次接手：按这个顺序读

1. **`docs\_digest\54-完整交接（合并2026-10-11）.md`** —— 总交接（术语表、系统地图、ADR、踩坑、下一步）；
2. `docs\_digest\34-单元测试现状与已知失败.md` —— 测试基线与"哪些失败是怎么修掉的"；
3. `docs\_digest\28-CS11上机验证清单（点火窗口）.md` —— 每次上车照着打勾；
4. `docs\_digest\55-吉利H52仓库对比与引进清单.md` —— 最近一轮外部对比与引进（含"文档≠代码"的教训）；
5. `docs\_digest\51-有线交互卡顿归因与视频接收窗调优.md` —— 有线流畅度的细节；
6. `docs\_digest\39`/`53` —— 更早的两次完整交接（历史背景）。

## 目录地图

| 路径 | 内容 | 能否删/能否上传 |
|---|---|---|
| `CarPlay-CS11\` | **主仓库**（分支 `cs11-api19`） | 上传对象（只含源码 + 认证身份；见下） |
| `docs\_digest\` | **全部技术文档**（中文，`00`–`55`） | **不能删**；按项目约定**不进仓库** |
| `work\` | 探针脚本、实测日志、A/B 数据、`_probe\`（含可复现的判决实验） | 证据，**不要删** |
| `_analysis\`、`_analysis_byd\` | 逆向分析产物（参照实现的源码、厂商 APK/jar） | **不要外传/上传**（仅本地研究） |
| `_upstream\` | 参考实现（`livi`、`cs11-carplay-dongle`、`DiPlay-Geely-Android43` 克隆与解包） | **不要外传/上传** |
| `_artifacts\` | 构建与验收产物：`apk_assets\`（认证身份的外部副本）、`car-session-*.log`（实车日志）、发布的 APK | 证据，**不要删**；**不要上传** |
| `tools\` | 车机脚本：`cs11_root_fire.sh`（重建 root 链）、`cs11_tune.sh`、`cs11_recover.sh` 等 | 保留，暂未入仓库 |
| `_worktrees\` | 并行开发用的 git worktree | 用完可删 |
| `DiAuto\`、`DiPlay\` | 上游两个项目的原始克隆（源码分析阶段，见附录） | 历史，保留 |
| `dist\`、`HANDOFF.md` | 早期发布物与更早的交接 | 历史，保留 |

## 车机关键事实（实测）

| 项 | 值 |
|---|---|
| 型号 / 系统 | 领克 CS11（`product=cs11_19a_h`）/ Android **4.4.3 · API 19 · Dalvik** |
| SoC / 内存 | Freescale **i.MX6Q** 4×Cortex-A9（32 位）+ **VPU 硬件 H.264**；2 GB / 64 GB |
| 屏幕 | 逻辑 **1280×720 @160dpi**（面板 1920×1080）；全车**只有一块 display** |
| 有线链路 | USB2 HS（`speed=480`）→ 内核 `cdc_ncm` → 网卡 **`usb1`**（`fe80::2`），MTU 1500 |
| 音频格式 | 有线 **type 100 LPCM**（134.9 包/s、1565 kbps）；无线 **type 102 AAC-LC**（47.8 包/s、252 kbps） |
| 仪表接口 | ECARX **DIM**（反射注入；服务进程 `com.ecarx.dim_interaction`） |
| adb | `d:\Dev Environment\platform-tools\adb.exe`，序列号 `0123456789ABCDEF`（**点火通电才在线**） |
| 已装包名 | `com.shihab.diplay.hudtest`（debug 变体） |

## 常命令

```powershell
# 构建（不需要车机；认证身份已随仓库提供，无需环境变量）
cd D:\Development\DiAuto-DiPlay\CarPlay-CS11
.\gradlew.bat :mobile:clean :mobile:assembleStandaloneDebug --console=plain --offline

# 单测（不需要车机）
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --offline

# 上车（会中断用户当前的 CarPlay 会话，先问）
$adb='d:\Dev Environment\platform-tools\adb.exe'; $car='0123456789ABCDEF'
& $adb -s $car install -r -d 'D:\Development\DiAuto-DiPlay\CarPlay-CS11\mobile\build\outputs\apk\debug\mobile-debug.apk'
& $adb -s $car shell setprop debug.diplay.trace 1; & $adb -s $car logcat -c
# 有线还需要先重建 root 链（网卡引导）：tools\cs11_root_fire.sh
```

## 纪律与红线

1. **文档不进仓库**：技术文档一律写 `docs\_digest\`；仓库只放代码与必需的运行资产。
2. **不要 `git clean -fdx`**：`docs\`、`work\`、`_analysis\`、`_upstream\`、`_artifacts\` 都不在 git 里，
   删掉等于毁掉全部知识与证据。
3. **只提交自己的文件**（禁止 `git add -A`），提交前**重跑全量单测**，失败集不许变大。
4. **不要上传参考实现与解包产物**：EasyPlay/HyLink 反编译、厂商 APK/jar、`DiPlay-Geely-Android43`
   克隆与解包目录等，一律留在本地。
5. **认证身份政策**：仓库里只保留**运行必需**的 `mobile/src/main/auth-assets/offline-mfi/{identity.pk8,certificate.p7b}`
   （与上游公开发行 APK 中的同一份）；其它任何密钥/证书容器都不得入库（`.gitignore` 已拦住）。
   自用以外请替换成自己的身份。
6. **测量纪律**：车机侧任何 A/B 必须**交错进行**并用**内容强度代理指标**（`rx`、`kbps`）校验两侧相当，
   否则整轮作废（`docs\_digest\54` ADR-19）。
7. **多改一次、少装一次**：每次 `adb install` 都会打断用户正在用的会话；把一轮的多项修复合并成一次
   构建 + 一次安装，并**提醒用户熄火**。

## 与上游的关系

- 本仓库是 DiPlay 的 **fork**（GPL-3.0），**不合并上游**（`origin` 仅用于取用参考）：手工读懂机制 →
  自己实现 → 带单测 → 提交，并写清证据出处（文件:行号）。
- 推送到自己远端：`git -C CarPlay-CS11 push gitee cs11-api19`（本机 git 凭据管理器已能直接推 Gitee）。

## 附录：2026-10-06 的上游源码分析阶段（历史）

项目最初是对象为 **BYD 车机**上游项目的拉取与源码分析，产物仍保留在工作区：

- 上游仓库：<https://github.com/shihabal3amri/DiAuto> · <https://github.com/shihabal3amri/DiPlay>；
- 完整克隆：`DiAuto\`（v0.3.11，18 commits）、`DiPlay\`（0.2.13 一代，507 commits）；
- 分析报告：`docs\INDEX.md`、`docs\01-DiAuto-源码分析.md`、`docs\02-DiPlay-源码分析.md`；
- 已校验的发布工件：`_artifacts\DiAuto-v0.3.11.apk`（SHA-256 `c2ba6c26…ee3b`）、
  `_artifacts\DiPlay-0.2.12.apk`（SHA-256 `840f2d62…7cf4`）；
- 本机 `github.com:443` 直连不通，克隆/下载走镜像 `https://gh-proxy.com/https://github.com/...`。
