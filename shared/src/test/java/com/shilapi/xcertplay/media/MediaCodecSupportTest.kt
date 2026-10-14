package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class MediaCodecSupportTest {
    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun anAccessUnitTooLongForAdtsIsRejectedInsteadOfSpillingIntoTheHeader() {
        // ADTS frame_length is 13 bits, so 9000 + 7 = 9007 would spill bit 12 into
        // channel_configuration and describe a different stream to the decoder. Only the type-103
        // buffered path (u16 frame lengths) can deliver an access unit this long.
        assertEquals(0, MediaCodecSupport.adtsFrame(ByteArray(9000), 44_100, 2).size)
        assertEquals(0, MediaCodecSupport.adtsFrame(ByteArray(8185), 44_100, 2).size)
    }

    @Test
    fun theLongestDescribableAccessUnitStillGetsAConsistentHeader() {
        val frame = MediaCodecSupport.adtsFrame(ByteArray(8184), 48_000, 2)

        assertEquals(8184 + 7, frame.size)
        assertEquals(0x1fff, adtsFrameLength(frame))
        assertEquals(2, adtsChannelConfiguration(frame))
    }

    /** ADTS frame_length: 2 bits in byte 3, 8 in byte 4, 3 in the top of byte 5. */
    private fun adtsFrameLength(frame: ByteArray): Int =
        ((frame[3].toInt() and 0x3) shl 11) or
            ((frame[4].toInt() and 0xff) shl 3) or
            ((frame[5].toInt() and 0xe0) ushr 5)

    /** channel_configuration: 1 bit in byte 2, 2 bits in the top of byte 3. */
    private fun adtsChannelConfiguration(frame: ByteArray): Int =
        ((frame[2].toInt() and 0x1) shl 2) or ((frame[3].toInt() and 0xc0) ushr 6)
}
