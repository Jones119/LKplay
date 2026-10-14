# 37 · EasyPlay 对标分析（2026-10-09）：还有什么值得借鉴

> 动机：EasyPlay 是**同一台 CS11 上能跑通**的 DiPlay 分支（`_analysis/easyplay/`，5257 个源文件、363 个
> `com.shilapi.xcertplay` 类 ✓）。上一轮它就是"黑屏问题"的解题线索（doc 36）✓。本轮做**全量对标**：
> 24 个高价值类并行体检（每类读源码 + 对比我们树 ✓）+ 原生库/资源配置/manifest 清点 ✓。

## 0. 结论速览

| 类别 | 结论 |
|---|---|
| **值得借鉴（大件，2 项）** | ① **接车机媒体中心**（`LegacyEcarxMediaCenter`）② **导航信息推车机仪表/转向卡**（`LegacyLynkNavigationBridge`）——**两者的服务在真机上都已确认存在** ✓ |
| **值得借鉴（小件，约 8 项）** | 媒体键去重 ✓、`abortBroadcast` ✓、音频输出格式变化重建 track ✓、编解码器枚举回退 ✓、能力自检 ✓、触摸按 pointerId 分槽 ✓、车机蓝牙地址校验 ✓、跨路径按键回声抑制 ✓（多为 1~4 小时 ✓） |
| **我们已领先（无需移植）** | 视频解码器 ✓（其 `LegacyVideoDecoder` 被判定为**我们实现的子集** ✓）、音频焦点 ✓、输入泵 ✓、蓝牙交接（我们走厂商 adaptapi ✓ 比它的一发式代理更可靠 ✓） |
| **原生库** | `libdiplay_lwip.so`（lwIP ✓，仅在有线/NCM 场景才有用 ✗，我们已明确不做有线 ✗）、`libdiplay_crypto.so`（Monocypher ✓）、`libdiplay_opus.so`（**我们已在用** ✓） |

## 1. 真机前置验证（本轮实测，替代文档里的"待确认"）

```
pm list packages           → package:ecarx.xsf.mediacenter
dumpsys activity services  → ServiceRecord{… ecarx.xsf.mediacenter/.MediaCenterService}
                             intent={act=ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE pkg=ecarx.xsf.mediacenter}
                             baseDir=/system/app/XSFMediaCenter.apk
busybox ps                 → 1109 … {dim_interaction} com.ecarx.dim_interaction
dumpsys activity services  → ServiceRecord{… com.ecarx.dim_interaction/.server.DiServer}
                             baseDir=/system/app/XCDimInteractionService.apk
ls /system/framework       → ecarx.adapterapi.jar(+odex)、ecarx.cmaplatform.api.jar(+odex)
adapterapi.jar 符号        → DimInteraction / DimInteractionImpl / INaviInteraction / INavigationDI
```

⚠️ **重要修正**：媒体中心的绑定 action 实测是 **`ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE`**，
与 EasyPlay 源码里的常量不同 ✗（照抄会 bind 失败 ✓）。导航建议优先用 **`DimInteraction`/`INaviInteraction`**
（我们已能反射 `ecarx.adapterapi.jar` ✓），EasyPlay 的 `DiServer` binder 方案作为回退 ✓。

## 2. 值得借鉴（按价值排序）

### ★★★ ① 接入车机媒体中心（`LegacyEcarxMediaCenter`，1~2 天）
把 DiPlay 注册为车机"音乐源"（`requestPlay` + `updateCurrentSourceType(6=SOURCE_ONLINE)` ✓），把 CarPlay 的
曲目/播放状态推进车机原生媒体 UI ✓。**对我们的真正价值**：让车机把**媒体键与音源仲裁交给我们** ✓，
而不是现在的"只有 4.4 的 MEDIA_BUTTON 广播"这一条退路 ✓。可借鉴的设计：**未显式 claim 前不发布** ✓、
**两次 claim 至少间隔 2 秒** ✓、**发布被拒即视为被剥夺直到重新 claim** ✓、`focusLost → 重新 claim` ✓。
前置：**已确认存在** ✓（服务与 apk 都在 ✓）。

### ★★★ ② 导航信息推车机仪表（`LegacyLynkNavigationBridge`，~1 天）
把 CarPlay 的转向指引（转向类型/箭头 id/路名/距离/剩余里程时间 ✓）推给车机 **DIM** 服务 ✓，并带**所有权/防抖**
状态机（连接且在位才推 ✓、只清自己写过的 ✓、10 秒连接超时 ✓、`sdk_missing` 分级失败 ✓）。
**我们的差距**：目前已解析出指引（`RouteGuidanceState` ✓ / `CarPlayGlance` ✓）但**只画在自己的 CarPlay 画面里** ✗，
从未写入车机仪表 ✓。**先做**：用 `DimInteraction.create(ctx).getNaviInteraction()`（已确认在 jar 里 ✓）
做一次上车探测 ✓；同时补 `routeState`（0x01 已在解析但被丢弃 ✗）与 `currentRoad/afterRoad` ✓
（否则"前往路线起点"会被画成普通直行 ✗）。

