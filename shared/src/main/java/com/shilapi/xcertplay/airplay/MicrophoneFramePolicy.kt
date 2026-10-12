package com.shilapi.xcertplay.airplay

/**
 * How many PCM samples one wired microphone packet may carry.
 *
 * The wired microphone is LPCM - the phone only asks for Opus on the wireless link - so a 20 ms frame at
 * 48 kHz mono is 1920 bytes of payload, 1956 bytes once the 12-byte RTP header, the 16-byte Poly1305 tag
 * and the 8-byte nonce trailer are added. That is past the NCM link's 1500-byte MTU, so **every wired
 * microphone packet was an IPv6 fragment**, and the phone recorded silence while its own PCM downlink
 * frames arrive at 756 bytes (`Receive: video`/`wireBytes=756` on the car). Wireless kept working because
 * it negotiates Opus, whose frames are two orders of magnitude smaller.
 *
 * Measured on the car 2026-10-13 (docs/_digest/58 §10): the microphone was open, the level meter showed a
 * real voice (peak 6742), 245 packets were sent with zero send errors and `usb1_tx` grew accordingly - all
 * of which pointed away from capture and at the wire format itself.
 *
 * The cap below leaves room for the IPv6 and UDP headers, so 48 kHz mono sends 12 ms (1188 bytes on the
 * wire) and 48 kHz stereo sends 6 ms; anything the phone asks for that already fits is kept as asked.
 */
internal object MicrophoneFramePolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.mic_frame_ms"

    /** RTP header (12) + Poly1305 tag (16) + the repeated nonce (8). */
    const val WIRE_OVERHEAD_BYTES = 36

    /** The largest sealed packet this policy will produce, inside the NCM link's 1500-byte MTU. */
    const val MAX_WIRE_BYTES = 1200

    /** What the phone asks for when it says nothing, and what wireless Opus uses regardless. */
    const val DEFAULT_MILLIS = 20

    /** The longest PCM frame that still fits [MAX_WIRE_BYTES] at this rate and channel count. */
    fun maxFrameMillis(sampleRate: Int, channels: Int): Int {
        val bytesPerSample = (channels.coerceAtLeast(1)) * 2
        val samples = (MAX_WIRE_BYTES - WIRE_OVERHEAD_BYTES) / bytesPerSample
        return (samples * 1000 / sampleRate.coerceAtLeast(1)).coerceAtLeast(1)
    }

    /**
     * The frame length to use: the phone's preference (or [DEFAULT_MILLIS]), capped so the packet fits,
     * and overridable per ignition window with `debug.diplay.mic_frame_ms` (a value in 1..60 ms).
     */
    fun resolve(preferredMillis: Int, sampleRate: Int, channels: Int, overrideMillis: Int): Int {
        val preferred = if (overrideMillis in 1..60) overrideMillis else preferredMillis.coerceAtLeast(1)
        return preferred.coerceIn(1, maxFrameMillis(sampleRate, channels))
    }

    /** `debug.diplay.mic_frame_ms`, or 0 when unset; the ROM has no public property API on API 19. */
    fun currentOverride(): Int = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, PROPERTY) as? String)?.trim()?.toIntOrNull()
    }.getOrNull()?.also { value ->
        if (value in 1..60) android.util.Log.i(TAG, "$PROPERTY override: ${value}ms microphone frames")
    } ?: 0
}
