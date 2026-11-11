package com.shilapi.xcertplay.media

import android.util.Log

/**
 * 「帧年龄上限」：这一帧从网络到达到现在超过这个时间，就只解码不上屏
 * （`AndroidMediaSink.RENDER_MAX_AGE_US` 的可调版本，默认 250 ms）。
 *
 * 调大它会让更多帧上屏（`shown` 上升），代价是画面延迟变大；调到 5000 ms 等于「每帧都上屏」，
 * 用来测上屏链路本身的上限（2026-11-11 排查「输出是否被车机卡在 30fps」时加的）。
 *
 * ```
 * adb shell setprop debug.diplay.video_age_ms 5000   # 每帧都上屏
 * adb shell setprop debug.diplay.video_age_ms ''     # 回到默认 250ms
 * ```
 *
 * 每个 video sink 创建时读一次，改值后要新建会话才生效。
 */
object VideoRenderAge {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_age_ms"

    /** 与 [AndroidMediaSink.RENDER_MAX_AGE_US] 一致。 */
    const val DEFAULT_MS = 250L

    /** 上限取 5 s：再大就等于关闭年龄规则，没必要留更离谱的值。 */
    const val MAX_MS = 5_000L

    /** 纯逻辑：一个属性值到一个毫秒上限；无法解析或越界都钳到合法范围。 */
    fun sanitize(raw: String?): Long {
        val value = raw?.trim()?.toLongOrNull() ?: return DEFAULT_MS
        return value.coerceIn(0L, MAX_MS)
    }

    /** 非默认值时打一行，便于事后从日志确认当时的口径。 */
    fun currentMs(): Long = sanitize(property()).also { ms ->
        if (ms != DEFAULT_MS) Log.i(TAG, "render age limit overridden by $PROPERTY: ${ms}ms")
    }

    fun currentUs(): Long = currentMs() * 1_000L

    /** 4.4 没有公开属性 API，走反射（与其它 debug 开关同形）。 */
    private fun property(): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, PROPERTY) as? String
    }.getOrNull()
}
