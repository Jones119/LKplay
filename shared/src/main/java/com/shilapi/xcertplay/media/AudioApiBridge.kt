package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * All API 21+/26+ audio types live here, isolated in their own class.
 *
 * Dalvik verifies a method on first execution and resolves every class its body mentions; an
 * AudioAttributes/AudioFocusRequest reference inside a method that runs on API 19 throws
 * NoClassDefFoundError there even when the guarded branch never executes (CS11 sees this in
 * AudioRenderer.createTrack). Every method in this object is invoked only from API 21+/26+ call
 * sites, so on 4.4 the object is never verified and the missing classes never load.
 */
internal object AudioApiBridge {
    fun attributesFor(selection: AudioChannelSelection, streamOverride: Int): Any {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. The head-unit audio policy
            // can map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (_: Exception) {
                // Keep usage routing when the ROM rejects the legacy stream.
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    fun attributesFor(selection: AudioChannelSelection): Any =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /** AudioTrack's attributes getter is only available from Android 10. */
    fun attributesForFocus(track: AudioTrack, configured: Any): Any =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

    fun usageLabel(attributes: Any?): String {
        val resolved = attributes as? AudioAttributes ?: return "usage=n/a contentType=n/a"
        return "usage=${resolved.usage} contentType=${resolved.contentType}"
    }

    fun builderTrack(
        attributes: Any,
        sampleRate: Int,
        encoding: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes as AudioAttributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()

    fun buildFocusRequestApi26(
        gain: Int,
        attributes: Any,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): Any = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(attributes as AudioAttributes)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()

    fun requestFocusApi26(manager: AudioManager, request: Any): Int =
        manager.requestAudioFocus(request as AudioFocusRequest)

    fun abandonFocusApi26(manager: AudioManager, request: Any) {
        manager.abandonAudioFocusRequest(request as AudioFocusRequest)
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }
}
