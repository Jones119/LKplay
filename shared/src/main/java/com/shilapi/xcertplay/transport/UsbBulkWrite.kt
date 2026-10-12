package com.shilapi.xcertplay.transport

/** Outcome of [UsbBulkWrite.all]: how far the pipe actually got and why it stopped. */
internal data class UsbBulkWriteResult(val written: Int, val timedOut: Boolean) {
    fun isComplete(total: Int): Boolean = written == total
}

/**
 * Resumes a short bulk write from the offset the pipe actually reached, under **one** deadline.
 *
 * Android's `UsbDeviceConnection.bulkTransfer` reports how many bytes it transferred, and Linux'
 * usbfs (MUSB on this head unit) can return fewer than requested for a large buffer. The previous
 * code treated any short transfer as a dead device, so one truncated write failed the whole wired
 * link. Retrying from the real offset finishes the frame instead.
 *
 * A single deadline across all attempts matters as much as the retry: computing the timeout per
 * attempt would let a stalled pipe restart its budget every chunk and never time out. The Geely
 * H52 Android 4.3 port documents the same two defects in its `LegacyUsbTransfer`, which is where
 * this behaviour was ported from (see `docs/_digest/55-...`).
 *
 * Pure logic with an injected clock and transfer so the resume/deadline rules are unit-tested.
 */
internal object UsbBulkWrite {
    fun all(
        length: Int,
        timeoutMillis: Int,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        transfer: (offset: Int, length: Int, timeoutMillis: Int) -> Int,
    ): UsbBulkWriteResult {
        require(length > 0) { "length must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = nowMillis() + timeoutMillis
        var offset = 0
        while (offset < length) {
            val remaining = deadline - nowMillis()
            if (remaining <= 0) return UsbBulkWriteResult(offset, timedOut = true)
            val written = transfer(offset, length - offset, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (written <= 0) return UsbBulkWriteResult(offset, timedOut = false)
            offset += written
        }
        return UsbBulkWriteResult(offset, timedOut = false)
    }
}
