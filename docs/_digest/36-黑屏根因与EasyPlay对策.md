# 36 · CS11 黑屏（切桌面再回 CarPlay）根因与 EasyPlay 的对策

## 现象与完整证据链（2026-10-08/09 车上实测，我可自行复现）

复现步骤：CarPlay 上屏 → 按车机 HOME → 点 DiPlay（现在会直接回 CarPlay 屏）→ **内容区纯黑**，
但车机底栏正常、前台是我们的 `CarPlayHostActivity`。

| 证据 | 结论 |
|---|---|
| `video stats rx=1.4~2.4fps shown=0.8~1.2fps kbps=230~390`，与**健康静止画面**（截图 1.36MB 时 `rx=1.6fps kbps=321`）**同量级** | **视频流是好的** ✓，不是被 iOS 掐流 |
| `dumpsys activity top` 视图树：`TextureView{… 0,0-1280,650}` **存在且尺寸正确** | 视图没丢 ✓（`uiautomator dump` 看不到它是**不可靠的** ✗，只给 6 个节点 ✗） |
| 日志最后一条视频事件：`video decoder detached from surface`，之后**再无重挂** | 已由 `a7b5a6d`（onResume 重挂）修掉 ✓ |
| **健康时**创建解码器：`decoder configured …` → `Using client-side OMX mux.`，**无报错** ✓ | 首次创建正常 ✓ |
| **重建时**：`Using client-side OMX mux.` → **`OMX_SetParameter() failed for StoreMetaDataInBuffers`** ✗ | 重建后厂商解码器**进不了"渲染到 Surface"模式** ✗，退化为**客户端缓冲**（帧交回我们、不画到屏幕）⇒ **黑屏** ✓✓ |

**结论**：问题不在 Surface 挂没挂、也不在关键帧，而在**厂商 H.264 解码器在"Surface 销毁后重建"时无法进入 Surface 输出模式** ✗。
我们目前是"重建一次，失败就认了" ✗；重建失败后**输出缓冲仍在被我们统计并释放**（所以 `shown>0` ✓），但**没有任何东西画到屏幕上** ✓✓。

## EasyPlay 的对策（同一台车机上没有该问题）

`_analysis/easyplay/.../LegacyVideoDecoder.java`：

```java
private long nextConfigure;                                  // 重建冷却门
private final void configureCodec() {
    ...
    if (surface != null && System.nanoTime() >= this.nextConfigure) {   // 只有冷却期过了才重建
        ...
    }
}
...
} catch (Exception error) {
    this.nextConfigure = System.nanoTime() + 1_000_000_000;  // 失败后冷却 1 秒
    reset("Video decoder failed: " + error.getClass().getSimpleName());
}
```

⇒ **EasyPlay 的做法是"带 1 秒冷却地不断重建，直到解码器真正出帧"** ✗→✓（它把这个厂商怪癖当成常态处理 ✓）。
它还带 `OwnedVideoFrame`/`VideoFramePool`（自行持有帧对象 ✓），但**渲染目标仍是 Surface**（`volatile Surface output`、`isRenderingTo(surface)` ✓）——
即：**不是**改成软件渲染 ✗，而是**靠重试把解码器拉回 Surface 模式** ✓✓。

## 要落的补丁（`shared/.../media/AndroidMediaSink.kt`，视频渲染器内）

```kotlin
// 字段
private var outputFramesSinceConfigure = 0
private var lastConfigureAtNs = 0L
private var lastRebuildAtNs = 0L

// configureDecoder(...) 成功之后
outputFramesSinceConfigure = 0
lastConfigureAtNs = System.nanoTime()

// drainOutput(...) 里每次"释放一帧输出"处（注意：仅计真正 release 的那次）
outputFramesSinceConfigure++

// run() 的每轮循环里（与 referenceChain.needsKeyFrame 那行同一处）
// CS11: 重建后的厂商解码器可能停在客户端缓冲模式，什么也画不出来（见本文件）。
// EasyPlay 以 1 秒冷却反复重建直到出帧，我们照做；只有"解码器一帧都没出"才触发。
val now = System.nanoTime()
if (outputFramesSinceConfigure == 0 && decoder != null && lastConfig != null && outputSurface != null &&
    now - lastConfigureAtNs > 700_000_000L && now - lastRebuildAtNs > 1_000_000_000L) {
    lastRebuildAtNs = now
    Log.w(TAG, "video decoder produced no output since it was configured; rebuilding it")
    releaseDecoder()
    lastConfig?.let(::configureDecoder)
    requestKeyFrameIfDue()
}
```

**为什么安全**：判据是"**解码器一帧输出都没有**"，而非帧率 ✓（静止画面也有输出帧 ✓，不会误触发 ✓）。
**风险**：若解码器**长时间无输入**（无流）也可能满足条件 ⇒ 再加一条 `queue`/`framesSubmitted` 的守卫 ✓（本文件里的 `submit`/`feed` 计数即可 ✓）。

**验收（我可自行复现）**：CarPlay 上屏 → HOME → 回 DiPlay：画面应 **1~2 秒内恢复**；
日志应出现 `video decoder produced no output since it was configured; rebuilding it` ✓ 随后**只出现一次**
（因为第二次重建应成功 ✓，且此后 `outputFramesSinceConfigure > 0` ✓）。

## 附：本项目为此已做的（保留，均无害）
- `4d72fc6` Surface 回归即请求关键帧；`21166d4` 用 **MAIN_UUID** 指名显示（必需 ✓，之前发的是副屏 uuid ✗）；
- `a7b5a6d` onResume 重挂当前 Surface ✓（修掉了"detach 后不重挂" ✓）；
- `1886552` 回退了我误判的**帧率**看门狗 ✗（CarPlay 是脏区域流，静止时帧率本来就低 ✗）。
