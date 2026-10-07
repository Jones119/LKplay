package com.shilapi.xcertplay.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stats line is the only instrument for "is the motion even", so the present-interval
 * percentiles, the refused stale frames and the queue depth have to be exactly what they claim.
 */
class VideoStatsTest {
    private val lines = mutableListOf<String>()

    /** A clock the test advances explicitly, in milliseconds so no literal can overflow an Int. */
    private class Clock {
        var value = 0L
        fun advanceMillis(millis: Long) { value += millis * 1_000_000 }
    }

    private fun statsWith(clock: Clock) =
        VideoStats(label = "", nanoTime = { clock.value }, log = { lines += it })

    private fun steadyFrames(clock: Clock, stats: VideoStats, count: Int, intervalMillis: Long) {
        repeat(count) {
            stats.onReceived(size = 1_000)
            clock.advanceMillis(intervalMillis)
            stats.onRendered()
        }
    }

    @Test fun presentPercentilesSeparateTypicalMotionFromBursts() {
        val clock = Clock()
        val stats = statsWith(clock)
        // Nine steady 33 ms intervals, then one 200 ms burst gap.
        steadyFrames(clock, stats, 10, 33)
        stats.onReceived(size = 1_000)
        clock.advanceMillis(200)
        stats.onRendered()
        clock.advanceMillis(5_000)
        val line = stats.logIfDue()
        assertNotNull(line)
        assertTrue(line!!, line.contains("shownP50=33ms"))
        assertTrue(line, line.contains("shownP95=200ms"))
    }

    @Test fun aStaticScreenIsNotCountedAsJitter() {
        val clock = Clock()
        val stats = statsWith(clock)
        steadyFrames(clock, stats, 2, 33)
        // A 3 s pause is a static screen: it must not become the p95.
        clock.advanceMillis(3_000)
        stats.onRendered()
        clock.advanceMillis(5_000)
        val line = stats.logIfDue()
        assertNotNull(line)
        assertTrue(line!!, line.contains("shownP50=33ms"))
        assertTrue(line, line.contains("shownP95=33ms"))
    }

    @Test fun refusedStaleFramesAndQueueDepthAreReported() {
        val clock = Clock()
        val stats = statsWith(clock)
        steadyFrames(clock, stats, 2, 33)
        stats.onDropped()
        stats.onDropped()
        stats.onQueueDepth(7)
        stats.onQueueDepth(3)
        clock.advanceMillis(5_000)
        val line = stats.logIfDue()
        assertNotNull(line)
        val text = line!!
        assertTrue(text, text.contains("dropped=2"))
        assertTrue(text, text.contains("queueMax=7"))
    }

    @Test fun anIdleWindowReportsNothing() {
        val clock = Clock()
        val stats = statsWith(clock)
        clock.advanceMillis(5_000)
        assertNull(stats.logIfDue())
    }

    @Test fun touchLatencyIsMeasuredFromTheAcceptedFingerState() {
        val clock = Clock()
        val stats = statsWith(clock)
        // Accepted at 100 ms, written at 300 ms (a 200 ms wait for the writer), answered at 400 ms.
        TouchLatencyProbe.onStateSent(
            enqueuedNs = 100 * 1_000_000,
            sentAtNs = 300 * 1_000_000,
            sendDurationNs = 150 * 1_000_000,
        )
        clock.advanceMillis(400)
        stats.onReceived(size = 1_000)
        clock.advanceMillis(5_000)
        val line = stats.logIfDue()
        assertNotNull(line)
        // 300 ms from the finger, not 100 ms from the write.
        assertTrue(line!!, line.contains("touch2frame avg=300ms"))
        assertTrue(line, line.contains("touchQueueMax=200ms"))
        assertTrue(line, line.contains("touchSendMax=150ms"))
    }
}
