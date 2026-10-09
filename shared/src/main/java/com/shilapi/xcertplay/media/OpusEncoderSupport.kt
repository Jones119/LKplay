package com.shilapi.xcertplay.media

import android.os.Build

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: the bundled software encoder also works on
 * head units without a platform Opus encoder. Probe it before advertising the microphone formats,
 * without opening the microphone or changing the audio mode.
 *
 * CS11: `android.media.MediaCodecList` and an Opus MediaFormat are API 21+, so every reference to
 * them is behind a version check. Loading the class speculatively would throw NoClassDefFoundError
 * on this head unit, which is why the check is not simply wrapped in runCatching.
 */
object OpusEncoderSupport {
    /**
     * Memoized because the answer cannot change while the process runs and the software probe builds
     * a real Opus encoder: without this, every info plist rebuild would encode a throwaway frame on
     * the main thread of a 1.0 GHz Cortex-A9.
     *
     * The lazy initializer calls the two-argument [isAvailable], never the no-argument one: that one
     * reads this field, so delegating to it would recurse until the stack ran out.
     */
    private val cached: Boolean by lazy {
        isAvailable(
            platformAvailable = ::platformAvailable,
            software = { SoftwareOpusEncoder(bitrate = 48_000) },
        )
    }

    fun isAvailable(): Boolean = cached

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        if (runCatching(platformAvailable).getOrDefault(false)) return true
        // A probe that cannot even build or run an encoder is not a capability; every failure is
        // contained here because the caller is building the info plist iOS connects with.
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= MEDIACODEC_OPUS_ENCODER_API) Api21CodecList.hasOpusEncoder() else false

    private const val MEDIACODEC_OPUS_ENCODER_API = 21
}

/**
 * CS11: 版本隔离持有者——`MediaCodecList(int)` 构造函数、`codecInfos` 与 `REGULAR_CODECS` 都是 API 21
 * 才有的（类本身 `since=16`，但 16–20 只有已废弃的 `getCodecCount()/getCodecInfoAt()`）。原来的写法把
 * 调用放在 [OpusEncoderSupport] 的另一个方法里，靠"那个方法在 4.4 上不会被调用"来保证安全；按本项目
 * 约定（`docs/_digest/08 §4.3`、`docs/_digest/17 §2` 第 3 条）改为独立静态类，类型只在 21+ 被加载。
 * 4.4 上 [OpusEncoderSupport] 的 `platformAvailable` 返回 false，走 [SoftwareOpusEncoder] 软件探测。
 *
 * 注意 `AndroidMediaSink.kt` 用的是 `getCodecCount()/getCodecInfoAt()` 老 API，不要改。
 */
private object Api21CodecList {
    fun hasOpusEncoder(): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(android.media.MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
    } catch (_: Exception) {
        false
    }
}
