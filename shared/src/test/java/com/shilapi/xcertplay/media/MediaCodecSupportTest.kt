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

    // ---------------------------------------------------------------------------------------------
    // docs/_digest/79 A5：音频路径改成「ADTS 头直写 MediaCodec 输入缓冲 + 访问单元流式跟在后面」，
    // 不再每包拼一个 header+accessUnit 数组。下面这组测试是该重构的安全网：
    // 它必须与旧的 adtsFrame 输出**逐字节相同**，否则解码器会读到错的头。
    // ---------------------------------------------------------------------------------------------

    @Test
    fun writeAdtsHeaderIsByteIdenticalToAdtsFrameHeader() {
        // 覆盖音频路径真实会遇到的组合，外加两个边界（0 字节、8184 上限）。
        val sizes = listOf(0, 1, 2, 7, 8, 100, 1024, 5000, 8183, 8184)
        val rates = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 96_000)
        val channelCounts = listOf(1, 2, 3, 6, 7)

        for (size in sizes) {
            for (rate in rates) {
                for (channels in channelCounts) {
                    val unit = ByteArray(size) { (it and 0x7f).toByte() }
                    val expected = MediaCodecSupport.adtsFrame(unit, rate, channels)
                    assertEquals(
                        "size=$size rate=$rate ch=$channels",
                        size + MediaCodecSupport.ADTS_HEADER_BYTES,
                        expected.size,
                    )

                    val scratch = ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES)
                    val written =
                        MediaCodecSupport.writeAdtsHeader(scratch, 0, size, rate, channels)
                    assertEquals(MediaCodecSupport.ADTS_HEADER_BYTES, written)
                    assertArrayEquals(
                        "header mismatch size=$size rate=$rate ch=$channels",
                        expected.copyOfRange(0, MediaCodecSupport.ADTS_HEADER_BYTES),
                        scratch,
                    )

                    // 整帧等价：头 + 单元 == adtsFrame 的输出
                    assertArrayEquals(
                        "frame mismatch size=$size rate=$rate ch=$channels",
                        expected,
                        scratch + unit,
                    )
                }
            }
        }
    }

    @Test
    fun writeAdtsHeaderHonoursANonZeroOffset() {
        val target = ByteArray(32) { 0x5A }
        val written = MediaCodecSupport.writeAdtsHeader(target, 5, 100, 48_000, 2)
        assertEquals(MediaCodecSupport.ADTS_HEADER_BYTES, written)

        val expected = MediaCodecSupport.adtsFrame(ByteArray(100), 48_000, 2)
        assertArrayEquals(
            expected.copyOfRange(0, MediaCodecSupport.ADTS_HEADER_BYTES),
            target.copyOfRange(5, 5 + MediaCodecSupport.ADTS_HEADER_BYTES),
        )
        // 偏移之外的字节不能被碰
        assertEquals(0x5A.toByte(), target[0])
        assertEquals(0x5A.toByte(), target[4])
        assertEquals(0x5A.toByte(), target[12])
    }

    @Test
    fun writeAdtsHeaderRejectsAnUndescribableAccessUnitWithoutTouchingTheBuffer() {
        val scratch = ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES) { 0x11 }
        // 与 adtsFrame 同样的上限：超过 8184 时 ADTS frame_length 会溢出到 channel_configuration。
        assertEquals(0, MediaCodecSupport.writeAdtsHeader(scratch, 0, 8185, 48_000, 2))
        assertEquals(0, MediaCodecSupport.writeAdtsHeader(scratch, 0, 9000, 48_000, 2))
        assertEquals(0, MediaCodecSupport.writeAdtsHeader(scratch, 0, -1, 48_000, 2))
        // 被拒时不许写脏数据 —— 否则调用方即使判了 0 也可能已经把垃圾留在缓冲里
        assertArrayEquals(ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES) { 0x11 }, scratch)
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
