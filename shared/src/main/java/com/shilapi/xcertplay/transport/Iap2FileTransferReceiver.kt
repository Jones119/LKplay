package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream

/** A completed iAP2 v2 NowPlayingArtworkData transfer. */
data class Iap2ArtworkTransfer(val id: Int, val bytes: ByteArray)

/**
 * Bounded receiver for iAP2 file-transfer session datagrams.
 *
 * Datagram byte 0 is the transfer id. Byte 1 contains First/Last flags and a low-nibble opcode:
 * Data(0), Start(1), Cancel(2), Setup(4), or Success(5). Version-2 Setup carries an eight-byte
 * big-endian size followed by a two-byte file type. Only NowPlayingArtworkData is accepted.
 *
 * File transfers carry their own quota so a burst of large transfers can never crowd out the
 * session-10 control traffic: [maximumPendingTransfers] and [maximumPendingTransferBytes] are
 * upstream's own file-transfer limits (32 queued payloads and 256 KiB of queued payload bytes,
 * versus 64 / 1 MiB for control), and an over-quota setup is *dropped*, as upstream does, rather
 * than answered or turned into a link failure.  This receiver has no payload queue - the link
 * worker hands every session-12 datagram straight to [accept] - so the bounded resource here is
 * the reassembly state of transfers that are still incomplete.
 *
 * One deviation is deliberate: upstream only ever held whole datagrams, while this receiver holds
 * a whole file, so a lone pending transfer is exempt from the byte budget and is bounded by
 * [maximumArtworkBytes] alone.  Applying 256 KiB to a single artwork would drop every cover larger
 * than 256 KiB, which this pipeline can carry today (the downstream artwork queue budgets 8 MiB of
 * compressed covers).  A second concurrent transfer, however, must fit the 256 KiB budget, so at
 * most one oversized transfer can exist at a time and the remaining backlog stays at upstream's
 * limit.
 */
