package com.shilapi.xcertplay.media

import java.io.Closeable

/** Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone uplink. */
internal interface MicrophoneOpusEncoder : Closeable {
    /** Short implementation name for diagnostic reports. */
    val implementation: String

    val available: Boolean

    /** Encodes one 20 ms little-endian 16-bit PCM frame and returns the Opus access units it produced. */
    fun encode(pcm: ByteArray): List<ByteArray>
}

internal object MicrophoneOpusEncoders {
    /**
     * Picks the microphone Opus encoder for this head unit, most capable first: Android's MediaCodec
     * encoder (API 29 and later), then the bundled libopus native encoder, then the vendored pure-Java
     * Concentus encoder. Every one of them keeps the same wire format, so the advertised CarPlay
     * formats do not change: what changes is only which implementation produces the packets.
     *
     * A factory may hand back an encoder that turned out to be unusable — `OpusEncoder` does exactly
     * that on a head unit whose MediaCodec lists Opus but refuses to configure it — so the [available]
     * flag is what decides, not the null-ness of the factory result.
     *
     * Wireless CarPlay sends calls and Siri as Opus and offers no PCM fallback that iOS accepts, so
     * "no encoder at all" means a silent uplink. Null is still returned when nothing is usable, and
     * then MicrophoneUplink fails at stage=ENCODER rather than sending packets the phone cannot read.
     */
    fun create(
        bitrate: Int,
        platform: (Int) -> MicrophoneOpusEncoder? = { OpusEncoder(it) },
        software: (Int) -> MicrophoneOpusEncoder? = { SoftwareOpusEncoder(it) },
    ): MicrophoneOpusEncoder? = usable(platform(bitrate)) ?: usable(software(bitrate))

    /** Keeps the encoder when it can encode, and releases it when it cannot. */
    private fun usable(encoder: MicrophoneOpusEncoder?): MicrophoneOpusEncoder? {
        if (encoder == null) return null
        if (encoder.available) return encoder
        runCatching { encoder.close() }
        return null
    }
}
