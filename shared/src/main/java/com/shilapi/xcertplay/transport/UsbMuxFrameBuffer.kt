package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/**
 * Incremental framing; callers serialize access with their USBMUX state lock.
 *
 * CS11: the buffer keeps a read cursor instead of slicing itself on every frame. The previous shape
 * (`bytes += transfer` on every USB read, then `bytes.copyOfRange(...)` twice per frame) allocated a
 * fresh copy of the whole backlog on **every** read and again on every frame, which is the wired
 * control channel's largest steady allocation source (docs/_digest/51 §12). Appends now copy into the
 * spare capacity and consumed bytes are reclaimed by compacting in place, so steady-state framing
 * allocates nothing except the one payload array each frame hands to its caller.
 */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    /** Backing store; live bytes are `[start, end)`. */
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = end - start

    /** Backing capacity; pinned by a test so consumed bytes are reclaimed instead of grown per frame. */
    internal val capacityBytes: Int get() = bytes.size

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        reserve(transfer.size)
        transfer.copyInto(bytes, end)
        end += transfer.size
    }

    fun takeFrame(): UsbMuxFrame? {
        if (bufferedBytes < HEADER_BYTES) return null
        var length = readU32(bytes, start + 4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // The captured iOS 27 VERSION and valid RX TCP replies, including payload-bearing
            // replies, have four extra bytes. Do not scan or discard a USB completion:
            // only this single four-byte boundary is eligible, and only before a TCP frame.
            // A normal or split next header at offset zero is always preserved.
            // Wait for the complete candidate MUX header and base TCP header. A timeout
            // leaves these bytes in place; a legitimate split frame must never be lost.
            if (previous != null && readU32(bytes, start) != PROTOCOL_TCP &&
                bufferedBytes < PADDING_BYTES + HEADER_BYTES) return null
            val followingLength = readU32(bytes, start + PADDING_BYTES + 4)
            val candidateHeader = previous != null && readU32(bytes, start) != PROTOCOL_TCP &&
                readU32(bytes, start + PADDING_BYTES) == PROTOCOL_TCP &&
                followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES &&
                readU32(bytes, start + PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            if (candidateHeader && bufferedBytes < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
            val followingTcpHeaderBytes = if (candidateHeader) {
                ((bytes[start + PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
            } else 0
            if (candidateHeader && followingTcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                readU16(bytes, start + PADDING_BYTES + HEADER_BYTES) != 0 &&
                readU16(bytes, start + PADDING_BYTES + HEADER_BYTES + 2) != 0) {
                consume(PADDING_BYTES)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) report(
                    "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                        "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                        "nextProtocol=$PROTOCOL_TCP nextLength=$followingLength " +
                        "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=$bufferedBytes")
                if (bufferedBytes < HEADER_BYTES) return null
                length = readU32(bytes, start + 4)
            } else {
                report("USBMUX framing rejected declaredLength=$length bufferedBytes=$bufferedBytes " +
                    "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bufferedBytes < length) return null
        val frame = UsbMuxFrame(
            protocol = readU32(bytes, start), length = length, word8 = readU32(bytes, start + 8),
            sequence = ((bytes[start + 12].toInt() and 0xff) shl 8) or (bytes[start + 13].toInt() and 0xff),
            payload = bytes.copyOfRange(start + HEADER_BYTES, start + length),
        )
        consume(length)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    /** Drops [count] consumed bytes, compacting the live window to the front when it has to grow. */
    private fun consume(count: Int) {
        start += count
        if (start == end) {
            start = 0
            end = 0
        }
    }

    /**
     * Makes room for [extra] more bytes: first by reclaiming the consumed prefix in place, then by
     * growing. The live window is never dropped, so a frame split across reads is preserved.
     */
    private fun reserve(extra: Int) {
        if (end + extra <= bytes.size) return
        val live = bufferedBytes
        if (live + extra <= bytes.size) {
            if (start > 0) {
                bytes.copyInto(bytes, 0, start, end)
                start = 0
                end = live
            }
            return
        }
        var capacity = bytes.size.coerceAtLeast(HEADER_BYTES)
        while (capacity < live + extra) capacity *= 2
        val grown = ByteArray(capacity)
        bytes.copyInto(grown, 0, start, end)
        bytes = grown
        start = 0
        end = live
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean {
        if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == 2) return true
        if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
            frame.payload.size < TCP_HEADER_BYTES) return false
        val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
        return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
            readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
    }

    private fun report(line: String) { runCatching { diagnostic(line) } }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        /** One USB read is at most 16 KiB (docs/_digest/44 §1.7), so this holds a read plus a frame. */
        const val INITIAL_CAPACITY = 64 * 1024
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_TCP = 6
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}
