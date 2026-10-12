package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wired microphone packet must fit the NCM link's MTU.
 *
 * Regression guard for the 2026-10-13 car finding (docs/_digest/58 §10): 20 ms of 48 kHz mono LPCM is
 * 1956 bytes on the wire, so every microphone packet was fragmented and WeChat voice notes recorded
 * silence - while wireless, which negotiates Opus, worked.
 */
class MicrophoneFramePolicyTest {
    @Test
    fun fortyEightKilohertzMonoIsCappedToTwelveMilliseconds() {
        val millis = MicrophoneFramePolicy.resolve(
            preferredMillis = MicrophoneFramePolicy.DEFAULT_MILLIS,
            sampleRate = 48_000,
            channels = 1,
            overrideMillis = 0,
        )
        assertEquals(12, millis)
        assertTrue("the sealed packet must fit: ${wireBytes(millis, 48_000, 1)}", wireBytes(millis, 48_000, 1) <= MicrophoneFramePolicy.MAX_WIRE_BYTES)
    }

    @Test
    fun fortyEightKilohertzStereoIsCappedToSixMilliseconds() {
        val millis = MicrophoneFramePolicy.resolve(20, 48_000, 2, 0)
        assertEquals(6, millis)
        assertTrue(wireBytes(millis, 48_000, 2) <= MicrophoneFramePolicy.MAX_WIRE_BYTES)
    }

    @Test
    fun aRateWhoseTwentyMillisecondFrameAlreadyFitsIsLeftAlone() {
        // 20 ms at 24 kHz mono is 996 bytes on the wire, so the phone's own preference is kept.
        assertEquals(20, MicrophoneFramePolicy.resolve(20, 24_000, 1, 0))
        assertTrue(wireBytes(20, 24_000, 1) <= MicrophoneFramePolicy.MAX_WIRE_BYTES)
    }

    @Test
    fun theDebugOverrideWinsButCannotBreakTheMtuRule() {
        assertEquals("an override inside the cap is honoured", 5, MicrophoneFramePolicy.resolve(20, 48_000, 1, 5))
        assertEquals("8 ms also fits at 48 kHz mono", 8, MicrophoneFramePolicy.resolve(20, 48_000, 1, 8))
        assertEquals("an override past the cap is capped", 12, MicrophoneFramePolicy.resolve(20, 48_000, 1, 20))
        assertEquals("an oversized override is still capped", 12, MicrophoneFramePolicy.resolve(20, 48_000, 1, 60))
        assertEquals("an out-of-range override is ignored", 12, MicrophoneFramePolicy.resolve(20, 48_000, 1, 0))
        assertEquals("and a negative one too", 12, MicrophoneFramePolicy.resolve(20, 48_000, 1, -3))
    }

    @Test
    fun everySupportedPcmCombinationStaysInsideTheMtu() {
        for (rate in listOf(8_000, 16_000, 24_000, 44_100, 48_000)) {
            for (channels in 1..2) {
                val millis = MicrophoneFramePolicy.resolve(20, rate, channels, 0)
                assertTrue(
                    "rate=$rate channels=$channels millis=$millis bytes=${wireBytes(millis, rate, channels)}",
                    wireBytes(millis, rate, channels) <= MicrophoneFramePolicy.MAX_WIRE_BYTES,
                )
                assertTrue("a frame must carry at least one sample", millis >= 1)
            }
        }
    }

    /** What one sealed packet would weigh, which is what fragmenting is decided by. */
    private fun wireBytes(millis: Int, sampleRate: Int, channels: Int): Int =
        sampleRate * millis / 1000 * channels * 2 + MicrophoneFramePolicy.WIRE_OVERHEAD_BYTES
}
