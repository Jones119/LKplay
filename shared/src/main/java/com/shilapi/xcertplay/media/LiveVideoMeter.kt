package com.shilapi.xcertplay.media

import kotlin.math.roundToInt

/** Cumulative video counters, sampled by the on-screen FPS overlay. */
data class LiveVideoCounters(val received: Long, val rendered: Long)

/** One sampling window's rates: [shownFps] rendered, [receivedFps] arrived from the phone. */
data class LiveVideoReading(val shownFps: Int, val receivedFps: Int)

/**
 * Turns two cumulative counters into per-second rates for the "显示画面帧率" overlay.
 *
 * Ported from EasyPlay 10-80's `LegacyLiveVideoMeter` (docs/_digest/94): the overlay samples once a
 * second, and the five-second [VideoStats] window cannot be reused because it resets its counters.
 * A counter that goes backwards means the session restarted, so that sample returns null and the
 * overlay keeps its last text until the next window.
 */
class LiveVideoMeter {
    private var last: LiveVideoCounters? = null
    private var lastNanos = 0L

    fun update(counters: LiveVideoCounters?, nowNanos: Long): LiveVideoReading? {
        val previous = last
        val elapsedNs = nowNanos - lastNanos
        last = counters
        lastNanos = nowNanos
        if (counters == null || previous == null || elapsedNs <= 0L) return null
        if (counters.received < previous.received || counters.rendered < previous.rendered) return null
        val seconds = elapsedNs / 1e9
        if (seconds <= 0.0) return null
        return LiveVideoReading(
            shownFps = ((counters.rendered - previous.rendered) / seconds).roundToInt().coerceAtLeast(0),
            receivedFps = ((counters.received - previous.received) / seconds).roundToInt().coerceAtLeast(0),
        )
    }

    fun reset() {
        last = null
        lastNanos = 0L
    }
}
