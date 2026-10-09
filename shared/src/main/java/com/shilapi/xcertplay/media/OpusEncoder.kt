package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.shilapi.xcertplay.compat.inputBufferCompat
import com.shilapi.xcertplay.compat.outputBufferCompat

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone
 * uplink, using Android's MediaCodec encoder (Android 10 and later) when the platform has one and
 * the bundled libopus otherwise.
 *
 * This class is the "platform" implementation [MicrophoneOpusEncoders] tries first; the vendored
 * pure-Java Concentus encoder behind [SoftwareOpusEncoder] covers a head unit that has neither. It
 * is named after the wire format it produces rather than one backend, so both backends stay in this
 * one place instead of a second copy of the MediaCodec buffer handling.
 */
internal class OpusEncoder(bitrate: Int) : MicrophoneOpusEncoder {
    private val codec: MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
            it.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            it.start()
            Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
        }
    } catch (error: Exception) {
        Log.w(TAG, "Opus microphone encoder unavailable", error)
        null
    }
    // The CS11 has no Opus encoder in MediaCodec, and wireless CarPlay still requires Opus on the
    // microphone, so the bundled libopus encodes the uplink whenever the platform codec is missing.
    private var native: LegacyNativeOpus.Encoder? =
        if ((codec == null || softwareOpusForced()) && LegacyNativeOpus.available) {
            LegacyNativeOpus.Encoder(bitrate).takeIf { it.isAvailable }
        } else null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    init {
        if (native != null) Log.i(TAG, "Opus microphone encoder started on libopus bitrate=$bitrate")
    }

    /**
     * Which backend actually produced the packets, for the diagnostic report. "mediacodec" and
     * "libopus" are both this class; the pure-Java fallback reports "software" from
     * [SoftwareOpusEncoder].
     */
    override val implementation: String = when {
        native != null -> "libopus"
        codec != null -> "mediacodec"
        else -> "none"
    }

    override val available: Boolean get() = !closed && (codec != null || native?.isAvailable == true)

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    override fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed) return emptyList()
        native?.let { encoder ->
            val packet = encoder.encode(pcm)
            outputPackets++
            if (packet != null && outputPackets <= FIRST_PACKET_LOG_COUNT) {
                Log.i(TAG, "libopus microphone packet=$outputPackets bytes=${packet.size}")
            }
            return if (packet != null) listOf(packet) else emptyList()
        }
        val codec = codec ?: return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            val input = codec.inputBufferCompat(inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.outputBufferCompat(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size} " +
                                    "head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        native?.close()
        native = null
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
    }
}

// CS11: MediaCodec.getInputBuffer/getOutputBuffer 是 API21+；4.4 上走 legacy 缓冲区数组。
// 复用 compat 层的同一实现，避免两份逻辑各自漂移（见 com.shilapi.xcertplay.compat.inputBufferCompat）。

