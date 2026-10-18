package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.transport.readU16Be
import com.shilapi.xcertplay.transport.readU32Be

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
        return ByteArray(ADTS_HEADER_BYTES + accessUnit.size).also { frame ->
            writeAdtsHeader(frame, 0, accessUnit.size, sampleRate, channels)
            accessUnit.copyInto(frame, ADTS_HEADER_BYTES)
        }
    }

    /**
     * Writes just the 7-byte ADTS header for an access unit of [accessUnitBytes] into [target].
     *
     * Exists so the audio renderer can put the header **straight into the MediaCodec input buffer**
     * and then stream the access unit after it, instead of building an intermediate
     * `header + accessUnit` array per packet (docs/_digest/79 A5: the wireless path used to allocate
     * twice and copy twice for every one of ~48 packets/s).
     *
     * Byte-identical to the header [adtsFrame] produces — both go through the same code, so they
     * cannot drift apart, and `MediaCodecSupportTest` asserts the equality.
     *
     * Returns the header size ([ADTS_HEADER_BYTES]), or 0 when the access unit is too large for
     * ADTS `frame_length` to describe (the caller must drop the packet, as with [adtsFrame]).
     */
    fun writeAdtsHeader(
        target: ByteArray,
        offset: Int,
        accessUnitBytes: Int,
        sampleRate: Int,
        channels: Int,
    ): Int {
        if (accessUnitBytes < 0 || accessUnitBytes > ADTS_MAX_ACCESS_UNIT_BYTES) return 0
        require(offset >= 0 && offset + ADTS_HEADER_BYTES <= target.size) {
            "ADTS header does not fit: offset=$offset size=${target.size}"
        }
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnitBytes + ADTS_HEADER_BYTES
        target[offset] = 0xff.toByte()
        target[offset + 1] = 0xf1.toByte()
        target[offset + 2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        // frame_length is 13 bits: 2 bits of it live next to channel_configuration, hence the mask.
        target[offset + 3] = (((channelConfig and 0x3) shl 6) or ((frameLength ushr 11) and 0x3)).toByte()
        target[offset + 4] = ((frameLength ushr 3) and 0xff).toByte()
        target[offset + 5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        target[offset + 6] = 0xfc.toByte()
        return ADTS_HEADER_BYTES
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

    /**
     * ADTS 帧头的字段，供 Freescale 解码器单独传入（它不要 ADTS 字节，只要这些参数 + 裸 AU）。
     *
     * 字段布局对应 ISO/IEC 13818-7 的 adts_fixed_header + adts_variable_header，
     * 与厂商头文件 `aacd_dec_interface.h` 的 `ADTS_Header` 一致。
     */
    data class AdtsHeader(
        val samplingFreqIndex: Int,
        val channelConfig: Int,
        val protectionAbsent: Int,
        val adtsBufferFullness: Int,
    )

    /**
     * 解析 ADTS 头。返回 null 表示不是 ADTS（同步字不符或采样率索引非法）。
     *
     * 之所以自己解析而不是复用 [writeAdtsHeader] 的生成逻辑：生产者是我们（写头），
     * 消费者是厂商解码器（读头），两侧独立实现才能在其中一个出错时被发现。
     */
    fun parseAdtsHeader(data: ByteArray, offset: Int): AdtsHeader? {
        if (offset < 0 || offset + ADTS_HEADER_BYTES > data.size) return null
        // syncword: 12 bits all ones
        if ((data[offset].toInt() and 0xff) != 0xff) return null
        if ((data[offset + 1].toInt() and 0xf0) != 0xf0) return null
        val samplingFreqIndex = (data[offset + 2].toInt() ushr 2) and 0x0f
        if (samplingFreqIndex >= 0x0c) return null
        val channelConfig =
            ((data[offset + 2].toInt() and 0x01) shl 2) or ((data[offset + 3].toInt() ushr 6) and 0x03)
        val protectionAbsent = (data[offset + 1].toInt() ushr 0) and 0x01
        val bufferFullness =
            ((data[offset + 5].toInt() and 0x1f) shl 6) or ((data[offset + 6].toInt() ushr 2) and 0x3f)
        return AdtsHeader(
            samplingFreqIndex = samplingFreqIndex,
            channelConfig = channelConfig,
            protectionAbsent = protectionAbsent,
            adtsBufferFullness = bufferFullness,
        )
    }

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

    /**
     * ADTS 头长度。公开是因为音频路径要按它预留输入缓冲空间
     * （[writeAdtsHeader] 直接写进 MediaCodec 的输入缓冲，见 doc 79 A5）。
     */
    const val ADTS_HEADER_BYTES = 7

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
