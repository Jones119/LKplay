package com.shilapi.xcertplay.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * One-shot check of whether the head unit's microphone can be opened right now.
 *
 * The CS11 answers a competing capture with `ERROR_INVALID_OPERATION` (-3) while its own voice
 * assistant holds the microphone, which is why CarPlay voice input and Siri silently fail there
 * (docs/_digest/32 §①). This probe walks the same source ladder [MicrophoneUplink] uses and reports
 * whether any source returns samples, so the settings screen can tell the driver instead of leaving
 * them with a microphone that looks connected but captures nothing.
 *
 * It opens and closes the recorder immediately, so it must only be called while no CarPlay
 * microphone session is active.
 */
internal object MicrophoneAvailability {
    private const val TAG = "xcertplay-usb"
    private const val SAMPLE_RATE = 48_000
    private const val PROBE_BYTES = 1_920

    enum class Status { AVAILABLE, BLOCKED, UNKNOWN }

    fun probe(): Status {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(-1)
        if (minBuffer <= 0) {
            Log.i(TAG, "microphone probe unavailable minBuffer=$minBuffer")
            return Status.UNKNOWN
        }
        val bufferSize = maxOf(minBuffer * 2, PROBE_BYTES * 4)
        val buffer = ByteArray(PROBE_BYTES)
        var opened = false
        for (source in SOURCE_LADDER) {
            val recorder = runCatching { create(source, channelMask, bufferSize) }.getOrNull() ?: continue
            val read = try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Int.MIN_VALUE
                } else {
                    recorder.startRecording()
                    opened = true
                    recorder.read(buffer, 0, buffer.size)
                }
            } catch (error: Exception) {
                Log.w(TAG, "microphone probe failed source=$source", error)
                Int.MIN_VALUE
            } finally {
                runCatching { if (opened) recorder.stop() }
                runCatching { recorder.release() }
                opened = false
            }
            Log.i(TAG, "microphone probe source=$source result=$read")
            when {
                read >= 0 -> return Status.AVAILABLE
                read == Int.MIN_VALUE -> Unit
                else -> Unit
            }
        }
        Log.i(TAG, "microphone probe blocked on every source")
        return Status.BLOCKED
    }

    @Suppress("DEPRECATION")
    private fun create(source: Int, channelMask: Int, bufferSize: Int): AudioRecord =
        if (Build.VERSION.SDK_INT >= 23) {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            // CS11: AudioRecord.Builder 是 API23+；4.4 走 legacy 构造
            AudioRecord(source, SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }

    /** Same order as [MicrophoneUplink.sourceLadder]: the voice sources first, then the plain ones. */
    private val SOURCE_LADDER = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    )
}
