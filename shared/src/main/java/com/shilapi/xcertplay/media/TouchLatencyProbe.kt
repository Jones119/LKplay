package com.shilapi.xcertplay.media

/**
 * Diagnostic link between the touch uplink and the next video frame, read by [VideoStats].
 *
 * Latency is measured from the moment the finger state that the phone acted on was *accepted for
 * sending*, so waiting for the single-threaded event-channel writer is part of touch2frame. Both
 * touch2frame and touchSendMax previously started at the write itself, which hid queueing delay
 * behind a slow event channel.
 */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L

    /** Longest single write to the encrypted event channel in the current stats window. */
    @Volatile var maxSendNs = 0L

    /** Longest wait between accepting a finger state and starting to write it. */
    @Volatile var maxQueueNs = 0L

    /**
     * Records a state that has just been written. The latest written state wins: the next frame the
     * phone renders reflects the newest position we sent, not an earlier one.
     */
    fun onStateSent(enqueuedNs: Long, sentAtNs: Long, sendDurationNs: Long) {
        pendingTouchNs = enqueuedNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
        if (enqueuedNs != 0L) {
            val queueNs = sentAtNs - enqueuedNs
            if (queueNs > maxQueueNs) maxQueueNs = queueNs
        }
    }

    /** Returns finger-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}
