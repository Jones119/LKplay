package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2FileTransferReceiverTest {
    @Test
    fun acceptsAndReassemblesNowPlayingArtwork() {
        val receiver = Iap2FileTransferReceiver()

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x01), receiver.accept(setup(0x81, 5)).replies.single())
        assertNull(receiver.accept(data(0x81, 0x80, byteArrayOf(1, 2, 3))).completed)
        val completed = receiver.accept(data(0x81, 0x40, byteArrayOf(4, 5)))

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x05), completed.replies.single())
        assertEquals(0x81, completed.completed?.id)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), completed.completed?.bytes)
    }

    @Test
    fun rejectsOversizedUnsupportedAndTruncatedTransfers() {
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 4)

        val oversized = receiver.accept(setup(1, 5))
        assertArrayEquals(byteArrayOf(1, 2), oversized.replies.single())
        assertEquals(0, oversized.completed?.bytes?.size)
        val unsupported = receiver.accept(setup(2, 3, type = 7))
        assertArrayEquals(byteArrayOf(2, 2), unsupported.replies.single())
        assertNull(unsupported.completed)
        assertNull(receiver.accept(byteArrayOf(4, 4)).completed)
        receiver.accept(setup(3, 4))
        val truncated = receiver.accept(data(3, 0x40, byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(3, 2), truncated.replies.single())
        assertEquals(3, truncated.completed?.id)
        assertEquals(0, truncated.completed?.bytes?.size)
    }

    @Test
    fun aSenderCancelClearsOnlyAPendingArtworkTransfer() {
        val receiver = Iap2FileTransferReceiver()
        receiver.accept(setup(5, 4))

        val cancelled = receiver.accept(data(5, 0x02, ByteArray(0)))
        assertTrue(cancelled.replies.isEmpty())
        assertEquals(5, cancelled.completed?.id)
        assertEquals(0, cancelled.completed?.bytes?.size)
        assertNull(receiver.accept(data(5, 0x02, ByteArray(0))).completed)
    }

    @Test
    fun zeroSizedArtworkClearsTheCurrentImage() {
        val outcome = Iap2FileTransferReceiver().accept(setup(0x80, 0))

        assertEquals(2, outcome.replies.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), outcome.replies[0])
        assertArrayEquals(byteArrayOf(0x80.toByte(), 5), outcome.replies[1])
        assertEquals(0, outcome.completed?.bytes?.size)
    }

    @Test
    fun acceptsUpToTheDefaultPendingTransferQuotaThenDropsTheWholeBacklog() {
        val receiver = Iap2FileTransferReceiver()
        repeat(Iap2FileTransferReceiver.DEFAULT_MAXIMUM_PENDING_TRANSFERS) { id ->
            assertArrayEquals(byteArrayOf(id.toByte(), 1), receiver.accept(setup(id, 4)).replies.single())
        }

        // Upstream dropped the payload that did not fit instead of failing the link, and flushed
        // the file queue with it; the dropped setups must not be answered either.
        val overflowed = receiver.accept(setup(40, 4))
        assertTrue(overflowed.replies.isEmpty())
        assertNull(overflowed.completed)
        assertNull(receiver.accept(data(0, 0x40, byteArrayOf(1, 2, 3, 4))).completed)
        assertTrue(receiver.accept(data(1, 0x40, byteArrayOf(1, 2, 3, 4))).replies.isEmpty())

        // The flushed quota is free again, so the next transfer starts normally.
        assertArrayEquals(byteArrayOf(41, 1), receiver.accept(setup(41, 4)).replies.single())
    }

    @Test
    fun dropsTheBacklogAndTheSetupThatWouldExceedThePendingByteQuota() {
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 64, maximumPendingTransferBytes = 8)

        // A lone transfer is bounded by maximumArtworkBytes, not by the file-transfer byte quota.
        assertArrayEquals(byteArrayOf(1, 1), receiver.accept(setup(1, 6)).replies.single())
        // A second concurrent transfer does not fit the eight-byte budget, so both are dropped.
        val overflowed = receiver.accept(setup(2, 4))
        assertTrue(overflowed.replies.isEmpty())
        assertNull(overflowed.completed)
        assertTrue(receiver.accept(data(1, 0x40, ByteArray(6))).replies.isEmpty())
        assertNull(receiver.accept(data(2, 0x40, ByteArray(4))).completed)
        assertTrue(receiver.accept(data(2, 0x02, ByteArray(0))).replies.isEmpty())

        // The offending setup was dropped as well, so a fresh setup is admitted again.
        assertArrayEquals(byteArrayOf(3, 1), receiver.accept(setup(3, 4)).replies.single())
    }

    @Test
    fun aLoneTransferLargerThanTheByteQuotaStillCompletes() {
        // Guards the deviation documented on the receiver: 256 KiB must not cap a single artwork.
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 32, maximumPendingTransferBytes = 8)
        assertArrayEquals(byteArrayOf(4, 1), receiver.accept(setup(4, 16)).replies.single())

        assertNull(receiver.accept(data(4, 0x80, ByteArray(8))).completed)
        val completed = receiver.accept(data(4, 0x40, ByteArray(8)))

        assertArrayEquals(byteArrayOf(4, 5), completed.replies.single())
        assertEquals(16, completed.completed?.bytes?.size)
    }

    @Test
    fun completingOrAbortingATransferReleasesItsPendingByteQuota() {
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 64, maximumPendingTransferBytes = 12)

        receiver.accept(setup(1, 4))
        receiver.accept(setup(2, 4))
        // Only four of the twelve bytes are free, so a six-byte transfer takes the backlog with it.
        assertTrue(receiver.accept(setup(3, 6)).replies.isEmpty())

        receiver.accept(setup(4, 4))
        receiver.accept(setup(5, 4))
        assertArrayEquals(byteArrayOf(4, 5), receiver.accept(data(4, 0x40, ByteArray(4))).replies.single())
        assertArrayEquals(byteArrayOf(6, 1), receiver.accept(setup(6, 4)).replies.single())

        // A sender cancel releases the bytes just like completion does.
        assertEquals(6, receiver.accept(data(6, 0x02, ByteArray(0))).completed?.id)
        assertArrayEquals(byteArrayOf(7, 1), receiver.accept(setup(7, 4)).replies.single())

        // A setup that replaces its own transfer id, and clear(), release them too.
        assertArrayEquals(byteArrayOf(7, 1), receiver.accept(setup(7, 4)).replies.single())
        receiver.clear()
        assertArrayEquals(byteArrayOf(8, 1), receiver.accept(setup(8, 8)).replies.single())
    }

    private fun setup(id: Int, size: Int, type: Int = 2): ByteArray = ByteArray(12).apply {
        this[0] = id.toByte()
        this[1] = 4
        var remaining = size.toLong()
        for (index in 9 downTo 2) {
            this[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
        this[10] = (type ushr 8).toByte()
        this[11] = type.toByte()
    }

    private fun data(id: Int, flags: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(id.toByte(), flags.toByte()) + bytes
}
