package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The overlay's arithmetic, ported from EasyPlay 10-80's `LegacyLiveVideoMeter` (docs/_digest/94).
 *
 * The meter has no Android dependency, so the rates are asserted on the host instead of by watching the
 * corner of a head unit.
 */
class LiveVideoMeterTest {
    @Test
    fun theFirstSampleOnlyEstablishesABaseline() {
        val meter = LiveVideoMeter()
        assertNull(meter.update(LiveVideoCounters(received = 100, rendered = 90), 1_000_000_000L))
    }

    @Test
    fun ratesAreDeltasOverTheElapsedSecond() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 100, rendered = 90), 1_000_000_000L)
        val reading = meter.update(LiveVideoCounters(received = 131, rendered = 120), 2_000_000_000L)
        assertEquals(30, reading?.shownFps)
        assertEquals(31, reading?.receivedFps)
    }

    @Test
    fun aLongerWindowIsNormalisedToPerSecond() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 0, rendered = 0), 0L)
        val reading = meter.update(LiveVideoCounters(received = 120, rendered = 60), 2_000_000_000L)
        assertEquals(30, reading?.shownFps)
        assertEquals(60, reading?.receivedFps)
    }

    @Test
    fun countersGoingBackwardsMeanTheSessionRestarted() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 500, rendered = 400), 1_000_000_000L)
        assertNull(meter.update(LiveVideoCounters(received = 10, rendered = 5), 2_000_000_000L))
    }

    @Test
    fun resetDropsTheBaseline() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 100, rendered = 90), 1_000_000_000L)
        meter.reset()
        assertNull(meter.update(LiveVideoCounters(received = 130, rendered = 120), 2_000_000_000L))
    }

    @Test
    fun aNullSampleDoesNotProduceAReading() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 0, rendered = 0), 0L)
        assertNull(meter.update(null, 1_000_000_000L))
    }
}
