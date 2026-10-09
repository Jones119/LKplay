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
        if (Build.VERSION.SDK_INT >= MEDIACODEC_OPUS_ENCODER_API) platformCodecListHasOpus() else false

    private fun platformCodecListHasOpus(): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(android.media.MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
    } catch (_: Exception) {
        false
    }

    private const val MEDIACODEC_OPUS_ENCODER_API = 21
}
