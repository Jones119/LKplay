package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun anOverflowingNalLengthLeavesThePayloadUntouched() {
        // 0x7FFFFFFF is a legal u32 on the wire. offset + nalLength used to wrap negative, pass the
        // bounds check, and the next length read then indexed at a negative offset
        // (ArrayIndexOutOfBoundsException), which escaped onMessage and closed the whole stream - the
        // clear-frame branch made that reachable from any host on the link with a header plus a
        // four-byte body.
        val payload = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun anOverflowingLengthAfterAValidNalAlsoLeavesThePayloadUntouched() {
        // The realistic frame shape: the first NAL is well formed, the second declares 0x7FFFFFFF.
        val payload =
            byteArrayOf(0, 0, 0, 2, 0x40, 0x01) +
                byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun aNalLengthPastTheEndOfThePayloadLeavesItUntouched() {
        // The same branch without an overflow: the length fits an Int but not the payload.
        val payload = byteArrayOf(0, 0, 0, 7, 0, 0, 0, 0)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun aPooledBufferConvertsOnlyUpToTheFrameLength() {
        val first = byteArrayOf(0x40, 0x01)
        val frameLength = 4 + first.size
        val buffer = byteArrayOf(0, 0, 0, first.size.toByte()) + first + ByteArray(8) { 0x7F }

        val converted = ScreenCodec.lengthPrefixedToAnnexB(buffer, frameLength)

        assertSame(buffer, converted)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + first, buffer.copyOf(frameLength))
        // Bytes past the frame belong to a recycled buffer and must stay untouched.
        assertArrayEquals(ByteArray(8) { 0x7F }, buffer.copyOfRange(frameLength, buffer.size))
    }
}
