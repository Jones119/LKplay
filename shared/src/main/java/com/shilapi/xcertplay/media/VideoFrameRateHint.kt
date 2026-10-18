package com.shilapi.xcertplay.media

import android.util.Log

/**
 * 送进解码器的 `operating-rate` / `frame-rate` 提示值（`docs/_digest/79` 的 A3.3）。
 *
 * 4.4 的 vendor OMX 组件按**字符串键**读这两个调度提示，用来决定进高性能档还是省电档
 * （doc 41 §2.3）。它们只是提示，不是硬约束：拒绝这些键的组件会在 `buildFormat` 的
 * `tuned=false` 分支里退到最小 format，因此**不会因为这两个键导致解码器配置失败**。
 *
 * 默认 30：与本机实测的稳态帧率一致（`shown=30.0fps`，doc 78 §1）。CarPlay 的分辨率请求里
 * fps 是协商项，但会话建立后 sink 拿不到它，所以这里做成可热调，便于一个点火窗口内 A/B：
 *
 * ```
 * adb shell setprop debug.diplay.video_fps 60    # A/B 另一侧
 * adb shell setprop debug.diplay.video_fps ''    # 回到默认 30
 * ```
 *
 * 判据沿用 `DiPlay-VideoStats`：`shownP95` / `dropped` / `recoveries` / `queueMax`（ADR-19 交错协议）。
 */
object VideoFrameRateHint {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_fps"

    /** 本机实测稳态帧率。 */
    const val DEFAULT_FPS = 30

    /** 提示值的合理区间：低于 15 或高于 120 都是笔误，钳住而不是原样下发。 */
    const val MIN_FPS = 15
    const val MAX_FPS = 120

    /** 纯逻辑，JVM 可测：一个属性值到一个帧率。 */
    fun sanitize(raw: String?): Int {
        val value = raw?.trim()?.toIntOrNull() ?: return DEFAULT_FPS
        if (value <= 0) return DEFAULT_FPS
        return value.coerceIn(MIN_FPS, MAX_FPS)
    }

    /** 下一次 `buildFormat` 用的提示值。非默认时打一行，便于事后从日志确认当时的下发值。 */
    fun current(): Int = sanitize(systemProperty()).also { fps ->
        if (fps != DEFAULT_FPS) Log.i(TAG, "video frame-rate hint overridden by $PROPERTY: $fps")
    }

    /** `debug.diplay.video_fps`；4.4 没有公开 API，走反射（同 VideoThreadPriority）。 */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
