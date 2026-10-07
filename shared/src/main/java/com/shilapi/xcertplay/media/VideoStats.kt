package com.shilapi.xcertplay.media

import android.util.Log

/**
 * Five-second video counters that separate network/iPhone gaps from decoder throughput.
 *
 * `maxGap` is the arrival gap, so it shows how bursty the link is; `shownP50/P95` are the present
 * intervals, so they show how evenly the motion actually reaches the screen. On the CS11 wireless
 * link frames arrive in ~100-180 ms bursts at a 33 ms nominal interval, and `dropped` counts the
 * decoded frames the 250 ms age rule refused to present.
 */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
    // Injected so the counters can be asserted from a host JVM test: android.util.Log is not
    // available outside Android (and is not mocked for these unit tests).
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var lastRenderedNs = 0L
    private var received = 0
    private var rendered = 0
    private var dropped = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var maxQueueDepth = 0
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val presentIntervals = LongArray(PRESENT_SAMPLE_CAPACITY)
    private var presentSamples = 0
    private var presentIndex = 0

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered(nowNs: Long = nanoTime()) {
        rendered++
        val previous = lastRenderedNs
        lastRenderedNs = nowNs
        val interval = nowNs - previous
        // A static screen is not jitter: only count intervals that look like continuous motion.
        if (previous != 0L && interval < IDLE_GAP_NS) {
            presentIntervals[presentIndex] = interval
            presentIndex = (presentIndex + 1) % presentIntervals.size
            if (presentSamples < presentIntervals.size) presentSamples++
        }
    }

    /** A decoded frame that the render-age rule refused to present, so it never reached the screen. */
    @Synchronized fun onDropped() { dropped++ }

    @Synchronized fun onQueueDepth(depth: Int) { if (depth > maxQueueDepth) maxQueueDepth = depth }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val (presentP50Ns, presentP95Ns) = presentPercentiles()
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms shownP50=%dms shownP95=%dms " +
            "dropped=%d queueMax=%d kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms touchQueueMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            presentP50Ns / 1_000_000, presentP95Ns / 1_000_000, dropped, maxQueueDepth,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples,
            TouchLatencyProbe.maxSendNs / 1_000_000, TouchLatencyProbe.maxQueueNs / 1_000_000,
        )
        TouchLatencyProbe.maxSendNs = 0
        TouchLatencyProbe.maxQueueNs = 0
        log(line)
        windowStartNs = now
        received = 0; rendered = 0; dropped = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        maxQueueDepth = 0
        presentSamples = 0; presentIndex = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        return line
    }

    private fun presentPercentiles(): Pair<Long, Long> {
        if (presentSamples == 0) return 0L to 0L
        val samples = presentIntervals.copyOf(presentSamples).apply { sort() }
        return samples[(presentSamples * 50 / 100).coerceAtMost(presentSamples - 1)] to
            samples[(presentSamples * 95 / 100).coerceAtMost(presentSamples - 1)]
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
        const val PRESENT_SAMPLE_CAPACITY = 512
    }
}
