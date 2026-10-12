package com.shilapi.xcertplay.media

import android.util.Log

/** How much decoded-video backlog this sink tolerates before it drops frames and resyncs. */
enum class VideoBacklogMode {
    /** Shipped: 60 frames / 8 MiB, 1 s stall recovery. */
    AUTO,

    /** The legacy EasyPlay build's tolerance: 20 frames / 4 MiB, 250 ms. */
    LEGACY,
}

/**
 * `debug.diplay.video_backlog` - this fork's decode queue tolerates 60 frames / 8 MiB and a 1 s stall
 * (`VideoDecodeQueue`, [AndroidMediaSink]), the legacy EasyPlay build on this car 20 frames / 4 MiB
 * and 250 ms (`LegacyVideoDecoder.java:214,324`), and the driver reports that build as smoother in
 * wired CarPlay (docs/_digest/57). This knob exists to test whether the backlog tolerance is part of
 * that difference instead of guessing.
 *
 * Our own history argues the other way, which is why this is an experiment and not a default: the
 * shipped numbers were widened on purpose, because a tighter recovery point fired about every 1.5 s
 * at 50 fps on this car and visibly stuttered (see the comment in [AndroidMediaSink]'s frame loop).
 *
 * ```
 * adb shell setprop debug.diplay.video_backlog legacy   # 20 frames / 4 MiB / 250 ms
 * adb shell setprop debug.diplay.video_backlog ''       # shipped: 60 frames / 8 MiB / 1 s
 * ```
 *
 * Read once per video sink, so the value takes effect on the next session.
 */
object VideoBacklogPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_backlog"

    const val AUTO_MAX_FRAMES = 60
    const val AUTO_MAX_BYTES = 8 * 1024 * 1024
    const val AUTO_STALL_RECOVERY_NS = 1_000_000_000L

    const val LEGACY_MAX_FRAMES = 20
    const val LEGACY_MAX_BYTES = 4 * 1024 * 1024
    const val LEGACY_STALL_RECOVERY_NS = 250_000_000L

    /** The pure part: one property value to one mode. JVM-testable without a device. */
    fun parse(raw: String?): VideoBacklogMode = when (raw?.trim()?.lowercase()) {
        "legacy", "tight", "easyplay", "20" -> VideoBacklogMode.LEGACY
        else -> VideoBacklogMode.AUTO
    }

    fun current(): VideoBacklogMode = parse(systemProperty()).also { mode ->
        if (mode != VideoBacklogMode.AUTO) {
            Log.i(TAG, "video backlog override by $PROPERTY: $mode")
        }
    }

    fun maxFrames(mode: VideoBacklogMode): Int =
        if (mode == VideoBacklogMode.LEGACY) LEGACY_MAX_FRAMES else AUTO_MAX_FRAMES

    fun maxBytes(mode: VideoBacklogMode): Int =
        if (mode == VideoBacklogMode.LEGACY) LEGACY_MAX_BYTES else AUTO_MAX_BYTES

    fun stallRecoveryNs(mode: VideoBacklogMode): Long =
        if (mode == VideoBacklogMode.LEGACY) LEGACY_STALL_RECOVERY_NS else AUTO_STALL_RECOVERY_NS

    /** `debug.diplay.video_backlog`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