class Iap2FileTransferReceiver(
    private val maximumArtworkBytes: Int = DEFAULT_MAXIMUM_ARTWORK_BYTES,
    private val maximumPendingTransfers: Int = DEFAULT_MAXIMUM_PENDING_TRANSFERS,
    private val maximumPendingTransferBytes: Int = DEFAULT_MAXIMUM_PENDING_TRANSFER_BYTES,
) {
    data class Outcome(
        val replies: List<ByteArray> = emptyList(),
        val completed: Iap2ArtworkTransfer? = null,
    )

    private data class Pending(
        val expectedBytes: Int,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(expectedBytes),
    )

    private val pending = LinkedHashMap<Int, Pending>()

    init {
        require(maximumArtworkBytes > 0)
        require(maximumPendingTransfers > 0)
        require(maximumPendingTransferBytes > 0)
    }

    @Synchronized
    fun accept(datagram: ByteArray): Outcome {
        if (datagram.size < HEADER_BYTES) return Outcome()
        val id = datagram[0].toInt() and 0xff
        val flags = datagram[1].toInt() and 0xff
        return when (flags and OPCODE_MASK) {
            OPCODE_SETUP -> setup(id, datagram)
            OPCODE_DATA -> data(id, flags, datagram)
            OPCODE_CANCEL -> {
                if (pending.remove(id) == null) return Outcome()
                Outcome(completed = Iap2ArtworkTransfer(id, ByteArray(0)))
            }
            else -> Outcome()
        }
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun setup(id: Int, datagram: ByteArray): Outcome {
        pending.remove(id)
        if (datagram.size < VERSION_2_SETUP_BYTES) return Outcome(replies = listOf(cancel(id)))
        val size = readU64(datagram, SIZE_OFFSET)
        val type = readU16Be(datagram, FILE_TYPE_OFFSET)
        if (type != NOW_PLAYING_ARTWORK_TYPE) return Outcome(replies = listOf(cancel(id)))
        if (size < 0 || size > maximumArtworkBytes) return rejected(id)
        if (size == 0L) {
            return Outcome(
                replies = listOf(start(id), success(id)),
                completed = Iap2ArtworkTransfer(id, ByteArray(0)),
            )
        }
        if (!admitsTransfer(size.toInt())) {
            // Upstream flushed its whole file-transfer queue when the new payload did not fit and
            // dropped that payload too, without replying and without failing the link; abandoning
            // every incomplete transfer reproduces that. Sender cancels, aborts and clear() release
            // the same state, so the quota is recovered as soon as a transfer stops being pending.
            pending.clear()
            return Outcome()
        }
        pending[id] = Pending(size.toInt())
        return Outcome(replies = listOf(start(id)))
    }

    /**
     * Upstream admitted a file payload only while fewer than 32 payloads and at most 256 KiB of
     * them were queued. A lone transfer is exempt from the byte budget because a whole file is
     * reassembled here where upstream queued single datagrams; see the class documentation.
     */
    private fun admitsTransfer(size: Int): Boolean {
        if (pending.size >= maximumPendingTransfers) return false
        if (pending.isEmpty()) return true
        var queuedBytes = 0
        for (transfer in pending.values) queuedBytes += transfer.expectedBytes
        return size <= maximumPendingTransferBytes - queuedBytes
    }

    private fun data(id: Int, flags: Int, datagram: ByteArray): Outcome {
        val transfer = pending[id] ?: return Outcome()
        // Defence in depth: the entry point already refuses a frame shorter than the header, but
        // `count` below is derived by subtraction and a future caller must not be able to hand
        // `write` a negative length (which is a silent no-op in the userspace stream and an
        // exception on the kernel one).
        if (datagram.size < HEADER_BYTES) {
            pending.remove(id)
            return rejected(id)
        }
        val count = datagram.size - HEADER_BYTES
        if (count > transfer.expectedBytes - transfer.output.size()) {
            pending.remove(id)
            return rejected(id)
        }
        transfer.output.write(datagram, HEADER_BYTES, count)
        val last = flags and FLAG_LAST != 0
        if (!last && transfer.output.size() < transfer.expectedBytes) return Outcome()
        pending.remove(id)
        if (transfer.output.size() != transfer.expectedBytes) {
            return rejected(id)
        }
        return Outcome(
            replies = listOf(success(id)),
            completed = Iap2ArtworkTransfer(id, transfer.output.toByteArray()),
        )
    }

    // An empty completion clears the art, so a refused cover does not leave the previous song's.
    private fun rejected(id: Int) = Outcome(replies = listOf(cancel(id)), completed = Iap2ArtworkTransfer(id, ByteArray(0)))

    private fun start(id: Int) = byteArrayOf(id.toByte(), OPCODE_START.toByte())

    private fun cancel(id: Int) = byteArrayOf(id.toByte(), OPCODE_CANCEL.toByte())

    private fun success(id: Int) = byteArrayOf(id.toByte(), OPCODE_SUCCESS.toByte())

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) {
            if (value < 0) return -1
            value = (value shl 8) or (bytes[offset + it].toLong() and 0xff)
        }
        return value
    }

    companion object {
        private const val HEADER_BYTES = 2
        private const val SIZE_OFFSET = 2
        private const val FILE_TYPE_OFFSET = 10
        private const val VERSION_2_SETUP_BYTES = 12
        private const val OPCODE_MASK = 0x0f
        private const val FLAG_LAST = 0x40
        private const val OPCODE_DATA = 0
        private const val OPCODE_START = 1
        private const val OPCODE_CANCEL = 2
        private const val OPCODE_SETUP = 4
        private const val OPCODE_SUCCESS = 5
        private const val NOW_PLAYING_ARTWORK_TYPE = 2

        /** Upstream's file-transfer quota: 32 queued files, against 64 queued control payloads. */
        const val DEFAULT_MAXIMUM_PENDING_TRANSFERS = 32

        /** Upstream's file-transfer quota: 256 KiB of queued file bytes, against 1 MiB of control. */
        const val DEFAULT_MAXIMUM_PENDING_TRANSFER_BYTES = 256 * 1024
        const val DEFAULT_MAXIMUM_ARTWORK_BYTES = 2 * 1024 * 1024
    }
}
