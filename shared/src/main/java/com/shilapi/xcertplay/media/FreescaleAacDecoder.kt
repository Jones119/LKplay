package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * 厂商 Freescale AAC 解码器的软解器（`docs/_digest/79` 的 A1）。
 *
 * 只在 [FreescaleAacBridge] 判定真正能解码时才应该被创建；本类不做可用性判断，
 * 创建失败一律返回 null 交给调用方回落。
 *
 * **缓冲复用**：解码输出写进类内持有的 [pcmScratch]，`decode()` 返回的是同一个数组
 * （只有 `[0, sampleCount)` 区间是本次的有效数据，长度需从 [decodedSamples] 取）。
 * 这是刻意的——每帧新建 ShortArray 会把这个优化的意义抵消掉（参见 A5 的同类取舍）。
 *
 * 线程约束：只在 `carplay-audio` 线程上使用（与 `AudioRenderer` 的其它字段一致）。
 */
internal class FreescaleAacDecoder private constructor(
    private val handle: Long,
    private val maxSamples: Int,
) : Closeable {

    /** 复用输出缓冲；有效区间由 [decodedSamples] 给出。 */
    val pcmScratch: ShortArray = ShortArray(maxSamples)

    private val result = IntArray(3)
    private var closed = false

    /** 本次 `decode()` 写入 [pcmScratch] 的 short 数（已乘声道数）。 */
    var decodedSamples: Int = 0
        private set

    /** 本次解码得到的声道数；0 = 未知（调用方应按流格式处理）。 */
    var decodedChannels: Int = 0
        private set

    /** 本次解码得到的采样率 Hz；0 = 未知。 */
    var decodedSampleRate: Int = 0
        private set

    /**
     * 解一帧。
     *
     * @param au     承载裸访问单元的数组（通常是整个 RTP 包，避免拷贝）
     * @param offset 访问单元起始偏移
     * @param length 访问单元长度
     * @param header 已解析的 ADTS 头字段
     * @return true = [pcmScratch] 里有 [decodedSamples] 个有效 short
     */
    fun decode(au: ByteArray, offset: Int, length: Int, header: MediaCodecSupport.AdtsHeader): Boolean {
        if (closed) return false
        decodedSamples = 0
        decodedChannels = 0
        decodedSampleRate = 0
        val rc = FreescaleAacNative.decodeFrame(handle, au, offset, length, header, pcmScratch, result)
        if (rc != STREAM_OK) {
            failures++
            if (failures <= MAX_LOGGED_FAILURES) {
                Log.w(TAG, "Freescale AAC decode rc=$rc len=$length")
            }
            return false
        }
        decodedSamples = result[0].coerceIn(0, maxSamples)
        decodedChannels = result[1]
        decodedSampleRate = result[2]
        return decodedSamples > 0
    }

    /** 累计失败次数，供诊断行判断"能建起来但解不出来"这种半可用状态。 */
    var failures: Int = 0
        private set

    override fun close() {
        if (closed) return
        closed = true
        FreescaleAacNative.destroyDecoder(handle)
    }

    companion object {
        private const val TAG = "xcertplay-usb"

        /**
         * 厂商返回 0 = 无错；31 = `AACD_ERROR_START_BLOCK`，表示本帧输出由**错误隐藏**产生。
         * 头文件注明它是 "It can be used as NO ERROR output"，所以两者都当成功。
         */
        private const val STREAM_OK = 0
        private const val RECOVERY_START_BLOCK = 31
        private const val MAX_LOGGED_FAILURES = 5

        /**
         * 创建一个解码器。失败返回 null——调用方**必须**回落到 MediaCodec，不要让用户没声音。
         *
         * @param onDiagnostic 一次性诊断回调（成功/失败原因）
         */
        fun create(maxChannels: Int, onDiagnostic: (String) -> Unit = {}): FreescaleAacDecoder? {
            val probe = FreescaleAacBridge.probeDecode()
            if (!probe.startsWith("ok")) {
                onDiagnostic("Freescale AAC unavailable: $probe")
                return null
            }
            val handle = FreescaleAacNative.createDecoder(maxChannels)
            if (handle == 0L) {
                onDiagnostic("Freescale AAC aacd_decoder_init failed")
                return null
            }
            onDiagnostic("Freescale AAC decoder created channels=$maxChannels $probe")
            return FreescaleAacDecoder(handle, maxChannels * AAC_FRAME_SIZE)
        }

        private const val AAC_FRAME_SIZE = 1024

        /** 供上层判断"这一帧的错误码是否算可继续"。 */
        fun isRecoverable(rc: Int): Boolean = rc == STREAM_OK || rc == RECOVERY_START_BLOCK
    }
}
