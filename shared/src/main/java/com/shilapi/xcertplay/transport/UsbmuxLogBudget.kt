package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many per-frame USBMUX lines may reach logcat.
 *
 * One line per frame filled logcat within a minute of a wired session (upstream #634), so the first
 * [limit] pure ACKs and large writes are logged and the rest are dropped. SYN/FIN/RST are always
 * logged: a late reset or close is what explains a failed session (upstream `2cce3825`/`8e739c52`).
 */
internal class UsbmuxLogBudget(private val limit: Int = DEFAULT_LIMIT) {
    private val controlFrames = AtomicInteger()
    private val largeWrites = AtomicInteger()

    /** Pure ACKs count against the budget; control flags bypass it. */
    fun controlFrame(flags: Int): Boolean =
        (flags and SYN_FIN_RST) != 0 || controlFrames.incrementAndGet() <= limit

    fun largeWrite(): Boolean = largeWrites.incrementAndGet() <= limit

    internal companion object {
        const val DEFAULT_LIMIT = 50
        const val SYN_FIN_RST = 0x07 // FIN | SYN | RST
    }
}
