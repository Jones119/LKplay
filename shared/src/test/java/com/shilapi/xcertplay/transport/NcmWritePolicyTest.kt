package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Before StartCarPlaySession the phone NAKs the NCM data path; each frame waited out the caller's
 * whole timeout and delayed the iAP2 handshake (upstream #634/#335).
 */
class NcmWritePolicyTest {
    @Test fun beforeTheFirstAcceptedFrameTheTimeoutIsCapped() {
        assertEquals(20, NcmWritePolicy.effectiveTimeout(requestedMillis = 200, bulkOutAccepted = false))
        assertEquals(20, NcmWritePolicy.effectiveTimeout(requestedMillis = 2_000, bulkOutAccepted = false))
    }

    @Test fun aShorterCallerTimeoutIsNotRaised() {
        assertEquals(10, NcmWritePolicy.effectiveTimeout(requestedMillis = 10, bulkOutAccepted = false))
    }

    @Test fun afterTheFirstAcceptedFrameTheCallerTimeoutApplies() {
        assertEquals(200, NcmWritePolicy.effectiveTimeout(requestedMillis = 200, bulkOutAccepted = true))
        assertEquals(2_000, NcmWritePolicy.effectiveTimeout(requestedMillis = 2_000, bulkOutAccepted = true))
    }
}
