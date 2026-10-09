package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Packet-size arithmetic behind the audio queue's drop accounting. */
class AudioJitterTargetTest {
    @Test
    fun packetSizeIsLearnedFromConsecutiveTimestampsAndRejectsDiscontinuities() {
        assertEquals(0, AudioJitterTarget.packetFrames(0, 1024, hasPrevious = false, sampleRate = 48_000))
        assertEquals(1024, AudioJitterTarget.packetFrames(0, 1024, hasPrevious = true, sampleRate = 48_000))
        assertEquals(2048, AudioJitterTarget.packetFrames(0, 2048, hasPrevious = true, sampleRate = 48_000))
        // A wrapped 32-bit timestamp still measures the same distance.
        assertEquals(
            1024,
            AudioJitterTarget.packetFrames(0xffff_fc00.toInt(), 0, hasPrevious = true, sampleRate = 48_000),
        )
        // A backwards jump or a >1 s gap is not a packet size.
        assertEquals(0, AudioJitterTarget.packetFrames(5000, 1000, hasPrevious = true, sampleRate = 48_000))
        assertEquals(0, AudioJitterTarget.packetFrames(0, 48_000 * 2, hasPrevious = true, sampleRate = 48_000))
        assertEquals(0, AudioJitterTarget.packetFrames(0, 1024, hasPrevious = true, sampleRate = 0))
    }

    @Test
    fun packetMillisMatchesTheCommonPacketDurations() {
        // AAC-LC at 48 kHz packs 1024 frames -> 21 ms.
        assertEquals(21L, AudioJitterTarget.packetMillis(1024, 48_000))
        // Opus telephony packets are 20 ms (960 frames).
        assertEquals(20L, AudioJitterTarget.packetMillis(960, 48_000))
        // Unknown packet size falls back to the AAC default rather than to zero.
        assertEquals(21L, AudioJitterTarget.packetMillis(0, 48_000))
        assertEquals(0L, AudioJitterTarget.packetMillis(1024, 0))
    }
}