### ★★ ③（小件，1~4 小时/项，建议成批做）

| 项 | 借什么 | 为什么 |
|---|---|---|
| **媒体键去重**（`LegacyMediaKeyGate`） | 用 `(keyCode, downTime, eventTime)` 三元组认"同一次物理按压" ✓（而非时间窗启发式 ✓，不会吃掉有意双击 ✓） | 车机可能重播/回显媒体键 ✗ ⇒ 现在重复的 NEXT 会**跳两首** ✗ |
| **`abortBroadcast`**（`LegacyMediaKeyReceiver`） | 已消费的有序广播要 `abortBroadcast()` ✓（并给 MEDIA_BUTTON filter 加 priority ✓） | 否则车机自己的接收器也会响应同一次按键 ✗ |
| **音频输出格式变化**（`LegacyAudioRenderer`） | `INFO_OUTPUT_FORMAT_CHANGED` 时比对解码器真实采样率/声道与 track ✓，不一致就**重建 AudioTrack** ✓ | 我们只打日志 ✗ ⇒ 一旦不符就是错音高/错时长 ✗（**注意别抄它的 `1..11` 流夹取** ✗，会砍掉我们的流 12 ✓） |
| **编解码器枚举回退**（`LegacyCodecFactory`） | API 19 用 `MediaCodecList.getCodecCount()/getCodecInfoAt()` 枚举全部候选名 ✓，逐个 `configure+start` 试 ✓ | 我们现在只有 `createDecoderByType` 单一候选 ✗；厂商解码器已证明会坏 ✓ ⇒ 值得有"下一个候选"的兜底 ✓（**追加在最后** ✓，i.MX6Q 软解 720p 只能算边际 ✓） |
| **能力自检**（`LegacyMediaCapabilities`） | 启动时**真** configure+start 探一遍 H.264/Opus ✓，不可用就**明确报错**而非黑屏 ✓ | 我们无论如何都广播 Opus ✓、只打一行日志 ✗ |
| **触摸按 pointerId 分槽**（`LegacyCarPlayTouchHandler`） | 按 `event.getPointerId()` 分槽 ✓、抬指显式发"离开" ✓、`onStop` 释放全部触点 ✓ | 我们现在按索引映射 ✗ ⇒ 多指抬起时存活手指会**静默继承**槽位 ✗（可能触发捏合跳变 ✗） |
| **蓝牙释放前校验地址**（`LegacyVendorBluetoothHandoff`） | 只有"当前连接地址 == 目标手机"才断 ✓；并对 nforetek `ecarx_btuiservice` 提供回退 ✓ | 我们现在会断开厂商报的任意地址 ✗（可能误断另一台已配对设备 ✗） |
| **跨路径按键回声抑制**（`LegacySessionService` 的心得） | 同一个物理按压在两条路径（活动 dispatchKeyEvent / MEDIA_BUTTON / 无障碍）只执行一次 ✓（~150ms 窗口 + 注入时钟 ✓，可测 ✓） | 我们三条路径各自为政 ✗ |

### ④ 明确**不借**
- `LegacyVideoDecoder` / `VideoInputPump` / `LegacyAudioFocus` / `LegacyBluetoothAudioHandoff`：**我们已是超集** ✓
  （视频解码器被审计判定为"我们的实现覆盖并超过它" ✓，只顺带借鉴"失败后 1 秒重建冷却"这一条 ✓ 我们已有 ✓）。
- **有线相关**（`Ch341I2cTransport`/`I2cTransport`/`Iap2Wired*`/`libdiplay_lwip.so`）：我们**已明确不做有线** ✓（你早前批准删掉 CH341 适配 ✓）。
- `EasyPlayUpdateRepository`/`releases.json`（它自己的更新器 ✗）、`LegacyWheelLearningDialog` 等 UI 皮肤 ✗。

## 3. 建议执行顺序（先小后大，且都可离线完成）

1. **小件成批**（约 6~8 小时 ✓，风险低、可直接单测 ✓）：媒体键去重 + `abortBroadcast` + 触摸 pointerId + 音频输出格式重建 + 蓝牙地址校验；
2. **能力自检 + 编解码器枚举回退**（约 3~4 小时 ✓）：给黑屏类问题再加一层兜底 ✓；
3. **媒体中心接入**（1~2 天 ✓）：先写一个最小绑定探测（用实测 action `ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE` ✓）确认能拿到 `IMediaCenterSvc` ✓，再补 claim 状态机 ✓；
4. **导航推仪表**（~1 天 ✓）：先探 `DimInteraction/INaviInteraction` ✓，再补 `routeState`/`currentRoad` ✓。

> 说明：本轮的 24 项结构化结论来自并行审计（每个类一个子代理，各自读源码并与我们树对比 ✓）。
> 其中 7 项的完整结论很长，未逐字收录；需要某一条的完整论证（例如 `LegacyEcarxMediaCenter` 的回归风险面）
> 可以按需重跑或直接读它的源码 ✓。
