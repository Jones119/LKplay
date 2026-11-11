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
        // 丢包率也按同一窗口的原始增量算：31 帧里 30 帧上屏 -> 1/31 = 3%。
        assertEquals(3, reading?.lostPercent)
    }

    @Test
    fun aLongerWindowIsNormalisedToPerSecond() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 0, rendered = 0), 0L)
        val reading = meter.update(LiveVideoCounters(received = 120, rendered = 60), 2_000_000_000L)
        assertEquals(30, reading?.shownFps)
        assertEquals(60, reading?.receivedFps)
        assertEquals(50, reading?.lostPercent)
    }

    @Test
    fun aWindowWithoutArrivalsShowsNoLoss() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 100, rendered = 90), 0L)
        // 静态画面：手机一帧都不发，分母为 0，按 0% 显示而不是除零或 100%。
        val reading = meter.update(LiveVideoCounters(received = 100, rendered = 90), 1_000_000_000L)
        assertEquals(0, reading?.shownFps)
        assertEquals(0, reading?.receivedFps)
        assertEquals(0, reading?.lostPercent)
    }

    @Test
    fun lossNeverGoesNegativeWhenARenderedFrameLagsIn() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(received = 100, rendered = 90), 0L)
        // 解码器在窗口末尾吐出上一窗口的帧：输出比接收多时按 0% 而不是负数。
        val reading = meter.update(LiveVideoCounters(received = 110, rendered = 105), 1_000_000_000L)
        assertEquals(0, reading?.lostPercent)
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
