package com.shilapi.xcertplay.airplay

/**
 * One accepted finger state and when it was accepted, so the uplink can report how long the state
 * waited for the writer as part of the finger-to-frame latency.
 */
internal data class TouchState(
    val contacts: List<AirPlayContact>,
    val enqueuedNs: Long = System.nanoTime(),
)

/**
 * Keeps the touch uplink finger-following while the encrypted event channel is slow.
 *
 * Every report from [AirPlayHid.touchReport] is an absolute snapshot of the current contact state,
 * and the controller sends them through a single-threaded executor with an unbounded queue. Measured
 * on the CS11 wireless link, one event-channel write can block for 200-380 ms; replaying every queued
 * position after such a stall makes the pointer trail the finger, and a long stall replays a whole
 * stale swipe on the phone.
 *
 * The gate keeps one ordered queue of pending states and collapses consecutive states that share the
 * same shape (the same fingers held down) down to the newest position. A shape change - a finger
 * going down or up, or the contact count changing - always starts a new queue entry, so transitions
 * are never dropped or reordered. Coordinates may be skipped; transitions may not.
 */
internal class TouchDispatchGate {
    private val pending = ArrayDeque<TouchState>()
    private var workerActive = false

    /**
     * Records the newest contact state. Returns true when the caller must start a worker run, false
     * when an already running worker will pick the state up.
     */
    @Synchronized fun enqueue(state: TouchState): Boolean {
        val previous = pending.lastOrNull()
        if (previous != null && shape(previous.contacts) == shape(state.contacts) &&
            shape(state.contacts).isNotEmpty()
        ) {
            // The same fingers are still held: only the newest position matters.
            pending.removeLast()
        }
        pending.addLast(state)
        if (workerActive) return false
        workerActive = true
        return true
    }

    /** The next state to send, or null once everything queued has been handed out. */
    @Synchronized fun next(): TouchState? {
        pending.removeFirstOrNull()?.let { return it }
        workerActive = false
        return null
    }

    /** A new session must not inherit the previous session's pending states. */
    @Synchronized fun reset() {
        pending.clear()
    }

    private fun shape(contacts: List<AirPlayContact>): List<Boolean> = contacts.map { it.down }
}
