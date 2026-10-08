package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.compat.recorderRouteTypeCompat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // The car keeps its own always-on voice assistant on the microphone, and this 4.4 audio HAL
        // answers a competing capture with ERROR_INVALID_OPERATION (-3) on the first read. Walk the
        // source ladder and keep the first source that actually returns samples.
        val probe = ByteArray(minOf(config.frameBytes, MIN_READ_BYTES))
        var chosen: AudioRecord? = null
        for (candidate in sourceLadder(source)) {
            val candidateRecorder = try {
                if (Build.VERSION.SDK_INT >= 23) {
                    AudioRecord.Builder()
                        .setAudioSource(candidate)
                        .setAudioFormat(
                            AndroidAudioFormat.Builder()
                                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(config.sampleRate)
                                .setChannelMask(channelMask)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    // CS11: AudioRecord.Builder 是 API23+；4.4 走 legacy 构造
                    @Suppress("DEPRECATION")
                    AudioRecord(candidate, config.sampleRate, channelMask,
                        AndroidAudioFormat.ENCODING_PCM_16BIT, bufferSize)
                }
            } catch (error: Exception) {
                Log.e(TAG, "microphone recorder creation failed source=$candidate", error)
                continue
            }
            if (candidateRecorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "microphone recorder not initialized source=$candidate state=${candidateRecorder.state}")
                candidateRecorder.release()
                continue
            }
            val read = try {
                candidateRecorder.startRecording()
                candidateRecorder.read(probe, 0, probe.size)
            } catch (error: Exception) {
                Log.e(TAG, "microphone probe failed source=$candidate", error)
                -1
            }
            Log.i(TAG, "microphone probe source=$candidate rate=${config.sampleRate} result=$read")
            if (read >= 0) {
                chosen = candidateRecorder
                break
            }
            runCatching { candidateRecorder.stop() }
            candidateRecorder.release()
        }
        val nextRecorder = chosen
        if (nextRecorder == null) {
            Log.w(TAG, "microphone unavailable on every source rate=${config.sampleRate}")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION)
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(serverBindAddress(), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") effects = voiceEffects(nextRecorder.audioSessionId)
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    /** Highest absolute 16-bit sample of one PCM frame; 0 means the capture is silent. */
    private fun peakOf(frame: ByteArray): Int {
        var peak = 0
        var index = 0
        while (index + 1 < frame.size) {
            val low = frame[index].toInt() and 0xFF
            val high = frame[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return peak
    }

    private fun sourceLadder(primary: Int): List<Int> = listOf(
        primary,
        // The CS11 only opens the microphone through VOICE_COMMUNICATION: VOICE_RECOGNITION, MIC and
        // DEFAULT all answer the probe with -3 there, which is why Siri (speechrecognition) fell back
        // to the iPhone's own microphone while a call — which already uses VOICE_COMMUNICATION — sent
        // the car's microphone fine.
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    ).distinct()

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var filled = 0
        var levelPeak = 0
        var levelFrames = 0
        try {
            while (running.get()) {
                stats.reading()
                // CS11: read(…,mode) 是 API23+；4.4 的 3 参 read 即阻塞
                val count = if (Build.VERSION.SDK_INT >= 23) {
                    recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    recorder.read(readBuffer, 0, readBuffer.size)
                }
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        // A silent capture (routing or effect chain) otherwise looks identical to a
                        // working one in the byte counters, so report the level once per second.
                        levelPeak = maxOf(levelPeak, peakOf(frame))
                        if (++levelFrames >= LEVEL_FRAMES_PER_LOG) {
                            Log.i(TAG, "microphone level peak=$levelPeak frames=$levelFrames")
                            levelPeak = 0
                            levelFrames = 0
                        }
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorderRouteTypeCompat(recorder) }.getOrNull()

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        /** 20 ms frames per level report (one second at 48 kHz). */
        const val LEVEL_FRAMES_PER_LOG = 50
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
