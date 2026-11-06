package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    @Test
    fun buildIntoWritesTheSameBytesAsBuild() {
        // Around both 512-byte pad boundaries (484 + 28 = 512, 996 + 28 = 1024), plus what the TX pump
        // actually sends: a bare ACK (74), a full 1500-byte MTU frame (1514), and the u16 limit.
        val lengths = listOf(1, 74, 483, 484, 485, 995, 996, 997, 1400, 1514, 1600, 65_507)
        for (length in lengths) {
            val frame = ByteArray(length) { (it * 31 + 7).toByte() }
            val expected = Ntb16Codec.build(frame, 0x2a)
            // A reused, oversized buffer still holding stale bytes must not leak them into the block.
            val out = ByteArray(Ntb16Codec.maxBlockBytes(length) + 64) { 0x5a.toByte() }
            val written = Ntb16Codec.buildInto(frame, length, 0x2a, out)
            assertEquals("length=$length", expected.size, written)
            assertArrayEquals("length=$length", expected, out.copyOf(written))
        }
    }

    @Test
    fun buildIntoFramesOnlyTheRequestedPrefixOfAReusedBuffer() {
        // The TX pump hands over its whole 1600-byte buffer with the frame's real length.
        val buffer = ByteArray(1600) { it.toByte() }
        val out = ByteArray(Ntb16Codec.maxBlockBytes(1600))
        val written = Ntb16Codec.buildInto(buffer, 60, 3, out)

        assertArrayEquals(Ntb16Codec.build(buffer.copyOf(60), 3), out.copyOf(written))
        assertArrayEquals(buffer.copyOf(60), Ntb16Codec.parse(out, 0, written).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildIntoRejectsAnOutputBufferWithoutRoomForThePad() {
        // 484 + 28 = 512 needs its pad byte, so a 512-byte output is one byte short.
        Ntb16Codec.buildInto(ByteArray(484), 484, 1, ByteArray(512))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildIntoRejectsALengthBeyondTheSourceArray() {
        Ntb16Codec.buildInto(ByteArray(10), 11, 1, ByteArray(100))
    }
}
