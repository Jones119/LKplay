package com.shilapi.xcertplay.media

import java.io.Closeable

/**
 * JNI 绑定：`libdiplay_aac_fsl.so`（`shared/src/main/jni/diplay_aac_fsl.c`）。
 *
 * 该库本身**不含**厂商解码器，只是在运行时 `dlopen` 车机 `/system/lib` 下的
 * `lib_aac_dec_v2_arm12_elinux.so` 并探测入口符号。这样既避免再分发厂商专有二进制，
 * 又能在 API 19 上直接打开系统库（没有 API 21+ 的 linker namespace 隔离）。
 *
 * ⚠️ JNI 符号绑定到**确切的类名与方法名**，两者都不可改名
 * （与 [LegacyNativeOpus] 同样的约束）。
 */
internal object FreescaleAacNative {
    private val loaded: Boolean = try {
        System.loadLibrary("diplay_aac_fsl")
        true
    } catch (_: Throwable) {
        false
    }

    /** 本机 shim 是否加载成功（与厂商解码器是否可用是两件事）。 */
    val shimLoaded: Boolean get() = loaded

    fun probe(): Boolean = if (loaded) runCatching { probeNative() }.getOrDefault(false) else false

    fun status(): String =
        if (loaded) runCatching { statusNative() }.getOrDefault("shim load failed")
        else "shim load failed"

    fun path(): String = if (loaded) runCatching { pathNative() }.getOrDefault("") else ""

    /**
     * 调一次厂商的 `aacd_query_dec_mem`，用返回值证明"库真的能被调用"。
     * 返回 null 表示不可用；否则返回厂商的原始描述符数组（诊断用）。
     */
    fun queryDecoderMemory(): QueryResult? {
        if (!loaded) return null
        val out = IntArray(32)
        val rc = runCatching { queryDecMemNative(out) }.getOrNull() ?: return null
        return QueryResult(rc, out)
    }

    data class QueryResult(val returnCode: Int, val descriptors: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is QueryResult && returnCode == other.returnCode &&
                descriptors.contentEquals(other.descriptors)

        override fun hashCode(): Int = 31 * returnCode + descriptors.contentHashCode()
    }

    /** 厂商解码器自报的版本串（`aacd_decode_versionInfo`）；不可用时为空串。 */
    fun version(): String = if (loaded) runCatching { versionNative() }.getOrDefault("") else ""

    /** 创建一个解码器实例；0L = 失败（原因只进 C 侧日志）。 */
    fun createDecoder(maxChannels: Int): Long =
        if (loaded) runCatching { createDecoderNative(maxChannels) }.getOrDefault(0L) else 0L

    fun destroyDecoder(handle: Long) {
        if (loaded && handle != 0L) runCatching { destroyDecoderNative(handle) }
    }

    /**
     * 解一帧。
     *
     * @param handle  [createDecoder] 的返回值
     * @param au      承载裸访问单元的数组（通常是整个 RTP 包，避免拷贝）
     * @param offset  访问单元在 [au] 中的起始偏移
     * @param length  访问单元长度
     * @param header  [MediaCodecSupport.parseAdtsHeader] 的结果
     * @param pcmOut  Q15 交错输出缓冲
     * @param result  出参 [样本数, 声道数, 采样率]
     * @return 0 成功；正数 = 厂商返回码；负数 = 本地错误（-3 输出缓冲太小，-4 ABI 不支持）
     */
    fun decodeFrame(
        handle: Long,
        au: ByteArray,
        offset: Int,
        length: Int,
        header: MediaCodecSupport.AdtsHeader,
        pcmOut: ShortArray,
        result: IntArray,
    ): Int {
        if (!loaded || handle == 0L) return -4
        val hdr = intArrayOf(
            header.samplingFreqIndex,
            header.channelConfig,
            header.protectionAbsent,
            header.adtsBufferFullness,
        )
        return runCatching {
            decodeFrameNative(handle, au, offset, length, hdr, pcmOut, result)
        }.getOrDefault(-5)
    }

    @JvmStatic private external fun probeNative(): Boolean
    @JvmStatic private external fun statusNative(): String
    @JvmStatic private external fun pathNative(): String
    @JvmStatic private external fun versionNative(): String
    @JvmStatic private external fun queryDecMemNative(out: IntArray): Int
    @JvmStatic private external fun createDecoderNative(maxChannels: Int): Long
    @JvmStatic private external fun destroyDecoderNative(handle: Long)
    @JvmStatic private external fun decodeFrameNative(
        handle: Long,
        au: ByteArray,
        auOffset: Int,
        auLength: Int,
        hdr: IntArray,
        pcmOut: ShortArray,
        result: IntArray,
    ): Int
}

/**
 * 厂商 AAC 解码器的可用性探测结果。**只报告事实，不做决定**——决定在渲染器里做，
 * 因为在真实解码路径跑通之前，任何"可用"都只是"具备条件"。
 */
sealed interface FreescaleAacAvailability {
    /** 库与入口符号都在，且 `aacd_query_dec_mem` 返回 0。 */
    data class Ready(val path: String, val descriptors: IntArray) : FreescaleAacAvailability {
        override fun equals(other: Any?): Boolean =
            other is Ready && path == other.path && descriptors.contentEquals(other.descriptors)

        override fun hashCode(): Int = 31 * path.hashCode() + descriptors.contentHashCode()
    }

