package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `/feedback` sampleTime must count on the stream's RTP clock, not on its decode rate.
 *
 * Opus is decoded at 48 kHz even when the phone negotiated a 16 or 24 kHz clock; reporting the
 * decode rate made a call run three times ahead of the audio that had actually arrived (upstream
 * #473/#536). Every other codec keeps its sample rate, so the wire format and the feedback clock
 * stay identical there.
 */
class AudioFormatRtpClockTest {
    @Test fun opusDecodesAtFortyEightButFeedbackUsesTheNegotiatedClock() {
        val sixteen = AudioStreamCodec.fromFormatBits(0x10000000L, payloadType = 102)
        assertEquals(48_000, sixteen.sampleRate)
        assertEquals(16_000, sixteen.rtpClockRate)
        assertEquals(AudioCodecKind.OPUS, sixteen.codec)

        val twentyFour = AudioStreamCodec.fromFormatBits(0x20000000L, payloadType = 102)
        assertEquals(48_000, twentyFour.sampleRate)
        assertEquals(24_000, twentyFour.rtpClockRate)

        val fortyEight = AudioStreamCodec.fromFormatBits(0x40000000L, payloadType = 102)
        assertEquals(48_000, fortyEight.sampleRate)
        assertEquals(48_000, fortyEight.rtpClockRate)
    }

    @Test fun aacFeedbackClockStaysOnTheSampleRate() {
        val fortyEight = AudioStreamCodec.fromFormatBits(0x800000L, payloadType = 103)
        assertEquals(48_000, fortyEight.sampleRate)
        assertEquals(48_000, fortyEight.rtpClockRate)

        val fortyFourOne = AudioStreamCodec.fromFormatBits(0x400000L, payloadType = 103)
        assertEquals(44_100, fortyFourOne.sampleRate)
        assertEquals(44_100, fortyFourOne.rtpClockRate)
    }

    @Test fun pcmFeedbackClockStaysOnTheSampleRate() {
        val stereo48 = AudioStreamCodec.fromFormatBits(0x8000L, payloadType = 100)
        assertEquals(AudioCodecKind.LPCM, stereo48.codec)
        assertEquals(48_000, stereo48.sampleRate)
        assertEquals(48_000, stereo48.rtpClockRate)

        val mono44 = AudioStreamCodec.fromFormatBits(0x400L, payloadType = 100)
        assertEquals(44_100, mono44.sampleRate)
        assertEquals(44_100, mono44.rtpClockRate)
    }

    @Test fun theDefaultClockIsTheSampleRate() {
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, payloadType = 100)
        assertEquals(format.sampleRate, format.rtpClockRate)
    }
}
