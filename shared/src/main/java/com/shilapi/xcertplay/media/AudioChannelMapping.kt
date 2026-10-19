package com.shilapi.xcertplay.media

import android.media.AudioManager

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    val streamType: Int = AudioManager.STREAM_MUSIC,
)

/**
 * Maps CarPlay stream metadata to Android audio routing values.
 *
 * Each selection carries the [AudioChannel] / [AudioContentType] pair used for usage-based
 * AudioAttributes, plus the stream type used when the stream-type AudioTrack path is active.
 * Guidance resolves to [AudioChannel.NAVIGATION]. Automotive routing keeps compatibility
 * audio on the media bus, so it receives the configured media buffer and output route.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
    // CS11: the vendor ROM exposes STREAM_NAVI_TTS as stream 12 and gives it its own volume UI, so
    // guidance belongs there instead of AOSP stream 14 (which this ROM maps to nothing usable and
    // made the car show media volume while a prompt played).
    const val DEFAULT_NAVIGATION_STREAM_TYPE = 12

    /**
     * CS11: the ROM's 30-stream table gives stream 0 its own call volume and arbitration that wins over
     * music and navigation, and HYLink hard-codes it on this same ROM family. Without an explicit stream
     * the telephony selection fell back to [AudioManager.STREAM_MUSIC], so a CarPlay call played on the
     * media bus and the wheel's volume key showed the media slider (docs/_digest/68 §5.4).
     */
    const val DEFAULT_PHONE_STREAM_TYPE = AudioManager.STREAM_VOICE_CALL

    /**
     * [assistantStreamType] follows guidance: Siri's downlink is a prompt-like stream that mixes over
     * music, and leaving it unset had put it on the media stream as well (docs/_digest/68 §5.3).
     */
    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
        assistantStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
        phoneStreamType: Int = DEFAULT_PHONE_STREAM_TYPE,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE ->
                mapMobileCompatible(normalized, payloadType, navigationStreamType, assistantStreamType, phoneStreamType)
            AudioChannelMappingMode.AUTOMOTIVE_BUS ->
                mapAutomotiveBus(normalized, payloadType, navigationStreamType, assistantStreamType, phoneStreamType)
        }
    }

    fun usesNavigationStream(audioType: String, payloadType: Int, advanced: Boolean): Boolean =
        !advanced && map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.NAVIGATION

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
        assistantStreamType: Int,
        phoneStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH, phoneStreamType)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH, assistantStreamType)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert", "compatibility" ->
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
        assistantStreamType: Int,
        phoneStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH, phoneStreamType)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH, assistantStreamType)
        "media", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mainHighAudioOrNavigation(payloadType: Int, navigationStreamType: Int): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        }
}
