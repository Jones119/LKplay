package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * Reports the Opus support of the host.
 *
 * CarPlay's telephony and speechRecognition streams carry Opus whenever the accessory advertises
 * it, and the accessory must encode the microphone uplink in the same codec it advertised. Android
 * 4.4 (the CS11 head unit) ships neither an Opus encoder nor an Opus decoder, which silently broke
 * both directions of a call: the caller's voice could not be decoded and the microphone uplink died
 * at the encoder. Advertising PCM only on such a host keeps calls on a codec path that exists.
 */
object AudioCodecCapabilities {
    private val opus: Boolean by lazy {
        canCreate { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) } &&
            canCreate { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }
    }

    /** True only when both the Opus encoder (uplink) and decoder (call audio) are present. */
    fun opusCodecsAvailable(): Boolean = opus

    private inline fun canCreate(create: () -> MediaCodec): Boolean = try {
        create().release()
        true
    } catch (_: Exception) {
        false
    }
}
