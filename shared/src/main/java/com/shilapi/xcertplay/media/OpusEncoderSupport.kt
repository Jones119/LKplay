package com.shilapi.xcertplay.media

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * This ROM has no platform Opus codec, so the bundled software encoder is what makes the
 * microphone formats available. Probe it before advertising them, without opening the microphone
 * or changing the audio mode.
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
            platformAvailable = { false },
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
}
