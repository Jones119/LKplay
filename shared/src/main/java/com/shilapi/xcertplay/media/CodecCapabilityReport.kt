package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * One honest line about the codecs this head unit actually provides.
 *
 * This ROM has no Opus codec at all, which is why the app ships libopus, and its vendor H.264 decoder
 * can misbehave in ways that only show up mid-session (docs/_digest/36, /37). EasyPlay answers the same
 * question with a full capability object that additionally configures and starts each codec
 * (`LegacyMediaCapabilities`); creating each one is the cheap version of the same signal, and it is
 * reported rather than acted on: wireless CarPlay **must** keep advertising Opus, because taking it
 * out of the advertisement makes iOS stop routing audio to the car entirely (docs/_digest/27 §6sexies).
 */
object CodecCapabilityReport {
    private const val OPUS = "audio/opus"

    fun line(): String {
        val h264 = probeDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        val aac = probeDecoder(MediaFormat.MIMETYPE_AUDIO_AAC)
        val opusDecoder = probeDecoder(OPUS)
        val opusEncoder = probeEncoder(OPUS)
        val libopus = if (LegacyNativeOpus.available) "yes" else "no"
        return "codec capabilities h264=$h264 aac=$aac platformOpusDecode=$opusDecoder " +
            "platformOpusEncode=$opusEncoder bundledLibopus=$libopus"
    }

    private fun probeDecoder(mime: String): String = runCatching {
        MediaCodec.createDecoderByType(mime).release()
        "yes"
    }.getOrElse { "no(${it.javaClass.simpleName})" }

    private fun probeEncoder(mime: String): String = runCatching {
        MediaCodec.createEncoderByType(mime).release()
        "yes"
    }.getOrElse { "no(${it.javaClass.simpleName})" }
}
