package com.shilapi.xcertplay.airplay

import android.os.Process
import android.util.Log

/**
 * Scheduling priority for the video receive and decode threads.
 *
 * The Geely H52 Android 4.3 port raises both of its video threads to `URGENT_DISPLAY` (-8) instead
 * of `DISPLAY` (-4) and reports better tracking while the head unit's own services compete for the
 * CPU (`_upstream/DiPlay-Geely-Android43`, P16 item 2). This fork shipped -4, so the value is a live
 * property and one ignition window can compare both without a rebuild:
 *
 * ```
 * adb shell setprop debug.diplay.video_priority display   # -4, the previous value (A/B side)
 * adb shell setprop debug.diplay.video_priority ''        # back to the default below
 * ```
 *
 * Both video threads take the same value, and the audio threads stay at `URGENT_AUDIO` (-19), so -8
 * can never take audio time from them. Judge an A/B with the fields the stats already print:
 * `shown`/`dropped`/`queueMax`/`touch2frame` (`DiPlay-VideoStats`) and
 * `underruns`/`skipped`/`skippedMs` (`DiPlay-AudioStats`), per ADR-19's interleaved protocol.
 */
object VideoThreadPriority {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_priority"

    /** The value this fork shipped until the Geely comparison: `DISPLAY` (-4). */
    val DISPLAY = Process.THREAD_PRIORITY_DISPLAY

    /** The Geely H52 value and this fork's new default: `URGENT_DISPLAY` (-8). */
    val URGENT_DISPLAY = Process.THREAD_PRIORITY_URGENT_DISPLAY

    /** Still below the audio threads' `URGENT_AUDIO` (-19), so audio keeps its edge. */
    val DEFAULT = URGENT_DISPLAY

    /** The pure part: one property value to one priority. JVM-testable without a device. */
    fun sanitize(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "display", "-4", "shipped" -> DISPLAY
        else -> DEFAULT
    }

    /** The priority for the next video thread. Logs any non-default choice once. */
    fun current(): Int = sanitize(systemProperty()).also { priority ->
        if (priority != DEFAULT) Log.i(TAG, "video thread priority overridden by $PROPERTY: $priority")
    }

    /** `debug.diplay.video_priority`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
