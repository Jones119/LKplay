package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.DuplexServer
import com.shilapi.xcertplay.transport.DuplexSocket
import com.shilapi.xcertplay.transport.KernelSessionNetwork
import com.shilapi.xcertplay.transport.StreamNetwork
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.shilapi.xcertplay.compat.WorkerThreads

/** CS11 / this project decodes H.264 only: the ROM's VPU decodes HEVC in software, so H.265 is not offered. */
enum class VideoCodec { H264 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    /** The listener comes from here so the wired path can run on a userspace stack. */
    private val network: StreamNetwork = KernelSessionNetwork,
    /** Only this peer may deliver frames; null keeps the previous accept-any behaviour. */
    private val expectedPeer: InetAddress? = null,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}

        /**
         * The same frame as [onFrame], but its bytes live in a pooled buffer: the implementation must
         * call [OwnedVideoFrame.close] once it no longer needs them. The default copy keeps
         * byte-array-only listeners working unchanged.
         */
        fun onOwnedFrame(frame: OwnedVideoFrame) {
            try {
                onFrame(frame.copyOfBytes())
            } finally {
                frame.close()
            }
        }

        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)

    /** One report per stream for a short AEAD write, which is a bug rather than a wire condition. */
    private val plaintextMismatchLogged = AtomicBoolean(false)
    private var server: DuplexServer? = null
    private var socket: DuplexSocket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = network.listen(serverBindAddress(), port = 0, reuseAddress = true)
        server = bound
        thread = WorkerThreads.create({
            // Geely H52's Android 4.3 port runs its video receive/decode threads at -8 rather than
            // the -4 used here; the car session A/Bs whether the extra step helps on this SoC
            // (diplay.video_priority below reverts it without a rebuild). Audio stays at -19.
            android.os.Process.setThreadPriority(VideoThreadPriority.current())
            accept(bound)
        }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: DuplexServer) {
        try {
            val accepted = bound.accept()
            val peer = accepted.remoteSocketAddress?.address
            if (expectedPeer != null && peer != expectedPeer) {
                // The data port is reachable before pairing; only the paired phone may stream.
                onDiagnostic("video socket rejected peer=$peer expected=$expectedPeer")
                accepted.close()
                return
            }
            socket = accepted
            // CS11: TCP flow control is our only throttle on the phone's video sender. A big
            // receive window lets it dump multi-megabit bursts into the NCM link, and those
            // bursts are what evict the audio datagrams (Udp6InErrors spikes while scrolling).
            // 64 KiB keeps the pipe full while pacing the sender; VideoReceiveWindow lets a
            // prop override it so one ignition window can compare values (see its KDoc).
            val windowBytes = VideoReceiveWindow.current()
            runCatching { accepted.receiveBufferSize = windowBytes }
            runCatching { accepted.tcpNoDelay = true }
            onDiagnostic(
                "video socket accepted rcvbuf=${runCatching { accepted.receiveBufferSize }.getOrDefault(0)}" +
                    " requested=$windowBytes",
            )
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: DuplexSocket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        // CS11: one receive thread, so one header buffer and one body buffer are reused; the plaintext
        // goes into a pooled frame that the decoder thread releases after feeding the codec
        // (see docs/_digest/41 §9 - this replaced two multi-kilobyte allocations per frame).
        val frames = VideoFramePool()
        try {
            val input = sock.inputStream
            val header = ByteArray(HEADER_LEN)
            var body = ByteArray(0)
            while (!closed.get()) {
                stats.reading()
                if (!readFully(input, header, 0, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val opcode = header[OPCODE_OFFSET].toInt() and 0xff
                if (opcode == OP_VIDEO_FRAME && bodySize >= ScreenCodec.TAG_SIZE) {
                    // CS11: read the sealed frame straight into the pooled buffer and open it in place.
                    // The old path read into the reused `body` array and then decrypted into a second
                    // buffer, i.e. two 1.3 MB copies per frame at 720p; this keeps one.
                    val frame = frames.acquire(bodySize)
                    if (!readFully(input, frame.bytes, 0, bodySize)) {
                        frame.close()
                        break
                    }
                    frame.length = bodySize - ScreenCodec.TAG_SIZE
                    stats.received(HEADER_LEN + bodySize)
                    onSealedFrame(header, frame)
                    stats.processed()
                } else {
                    if (body.size < bodySize) body = ByteArray(bodySize)
                    if (!readFully(input, body, 0, bodySize)) break
                    stats.received(HEADER_LEN + bodySize)
                    onMessage(header, body, bodySize, frames)
                    stats.processed()
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            frames.close()
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    /**
     * Opens a sealed frame that was read straight into [frame]'s buffer, in place.
     *
     * The ciphertext occupies `[0, frame.length + TAG_SIZE)` and the plaintext is written back over
     * it: the audio path already decrypts in place in its reused wire buffer, so the same overlap is
     * supported by both the native and the BouncyCastle backend.
     */
    private fun onSealedFrame(header: ByteArray, frame: OwnedVideoFrame) {
        val sealedLength = frame.length + ScreenCodec.TAG_SIZE
        val plaintextLength = try {
            AirPlayCrypto.chachaOpenInto(
                key = key,
                nonce = AirPlayCrypto.nonce64(frameCounter.get()),
                ciphertextAndTag = frame.bytes,
                ciphertextOffset = 0,
                ciphertextLength = sealedLength,
                aad = header,
                aadOffset = 0,
                aadLength = HEADER_LEN,
                output = frame.bytes,
                outputOffset = 0,
            )
        } catch (error: Throwable) {
            frame.close()
            throw error
        }
        // Every nonce belongs to the sender's sequence, so the counter advances even when the check
        // below rejects the frame.
        frameCounter.incrementAndGet()
        if (plaintextLength != frame.length) {
            // A short write would leave the tail of this recycled buffer holding bytes from the frame
            // that used it last, and the decoder would read them as this frame. Drop the frame and
            // report once so a flood of frames cannot flood the log.
            frame.close()
            if (plaintextMismatchLogged.compareAndSet(false, true)) {
                onDiagnostic(
                    "video frame plaintext mismatch expected=${frame.length} " +
                        "actual=$plaintextLength sealed=$sealedLength",
                )
            }
            return
        }
        if (firstFrameLogged.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "video first decrypted frame sealed=$sealedLength plain=${frame.length} " +
                    "head=${frame.bytes.hexPrefix(minOf(16, frame.length))}",
            )
        }
        ScreenCodec.lengthPrefixedToAnnexB(frame.bytes, frame.length)
        try {
            listener.onOwnedFrame(frame)
        } catch (error: Throwable) {
            // The listener never took ownership, so return the buffer before propagating.
            frame.close()
            throw error
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, bodySize: Int, frames: VideoFramePool) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                if (bodySize < ScreenCodec.TAG_SIZE) {
                    // A clear (unsealed) frame; rare. The reused body buffer must not escape, so copy.
                    listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(body.copyOf(bodySize)))
                    return
                }
                val frame = frames.acquire(bodySize - ScreenCodec.TAG_SIZE)
                val plaintextLength = try {
                    AirPlayCrypto.chachaOpenInto(
                        key = key,
                        nonce = AirPlayCrypto.nonce64(frameCounter.get()),
                        ciphertextAndTag = body,
                        ciphertextOffset = 0,
                        ciphertextLength = bodySize,
                        aad = header,
                        aadOffset = 0,
                        aadLength = HEADER_LEN,
                        output = frame.bytes,
                        outputOffset = 0,
                    )
                } catch (error: Throwable) {
                    frame.close()
                    throw error
                }
                // The frame was acquired for exactly bodySize - TAG_SIZE bytes, and every nonce
                // belongs to the sender's sequence, so the counter advances even when the check
                // below rejects the frame.
                frameCounter.incrementAndGet()
                if (plaintextLength != frame.length) {
                    // A short write would leave the tail of this recycled buffer holding bytes from
                    // the frame that used it last, and the decoder would read them as this frame
                    // (the audio path refuses the same mismatch). Drop the frame instead of decoding
                    // stale video, and report once so a flood of frames cannot flood the log.
                    frame.close()
                    if (plaintextMismatchLogged.compareAndSet(false, true)) {
                        onDiagnostic(
                            "video frame plaintext mismatch expected=${frame.length} " +
                                "actual=$plaintextLength sealed=$bodySize",
                        )
                    }
                    return
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodySize plain=${frame.length} " +
                            "head=${frame.bytes.hexPrefix(minOf(16, frame.length))}",
                    )
                }
                ScreenCodec.lengthPrefixedToAnnexB(frame.bytes, frame.length)
                try {
                    listener.onOwnedFrame(frame)
                } catch (error: Throwable) {
                    // The listener never took ownership, so return the buffer before propagating.
                    frame.close()
                    throw error
                }
            }
            OP_VIDEO_CONFIG -> {
                // The decoder (and any mirrored decoder) retains the config data, so it must not point
                // into the reused body buffer.
                val config = body.copyOf(bodySize)
                val (codec, codecData) = ScreenCodec.detectConfig(config)
                Log.i(TAG, "video codec config codec=$codec body=${config.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    /** Reads exactly [length] bytes into [buffer] at [offset]; false at end of stream. */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 0) return false
        var read = 0
        while (read < length) {
            val count = input.read(buffer, offset + read, length - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray =
        lengthPrefixedToAnnexB(payload, payload.size)

    /**
     * [lengthPrefixedToAnnexB] for a pooled buffer that can be longer than the frame it holds: only
     * the first [length] bytes are inspected and converted.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray, length: Int): ByteArray {
        if (length < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= length) {
            val nalLength = readU32Be(payload, offset)
            offset += 4
            // Bounded subtraction rather than offset + nalLength: a wire length of 0x7FFFFFFF
            // wraps the sum negative, passes this check, and the read below then indexes at a
            // negative offset. Malformed input must leave the payload untouched, as documented.
            if (nalLength <= 0 || nalLength > length - offset) return payload
            offset += nalLength
        }
        if (offset != length) return payload

        offset = 0
        while (offset + 4 <= length) {
            val nalLength = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + nalLength
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            if (String(payload, index, 4, Charsets.US_ASCII) == "avcC") {
                return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return VideoCodec.H264 to payload
    }

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
