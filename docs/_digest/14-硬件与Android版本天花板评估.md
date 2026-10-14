# 14 · 硬件底子与 Android 版本天花板评估

> 日期：2026-10-06 ｜ 问题：「CS11 的处理器性能与硬件配置，能支持后续的 Android 版本么？」
> 结论：**硬件上能——NXP 官方 BSP 链一直铺到 Android 9.0 Pie（i.MX6/7 系列均适用）；但"升级系统"对本项目既不需要（root 路线在 4.4 上直接解决），也不划算（要重写全部整车集成 + 刷写风险）。**

## 1. CS11 实测硬件底子

| 项目 | 规格 | 来源 |
|---|---|---|
| SoC | **Freescale i.MX6 Quad**（4× Cortex-A9，armv7，`ro.board.platform=imx6`） | 档案 05 |
| RAM | 2 GB | 档案 04 |
| 存储 | **eMMC 64 GB**（`/data` 37.2 G，空闲 33.3 G；`/system` 1.4 G；`/cache` 992 M） | 档案 05 |
| 屏幕 | 物理 1920×1080（`wm` 覆盖 1280×720，density 160） | 档案 05 |
| 视频 | **i.MX VPU H.264 硬解**（`OMX.Freescale.std...avc.v3.hw-based`）；720p@60 级流畅、1080p30 内 | 档案 05 |
| GPU | Vivante GC2000（i.MX6Q 标配，ES 2.0/3.0 级） | SoC 规格 |
| 系统 | Android 4.4.3 / kernel 3.10.53+（2020-07 构建） | 档案 05 / getprop |

参照系：该配置约等于 2014-15 年的中低端手机（4×A9@~1GHz + 2GB + 1080p 输出）。

## 2. 官方支持天花板：到 Android 9.0（Pie）

- NXP（Freescale）为 i.MX6/i.MX7 系列的官方 Android BSP 链：KK4.4.3 → L5.x → M6.x → N7.1.x → O8.x → **P9.0**。
  - 佐证：NXP《Android User's Guide》明载 "build **Android Pie 9.0** platform for the **i.MX 6** and i.MX 7 series devices"；NXP 社区有 i.MX6Quad SABRE-SD + Android 9 + 车辆 HAL 的完整实操路径（含 VHAL/CAN 指引）。
  - 精确版号：**P9.0.0_2.2.0-ga（2019-07，i.MX6/7 GA 版）**；此前 O8.0.0_1.0.0（2018-02）。构建目标含 `sabresd_6dq-userdebug`（i.MX6Quad 参考板）；官方烧写工具 = **UUU（USB 串行下载协议）**，配套 `u-boot-imx6q-sabresd-uuu.imx`——将来若涉足刷机，这是标准工具链（前提：能进入 i.MX6 串行下载模式）。
- 10 及以上：i.MX6 无 BSP（GPU 驱动/内核/生态均无来源），32 位 A9 已过生态线 → 不现实。

### 性能分级预期

| 目标版本 | 硬件可行性 | 体验预期 |
|---|---|---|
| 4.4.3（现状） | — | 流畅（KitKat 本就为低配设计） |
| 5.1–6 | ✅ 官方 BSP | 顺 |
| 7.1 | ✅ 官方 BSP | 尚可 |
| 8.x–9.0 | ✅ 官方 Pie BSP | 能跑，偏慢（2GB 低端机水平） |
| 10+ | ❌ 无 BSP | 不做幻想 |

### 真正的拦路虎（不是 SoC）

1. **刷写入口**：比 root 更难——U-Boot / fastboot / i.MX6 串行下载（SDP）均未验证，HAB 是否熔断未知；
2. **整车集成重写**：MCU/CAN 车辆信号、车辆设置、TBox、方控、倒车影像、功放、收音……全部是 ECARX 私有实现——丢一个就是"一台能亮的平板"。

## 3. 对本项目：不需要、不划算、不动摇

- DiPlay 已被**向下移植**到 4.4.3（本项目的全部意义）；CarPlay 所需三件套——**root（线索在手）、内核 NCM 网卡（已在）、H.264 硬解（已在）**——在 4.4 上全都能拿到。
- 媒体通道的解法（root + usb1 直配 / Wi-Fi 混合）**与 Android 版本无关**。
- 视频性能实测无忧：CarPlay 流（720p 级、上限 1080p30）落在 VPU 射程内。
- 若做远期系统级改造的正确顺序（备案，不做）：先 root → 全分区备份 → 找到刷写链 → NXP P9 BSP 打底 + 逐项移植车辆集成——以"月"计、砖车风险高。

## 4. 引用
- NXP：Android OS for i.MX 专页；《Android User's Guide》（P9.0 for i.MX6/7）
- NXP 社区：Android N7.1.1_1.0.0 发布公告；"Adding Car services in Android 9"（i.MX6Quad + Android 9 + VHAL）
- 本地：`docs/04-CS11加CarPlay-改造路线评估.md`、`docs/05-CS11实机档案.md`、`_artifacts/cs11_getprop.txt`

## 附录：UI 全面 1080p 化评估（2026-10-06）

> 问题：「仅靠 root，能否把所有 UI 画面适配到 1080p 让它更清晰？」

