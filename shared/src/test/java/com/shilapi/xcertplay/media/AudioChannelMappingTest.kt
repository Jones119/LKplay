package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CarPlay audio-type to Android routing table (docs/_digest/68 §5).
 *
 * The three CS11 stream choices are asserted explicitly because they are the ones the car's volume
 * arbitration reacts to: stream 0 for a call, 3 for music, 12 for guidance and Siri.
 */
class AudioChannelMappingTest {
    @Test
    fun mobileCompatibleMappingUsesTheCs11Streams() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_PHONE_STREAM_TYPE,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        listOf("default", "alert", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
                streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            )
        }
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        listOf("media", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_PHONE_STREAM_TYPE,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
        )
        listOf("default", "alert").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
                streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            )
        }
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
        )
    }

    @Test
    fun navigationStreamTypeIsOnlyAppliedToGuidanceChannels() {
        val custom = 7
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map("alert", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC, AudioManager.STREAM_MUSIC),
            AudioChannelMapper.map("media", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH, AudioChannelMapper.DEFAULT_PHONE_STREAM_TYPE),
            AudioChannelMapper.map("telephony", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE, custom),
        )
    }

    /** The mapper lets a future per-channel override move the call and assistant streams too. */
    @Test
    fun callAndAssistantStreamsAreConfigurable() {
        val selection = AudioChannelMapper.map(
            audioType = "telephony",
            payloadType = 100,
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationStreamType = 12,
            assistantStreamType = 8,
            phoneStreamType = 1,
        )
        assertEquals(AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH, 1), selection)

        val assistant = AudioChannelMapper.map(
            audioType = "speechRecognition",
            payloadType = 100,
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            navigationStreamType = 12,
            assistantStreamType = 8,
            phoneStreamType = 1,
        )
        assertEquals(AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH, 8), assistant)
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        channel: AudioChannel,
        contentType: AudioContentType,
        streamType: Int = AudioManager.STREAM_MUSIC,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType, streamType),
            AudioChannelMapper.map(audioType, payloadType, mode),
        )
    }
}
