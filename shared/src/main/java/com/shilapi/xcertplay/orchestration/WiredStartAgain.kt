package com.shilapi.xcertplay.orchestration

/**
 * Whether DiPlay sends `carplay-start-session` a second time on USB.
 *
 * The iPhone tries DiPlay's AirPlay listener right after the request and does not try again on its own. While it
 * still holds the CarPlay session of a DiPlay that ended without closing it (the process was stopped or replaced
 * while connected), it keeps the NCM link NAKed, every SYN-ACK is dropped, and the attempt ends at the control
 * loop limit; the next attempt needs a USB replug. A second request once the link is up makes it connect at once
 * (upstream DiPlay `e63b7b3d`, #889/#610, ported per docs/_digest/102 P0-2).
 */
internal object WiredStartAgain {
    /** How long the iPhone may stay silent after the request before DiPlay repeats it. */
    const val QUIET_MILLIS = 2_000L

    private const val NANOS_PER_MILLI = 1_000_000L

    fun decide(
        nowNanos: Long,
        startSentAtNanos: Long,
        sessionActiveSinceStart: Boolean,
        alreadySentAgain: Boolean,
    ): Boolean = !alreadySentAgain &&
        !sessionActiveSinceStart &&
        nowNanos - startSentAtNanos >= QUIET_MILLIS * NANOS_PER_MILLI
}