    /** 不能用的原因，直接给人看（会进诊断行）。 */
    data class Unavailable(val reason: String) : FreescaleAacAvailability
}

/**
 * 探测车机上的 Freescale AAC 解码器。结果缓存——它在一个进程生命周期内不会变，
 * 而渲染器每次建流都要问一次。
 *
 * 判据分层（每一层都可能失败，且原因要能区分）：
 *   1. shim 本身能否加载（ABI/打包问题）
 *   2. 车机 `/system/lib` 下那条路 `dlopen` 能否成功（ROM 差异）
 *   3. `aacd_query_dec_mem` 是否返回 0（库真的可调用）
 */
object FreescaleAacBridge {
    @Volatile private var cached: FreescaleAacAvailability? = null

    /**
     * 一次真正的解码往返（建解码器 → 解一帧 → 销毁），用于**证明**软解路径可用。
     *
     * 与 [availability] 的区别：那三步只证明"库能加载、能调用"；这里会真的走完
     * `aacd_query_dec_mem → 分配内存 → aacd_decoder_init → aacd_decode_frame`。
     * 用一帧静音 AAC-LC 做输入（48 kHz 立体声、ADTS 参数），能建起来就说明整套 ABI 是对的。
     *
     * 结果同样缓存——这是一次性能力判定，不是每帧都问。
     */
    @Volatile private var decodeProbe: String? = null

    fun availability(): FreescaleAacAvailability {
        cached?.let { return it }
        val result = probe()
        cached = result
        return result
    }

    /**
     * 软解路径能否**真正解码**。返回可读结论（成功时是解码器版本串）。
     *
     * 探针用 ADTS 参数 + 一帧静音 AAC-LC 访问单元：真正的静音帧是 1 字节 `0x00` 的
     * fill element，但不同编码器产出不同，所以这里**不假设**它能解出声音——
     * 只看 `aacd_decoder_init` 是否成功、以及 `aacd_decode_frame` 有没有崩或返回硬错误。
     * 即使返回业务错误码，也说明 ABI 是对的（能进到解码逻辑里）。
     */
    fun probeDecode(): String {
        decodeProbe?.let { return it }
        val result = runDecodeProbe()
        decodeProbe = result
        return result
    }

    private fun runDecodeProbe(): String {
        if (availability() !is FreescaleAacAvailability.Ready) {
            return "unavailable: ${reason()}"
        }
        val handle = FreescaleAacNative.createDecoder(MAX_PROBE_CHANNELS)
        if (handle == 0L) return "aacd_decoder_init failed"
        return try {
            // 一帧静音的 ADTS 参数：48 kHz(idx=3)、立体声(ch=2)、无 CRC、VBR。
            val header = MediaCodecSupport.AdtsHeader(
                samplingFreqIndex = 3,
                channelConfig = 2,
                protectionAbsent = 1,
                adtsBufferFullness = 0x7FF,
            )
            // 最小合法访问单元：单个 fill element（ID_END 之前的 padding）。
            val au = byteArrayOf(0x00)
            val pcm = ShortArray(MAX_PROBE_SAMPLES)
            val out = IntArray(3)
            val rc = FreescaleAacNative.decodeFrame(handle, au, 0, au.size, header, pcm, out)
            val version = FreescaleAacNative.version().ifEmpty { "unknown" }
            // rc==0 是最好；业务错误码（正数）同样证明 ABI 通了；负数是我们自己的错。
            if (rc >= 0) {
                "ok version=$version rc=$rc samples=${out[0]} channels=${out[1]} rate=${out[2]}"
            } else {
                "decode call failed rc=$rc"
            }
        } finally {
            FreescaleAacNative.destroyDecoder(handle)
        }
    }

    /** 探测一次，返回是否可用。握手失败时 [reason] 给出可读原因。 */
    fun isAvailable(): Boolean = availability() is FreescaleAacAvailability.Ready

    fun reason(): String = when (val a = availability()) {
        is FreescaleAacAvailability.Ready -> a.path
        is FreescaleAacAvailability.Unavailable -> a.reason
    }

    /** 仅供测试/诊断：清掉缓存以便重新探测。 */
    internal fun resetForDiagnostics() {
        cached = null
        decodeProbe = null
    }

    private const val MAX_PROBE_CHANNELS = 2
    private const val MAX_PROBE_SAMPLES = MAX_PROBE_CHANNELS * 1024

    private fun probe(): FreescaleAacAvailability {
        if (!FreescaleAacNative.shimLoaded) {
            return FreescaleAacAvailability.Unavailable("shim libdiplay_aac_fsl not loaded")
        }
        if (!FreescaleAacNative.probe()) {
            // 这里带上 shim 自己记的原因：是路径不存在，还是某个入口符号缺失。
            return FreescaleAacAvailability.Unavailable(FreescaleAacNative.status())
        }
        val query = FreescaleAacNative.queryDecoderMemory()
            ?: return FreescaleAacAvailability.Unavailable("aacd_query_dec_mem call failed")
        if (query.returnCode != 0) {
            // 51 = 无效参数（A1 逆向确认）；原样上报，便于对照 evidence 里的返回码表。
            return FreescaleAacAvailability.Unavailable(
                "aacd_query_dec_mem rc=${query.returnCode}",
            )
        }
        return FreescaleAacAvailability.Ready(FreescaleAacNative.path(), query.descriptors)
    }
}
