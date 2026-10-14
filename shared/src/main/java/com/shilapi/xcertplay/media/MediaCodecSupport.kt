package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Pure byte helpers that convert the CarPlay screen/audio payloads into the
 * records Android MediaCodec and AudioTrack expect. Kept free of Android types
 * so they stay testable on the JVM.
 */
object MediaCodecSupport {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** Splits an AVCDecoderConfigurationRecord into raw first SPS and first PPS. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> {
        if (codecData.size < 7) return emptySet()
        var cursor = 6
        val sps = readParameterSets(codecData, cursor, codecData[5].toInt() and 0x1f)
        cursor += sps.sumOf { it.size + 2 }
        if (cursor >= codecData.size) return emptySet()
        val pps = readParameterSets(codecData, cursor + 1, codecData[cursor].toInt() and 0xff)
        return (sps.firstOrNull() ?: ByteArray(0)) to (pps.firstOrNull() ?: ByteArray(0))
    }

    /** Converts CarPlay's length-prefixed NAL units into an Annex B byte stream. */
    fun toAnnexB(lengthPrefixed: ByteArray): ByteArray {
        if (lengthPrefixed.size >= 4 &&
            lengthPrefixed[0] == 0.toByte() &&
            lengthPrefixed[1] == 0.toByte() &&
            lengthPrefixed[2] == 0.toByte() &&
            lengthPrefixed[3] == 1.toByte()
        ) {
            return lengthPrefixed
        }

        var inputOffset = 0
        var outputSize = 0
        while (inputOffset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, inputOffset)
            inputOffset += 4
            if (length <= 0 || length > lengthPrefixed.size - inputOffset) return ByteArray(0)
            if (length > Int.MAX_VALUE - outputSize - START_CODE.size) return ByteArray(0)
            outputSize += START_CODE.size + length
            inputOffset += length
        }
        if (outputSize == 0 || inputOffset != lengthPrefixed.size) return ByteArray(0)

        val output = ByteArray(outputSize)
        var offset = 0
        var outputOffset = 0
        while (offset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, offset)
            offset += 4
            if (length <= 0 || offset + length > lengthPrefixed.size) break
            START_CODE.copyInto(output, outputOffset)
            outputOffset += START_CODE.size
            lengthPrefixed.copyInto(output, outputOffset, offset, offset + length)
            outputOffset += length
            offset += length
        }
        return output
    }

    /** IDR (AVC) starts an independently decodable reference chain. */
    fun isRandomAccess(annexB: ByteArray, codec: VideoCodec): Boolean =
        isRandomAccess(annexB, annexB.size, codec)

    /** [isRandomAccess] for a pooled buffer that can be longer than the frame it holds. */
    fun isRandomAccess(annexB: ByteArray, length: Int, codec: VideoCodec): Boolean {
        var cursor = 0
        while (cursor + 3 < length) {
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                annexB[cursor + 2] == 1.toByte() -> 3
                annexB[cursor + 2] == 0.toByte() && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0 && cursor + prefix < length) {
                val header = annexB[cursor + prefix].toInt() and 255
                if (header and 31 == 5) return true
                cursor += prefix
            } else cursor++
        }
        return false
    }

    /**
     * Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame; empty for an access unit ADTS
     * cannot describe.
     *
     * ADTS `frame_length` is 13 bits and includes the 7-byte header, so an access unit longer than
     * [ADTS_MAX_ACCESS_UNIT_BYTES] would spill its high bits into `channel_configuration`
     * (header[3]) and describe a stream the decoder would then misread. Such a unit is rejected
     * here for the same reason [toAnnexB] and [aacAccessUnit] return an empty array for malformed
     * input: an empty result is a drop, not a torn-down stream (see AndroidMediaSink.handle(),
     * which drops the packet before calling this and counts it).
     */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        if (accessUnit.size > ADTS_MAX_ACCESS_UNIT_BYTES) return ByteArray(0)
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnit.size + ADTS_HEADER_BYTES
        val header = ByteArray(ADTS_HEADER_BYTES)
        header[0] = 0xff.toByte()
        header[1] = 0xf1.toByte()
        header[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        // frame_length is 13 bits: 2 bits of it live next to channel_configuration, hence the mask.
        header[3] = (((channelConfig and 0x3) shl 6) or ((frameLength ushr 11) and 0x3)).toByte()
        header[4] = ((frameLength ushr 3) and 0xff).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        header[6] = 0xfc.toByte()
        return header + accessUnit
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun emptySet(): Pair<ByteArray, ByteArray> = ByteArray(0) to ByteArray(0)

    private fun readParameterSets(source: ByteArray, offset: Int, count: Int): List<ByteArray> {
        val sets = ArrayList<ByteArray>(count)
        var cursor = offset
        var index = 0
        while (index < count && cursor + 2 <= source.size) {
            index++
            val length = readU16Be(source, cursor)
            cursor += 2
            if (cursor + length > source.size) break
            sets.add(source.copyOfRange(cursor, cursor + length))
            cursor += length
        }
        return sets
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private const val ADTS_HEADER_BYTES = 7

    /** ADTS `frame_length` is 13 bits wide and counts the [ADTS_HEADER_BYTES]-byte header. */
    private const val ADTS_MAX_FRAME_LENGTH = 0x1fff

    /**
     * Largest access unit an ADTS header can describe: 8184 bytes.
     *
     * The type-103 buffered path frames access units by a u16 length, so a malformed or hostile
     * frame can reach [adtsFrame] with far more than this; a real AAC-LC access unit is two orders
     * of magnitude smaller. Exposed so the audio path can drop such a packet before wrapping it,
     * which keeps the failure visible in the drop counters instead of silently truncating audio.
     */
    const val ADTS_MAX_ACCESS_UNIT_BYTES = ADTS_MAX_FRAME_LENGTH - ADTS_HEADER_BYTES
}
