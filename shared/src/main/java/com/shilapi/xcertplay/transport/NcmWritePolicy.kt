package com.shilapi.xcertplay.transport

/**
 * Bulk-OUT timeout for an NCM write.
 *
 * Before StartCarPlaySession the phone keeps the NCM data path NAKed. Waiting out the caller's
 * whole timeout on every frame held the iAP2 handshake back — about 2 s per step upstream
 * (#634/#335/#301), 200 ms here. Until the phone takes its first frame, a frame that cannot go out
 * at once is dropped, exactly as it was after the full timeout; once one is accepted the caller's
 * timeout applies again.
 */
internal object NcmWritePolicy {
    const val NOT_READY_TIMEOUT_MILLIS = 20

    fun effectiveTimeout(requestedMillis: Int, bulkOutAccepted: Boolean): Int =
        if (bulkOutAccepted) requestedMillis else minOf(requestedMillis, NOT_READY_TIMEOUT_MILLIS)
}