- **可行性：能。** 且大概率**无需 root 即可先实验**（`wm size`/`wm density` 在大多数设备上 adb shell 可执行；本机待实测）——root 的真正作用是**固化 + 桩掉厂家重设 + 性能兜底**。
- **正确姿势**：`wm size 1920x1080` + **`wm density 240`**（1.5×）——
  - 布局空间完全不变（仍是 1280dp × 720dp，所有现有界面按原样摆布）；
  - 每个像素密度 ×1.5，面板原生渲染（无放大）→ **文字/线条/矢量原地变清晰**；
  - 反例：只改 size（density 保持 160）= 界面元素物理变小、留白变多；只改 density 不改 size = 元素变大装不下。两者都要按 1.5× 配套改。
- **效果预期分层**：文字/线框/矢量图形明显变锐（最大收益）；厂家位图素材（PNG 背景/图标）取决于是否有高密度资源档——厂家按 160dpi（mdpi）设计，可能只有 mdpi 位图 → 这部分提升有限（被 1.5× 上采样）。
- **性能代价**：GPU 像素负载 ×2.25（GC2000 + KitKat）→ 流畅度预计下降；厂商降 720p 正是为此。**必须实测**（完全可逆）。
- **卡顿风险细化（2026-10-06）**：
  - 无感场景：静态显示、点按交互（局部重绘，成本几乎不变）；
  - 高风险场景：全屏滑动/列表滚动/应用切换动画/多任务卡片——每帧全屏合成成本 ×2.25，叠加 i.MX6Q 公认的内存带宽瓶颈，掉帧概率高；文字大改版（更多字形采样）进一步加负；
  - 视频场景（关键）：若视频层走硬件 overlay（IPU 直扫，KitKat 时代 Freescale HWC 的常规路径），合成代价极低 → **CarPlay 视频大概率不卡、反而更清晰**；若被迫走 GPU 合成则代价明显。命中率需实测（`dumpsys SurfaceFlinger` / gfxinfo）。
  - 量化手段：改前/改后各跑 `adb shell dumpsys gfxinfo <包名>` 对比帧时间；动画缩放降级（animation scale 0.5）可显著回收感知流畅度；
  - 缓解：root 后可探 GPU/DRAM governor 节点（sysfs）尝试兜底；
  - 预期结论：**"能用，但比现在涩一点"**；清晰 vs 顺滑的取舍以实测数据为准。
- **root 的用途**：① 定位并停用"开机设 720p"的厂家组件（wm 覆盖本身持久化于 Settings，但要防厂家开机重设）；② sysfs 探测 GPU/DRAM/IPU 时钟节点兜底流畅度（i.MX6 有频率节点，能调到什么程度需实地探测）；③ 对个别适配异常的厂家 App 打补丁。
- **回退**：`wm size reset` / `wm density reset`（遇险 adb 侧重置即可，风险低）。
- **CarPlay 关联**：视频清晰度提升需**「系统逻辑空间 1080p」+「DiPlay 请求 iPhone 下发 1080p 码流」同时满足**（720p 系统下，1080p 流会先缩到 720 再被放大，无意义）。VPU 1080p30 处于规格边缘，需实测温度/流畅度。属 **root 到手后的可选优化项**，非当前主线（主线仍是媒体通道）。

## 附：Dalvik vs ART 运行时评估（2026-10-07 问答沉淀）

**现状实锤**：CS11 `persist.sys.dalvik.vm.lib=libdvm.so` → 跑的是 **Dalvik**（4.4 默认；ART 在 4.4 仅为实验性选项，5.0 起 ART 取代 Dalvik）。

**理论上谁快：ART**（AOSP 官方事实，非玄学）
- Dalvik = 解释器 + 轨迹 JIT（运行时热点编译）；ART = AOT（安装期 dex2oat 全量编译）+ 更好的 GC；
- Google 4.4 官方数据：多数基准 1.2–2×；应用启动明显更快；GC 停顿更低；代价 = 安装变慢、存储/内存占用增大、4.4 版兼容性风险（"实验性"标签）。

**对本项目的实际收益评估**
- 受益面：Kotlin 胶水（网络循环/解析/拷贝/状态机）约 10–30%；GC 停顿降低（若残余 processMaxUs 尖峰与 GC 相关）。
- 不受益：VPU 硬解、Conscrypt 原生解密、内核 NCM、IRQ/RPS——全为 native/内核层，与 runtime 无关。
- 判断：对"丢包战"格局**不大可能有实质改变**；边际收益集中在 Java 部分。

**风险（车机特有）**
1. 4.4 ART 为实验版——本项目大量 API19 兼容手术（如 `AudioApiBridge` 依赖 Dalvik 惰性校验的既有行为），ART 验证器行为不同，兼容性需实测；
2. 车厂魔改 ROM——`libart.so`/`dex2oat` 是否完整未知；切换 = 全系统 dex2oat 重编译（A9 上 10–30min+、占存储、耗电）；
3. 可逆性依赖故障时 adb 仍可用——车机场景风险不低；超出"不刷机"纪律边界，须明确同意后才动。

**零风险验证路径（PC 侧优先）**
- AVD `cs11-19` 双跑 A/B（切 `persist.sys.dalvik.vm.lib` + 重启模拟器）：先答"兼容性"（我们的 app 在 ART 下能否正常跑），再跑微基准对比。
- 下车日检查命令：`ls -la /system/lib/libart.so /system/bin/dex2oat`、`getprop persist.sys.dalvik.vm.lib`。
