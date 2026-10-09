package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusEncoderTest {
    @Test
    fun encodesEach20MsFrameAsOneDecodableOpusPacket() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = OpusDecoder(48_000, 1)
        assertTrue(encoder.available)

        repeat(10) { index ->
            val packets = encoder.encode(toneFrame(index))
            assertEquals(1, packets.size)
            val packet = packets.single()
            assertTrue(packet.isNotEmpty())
            assertEquals("one frame per packet", 0, packet[0].toInt() and 0x03)
            val pcm = ShortArray(SoftwareOpusEncoder.FRAME_SAMPLES)
            assertEquals(
                SoftwareOpusEncoder.FRAME_SAMPLES,
                decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false),
            )
        }
        encoder.close()
        assertTrue(encoder.encode(toneFrame(0)).isEmpty())
    }

    @Test
    fun rejectsFramesThatAreNot20Ms() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        assertTrue(encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES - 2)).isEmpty())
    }

    @Test
    fun unsupportedComplexityLeavesTheEncoderUnavailableInsteadOfThrowing() {
        // Concentus rejects complexity 11, and the OpusEncoderSupport probe builds the encoder
        // before it knows whether the parameters are accepted.
        val reported = mutableListOf<String>()
        val encoder = SoftwareOpusEncoder(bitrate = 48_000, complexity = 11) { message, _ ->
            reported.add(message)
        }
        assertFalse(encoder.available)
        assertTrue(encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES)).isEmpty())
        assertEquals(listOf("software Opus encoder unavailable"), reported)
        encoder.close()
    }

    @Test
    fun reportsTheSoftwareImplementationNameAndRunsOnTheJvmWithoutAndroid() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        assertEquals("software", encoder.implementation)
        encoder.close()
        assertFalse(encoder.available)
    }

    @Test
    fun platformEncoderIsPreferredAndSoftwareIsTheFallback() {
        val platform = FakeEncoder(available = true)
        val software = FakeEncoder(available = true)
        assertSame(
            platform,
            MicrophoneOpusEncoders.create(48_000, platform = { platform }, software = { software }),
        )

        // A platform encoder that cannot encode is released, and the bundled Java encoder takes over.
        val broken = FakeEncoder(available = false)
        assertSame(
            software,
            MicrophoneOpusEncoders.create(48_000, platform = { broken }, software = { software }),
        )
        assertTrue(broken.closed)
        assertFalse(software.closed)

        val brokenSoftware = FakeEncoder(available = false)
        assertNull(
            MicrophoneOpusEncoders.create(
                48_000,
                platform = { null },
                software = { brokenSoftware },
            ),
        )
        assertTrue(brokenSoftware.closed)
    }

    @Test
    fun aRealSoftwareEncoderIsProducedWhenThePlatformFactoryFindsNothing() {
        val created = MicrophoneOpusEncoders.create(48_000, platform = { null }) { SoftwareOpusEncoder(it) }
        assertTrue(created is SoftwareOpusEncoder)
        created!!.close()
    }

    private fun toneFrame(index: Int): ByteArray {
        val bytes = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)
        for (i in 0 until SoftwareOpusEncoder.FRAME_SAMPLES) {
            val t = (index * SoftwareOpusEncoder.FRAME_SAMPLES + i) / 48_000.0
            val sample = (8_000 * (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 660 * t))).toInt()
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    private class FakeEncoder(override val available: Boolean) : MicrophoneOpusEncoder {
        override val implementation = "fake"
        var closed = false
        override fun encode(pcm: ByteArray): List<ByteArray> = emptyList()
        override fun close() {
            closed = true
        }
    }
}
