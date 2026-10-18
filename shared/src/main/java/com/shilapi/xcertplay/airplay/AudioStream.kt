package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.DatagramNetwork
import com.shilapi.xcertplay.transport.DatagramTransport
import com.shilapi.xcertplay.transport.KernelDatagramNetwork
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.shilapi.xcertplay.transport.readU16Be
import com.shilapi.xcertplay.transport.readU16Le
import com.shilapi.xcertplay.transport.readU32Be
import com.shilapi.xcertplay.transport.readU32Le
import com.shilapi.xcertplay.transport.writeU16Be
import com.shilapi.xcertplay.transport.writeU32Be
import com.shilapi.xcertplay.compat.WorkerThreads

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    /** The RTP and RTCP endpoints come from here so the wired path can run on a userspace stack. */
    private val network: DatagramNetwork = KernelDatagramNetwork,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramTransport? = null
    private var controlSocket: DatagramTransport? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = network.open(serverBindAddress())
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = network.open(serverBindAddress())
        dataSocket = data
        controlSocket = control
        dataThread = WorkerThreads.create({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runData(data, listener)
        }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = WorkerThreads.create({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramTransport, listener: Listener) {
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        // Reused 12-byte AEAD nonce: four zero bytes followed by the packet's eight-byte tail nonce.
        val nonce = ByteArray(12)
        // CS11: the sealed packet is copied out of the shared datagram buffer only so the next receive
        // cannot overwrite it mid-decrypt, and nothing downstream keeps it - the capture writes it out
        // immediately and the queue takes the decrypted RTP array instead (docs/_digest/51 §12). So one
        // buffer is reused across packets and only resized when a packet arrives with a new length,
        // which on the wired LPCM stream (137 identical packets per second) is once per track.
        var wire = ByteArray(0)
        try {
            while (!closed.get()) {
                val received = try {
                    stats.reading()
                    socket.receive(buffer)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val length = received.size
                stats.received(
                    size = length,
                    sequence = if (length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                if (wire.size != length) wire = ByteArray(length)
                buffer.copyInto(wire, 0, 0, length)
                val packetNumber = receivedPackets.incrementAndGet()
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=${wire.size}",
                        )
                    }
                    listener.onPacket(
                        wire,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                // CS11: decrypt straight into the outgoing packet instead of building a copy of the
                // AAD, the sealed body, the short nonce and the payload for every packet
                // (docs/_digest/41 §9 - EasyPlay's openInto takes the same shape on the audio path).
                val sealedEnd = wire.size - NONCE_LEN
                // The sealed body is ciphertext plus the 16-byte Poly1305 tag, and the AEAD consumes
                // the tag without emitting anything for it, so the plaintext is 16 bytes shorter than
                // the sealed body. Sizing the buffer from the sealed length instead left 16 untouched
                // zero bytes at the end of every access unit (docs/_digest/43); the decoder read those
                // as a second, truncated frame, produced twice the PCM and logged "substituting
                // silence" - which sounded like slow motion, and like artefacts once the surplus was
                // trimmed.
                val plaintextLength = AudioStreamWire.sealedPlaintextLength(wire.size)
                val sample = readU32Be(wire, 4)
                if (plaintextLength < 0) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType packet too short for an AEAD tag " +
                                "wire=${wire.size}",
                        )
                    }
                    listener.onPacket(wire, null, sample, null)
                    stats.processed()
                    continue
                }
                val sealedLength = AudioStreamWire.sealedPayloadLength(wire.size)
                nonce.fill(0, 0, 4)
                wire.copyInto(nonce, 4, sealedEnd, wire.size)
                val rtp = ByteArray(RTP_HEADER_LEN + plaintextLength)
                wire.copyInto(rtp, 0, 0, RTP_HEADER_LEN)

                val payloadBytes = try {
                    AirPlayCrypto.chachaOpenInto(
                        key = key,
                        nonce = nonce,
                        ciphertextAndTag = wire,
                        ciphertextOffset = RTP_HEADER_LEN,
                        ciphertextLength = sealedLength,
                        aad = wire,
                        aadOffset = 4,
                        aadLength = RTP_HEADER_LEN - 4,
                        output = rtp,
                        outputOffset = RTP_HEADER_LEN,
                    )
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                // The buffer is sized for exactly this much plaintext. A different length would mean
                // stale bytes could reach the decoder, which is the bug this check exists to prevent.
                if (payloadBytes != plaintextLength) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType unexpected plaintext length " +
                                "expected=$plaintextLength actual=$payloadBytes",
                        )
                    }
                    listener.onPacket(wire, null, sample, null)
                    stats.processed()
                    continue
                }
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=$payloadBytes " +
                            "payloadHead=" +
                            rtp.copyOfRange(
                                RTP_HEADER_LEN,
                                RTP_HEADER_LEN + minOf(payloadBytes, 16),
                            ).toHexString(),
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                listener.onPacket(wire, rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } finally { stats.flush(ended = true) }
    }

    private fun runControl(socket: DatagramTransport) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(buffer)
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/**
 * Wire arithmetic for one encrypted audio datagram: RTP header, sealed body (ciphertext plus
 * Poly1305 tag), eight-byte nonce.
 *
 * Kept out of [AudioStream]'s companion so the lengths stay directly testable, because getting the
 * tag length wrong is silent: the decrypt buffer is simply left with trailing untouched bytes that
 * the decoder then reads as a second, truncated access unit (docs/_digest/43).
 */
internal object AudioStreamWire {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8

    /** Length of the sealed body (ciphertext plus tag) inside a wire packet. */
    fun sealedPayloadLength(wireBytes: Int): Int = wireBytes - NONCE_LEN - RTP_HEADER_LEN

    /**
     * Plaintext a [wireBytes]-long datagram decrypts to.
     *
     * The AEAD consumes the tag without emitting anything for it, so this is [TAG_LEN] bytes shorter
     * than the sealed body. Negative means the datagram is too short to hold a tag at all.
     */
    fun sealedPlaintextLength(wireBytes: Int): Int = sealedPayloadLength(wireBytes) - TAG_LEN
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
