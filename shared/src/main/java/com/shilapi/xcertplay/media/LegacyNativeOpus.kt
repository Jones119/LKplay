package com.shilapi.xcertplay.media

import java.io.Closeable

/**
 * Native Opus (libopus 1.x) for the CS11.
 *
 * This head unit ships no Opus codec in MediaCodec, and wireless CarPlay cannot run without Opus:
 * the microphone uplink must be encoded and the telephony/speechRecognition downlink decoded as
 * Opus, otherwise iOS either drops the microphone (uplink dies at the encoder) or refuses to route
 * any audio to CarPlay at all. The JNI entry points in `libdiplay_opus.so` are bound to **this exact
 * class and method names**, so neither may be renamed. The library is BSD-licensed (see
 * `docs/Opus-LICENSE.txt`).
 */
internal object LegacyNativeOpus {
    private val loaded: Boolean = try {
        System.loadLibrary("diplay_opus")
        true
    } catch (_: Throwable) {
        false
    }

    /** True when the shared library is present for this ABI and reports a version. */
    val available: Boolean
        get() = loaded && runCatching { versionNative() }.getOrNull()?.isNotEmpty() == true

    @JvmStatic
    private external fun versionNative(): String

    @JvmStatic
    private external fun createNative(rate: Int, channels: Int, encoder: Boolean, bitrate: Int, complexity: Int): Long

    @JvmStatic
    private external fun encodeNative(handle: Long, pcm: ShortArray, frameSamples: Int, output: ByteArray): Int

    @JvmStatic
    private external fun decodeNative(
        handle: Long,
        packet: ByteArray,
        offset: Int,
        length: Int,
        output: ShortArray,
        maxFrameSamples: Int,
        decodeFec: Boolean,
    ): Int

    @JvmStatic
    private external fun closeNative(handle: Long)

    /** 48 kHz mono Opus encoder for CarPlay's 20 ms (960 sample) microphone frames. */
    class Encoder(bitrate: Int, complexity: Int = DEFAULT_COMPLEXITY) : Closeable {
        private val handle = if (available) createNative(SAMPLE_RATE, CHANNELS, true, bitrate, complexity) else 0L
        private var shortBuffer = ShortArray(0)
        private val output = ByteArray(MAX_PACKET_BYTES)
        private var closed = false

        val isAvailable: Boolean get() = handle != 0L && !closed

        /** Encodes one 16-bit little-endian PCM frame; returns the Opus packet, or null. */
        fun encode(pcm: ByteArray): ByteArray? {
            if (!isAvailable) return null
            val samples = pcm.size / 2
            if (samples == 0) return null
            if (shortBuffer.size < samples) shortBuffer = ShortArray(samples)
            for (index in 0 until samples) {
                val low = pcm[index * 2].toInt() and 0xFF
                val high = pcm[index * 2 + 1].toInt()
                shortBuffer[index] = ((high shl 8) or low).toShort()
            }
            val length = runCatching { encodeNative(handle, shortBuffer, samples, output) }.getOrDefault(-1)
            return if (length > 0) output.copyOf(length) else null
        }

        override fun close() {
            if (closed) return
            closed = true
            if (handle != 0L) runCatching { closeNative(handle) }
        }
    }

    /** 48 kHz mono Opus decoder for the call/Siri downlink. */
    class Decoder : Closeable {
        private val handle = if (available) createNative(SAMPLE_RATE, CHANNELS, false, 0, 0) else 0L
        private var shortBuffer = ShortArray(0)
        private var closed = false

        val isAvailable: Boolean get() = handle != 0L && !closed

        /** Decodes one Opus packet into 16-bit little-endian PCM, or null when it cannot. */
        fun decode(packet: ByteArray, offset: Int, length: Int): ByteArray? {
            if (!isAvailable || length <= 0) return null
            if (shortBuffer.size < MAX_FRAME_SAMPLES) shortBuffer = ShortArray(MAX_FRAME_SAMPLES)
            val samples = runCatching {
                decodeNative(handle, packet, offset, length, shortBuffer, MAX_FRAME_SAMPLES, false)
            }.getOrDefault(-1)
            if (samples <= 0) return null
            val pcm = ByteArray(samples * 2)
            for (index in 0 until samples) {
                val value = shortBuffer[index].toInt()
                pcm[index * 2] = (value and 0xFF).toByte()
                pcm[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            return pcm
        }

        override fun close() {
            if (closed) return
            closed = true
            if (handle != 0L) runCatching { closeNative(handle) }
        }
    }

    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 1
    private const val MAX_FRAME_SAMPLES = 5_760 // 120 ms at 48 kHz, Opus' maximum frame size
    private const val MAX_PACKET_BYTES = 65_535
    private const val DEFAULT_COMPLEXITY = 5
}

/**
 * `adb shell setprop debug.diplay.software_opus 1` forces both directions onto libopus, so the
 * bundled codec can be exercised on a device whose MediaCodec does have Opus (the tablet bench).
 * Read reflectively because android.os.SystemProperties is not part of the public SDK.
 */
internal fun softwareOpusForced(): Boolean = runCatching {
    val get = Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    (get.invoke(null, "debug.diplay.software_opus", "0") as? String) == "1"
}.getOrDefault(false)

/**
 * Reads a system property as an on/off switch (`adb shell setprop <name> 0|1`), so behaviour that only
 * the car can settle — the CS11's telephony capture returns silence with the voice effects chain and
 * communication mode attached — can be tried on the head unit without rebuilding. Read reflectively
 * because android.os.SystemProperties is not part of the public SDK.
 */
internal fun debugSwitch(name: String, default: Boolean): Boolean = runCatching {
    val get = Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    ((get.invoke(null, name, if (default) "1" else "0") as? String) ?: "") == "1"
}.getOrDefault(default)
