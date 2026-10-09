package com.shilapi.xcertplay.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps per-frame traces from filling the log buffer: with tracing off the message must
 * not even be built.
 */
class DiagLogTest {
    @After
    fun resetGate() {
        // On the JVM the SystemProperties probe fails, so this leaves the gate closed.
        DiagLog.update(false)
    }

    @Test
    fun tracesAreOffByDefaultAndTheMessageIsNeverBuilt() {
        DiagLog.update(false)
        assertFalse(DiagLog.traceEnabled)

        var built = 0
        DiagLog.trace("DiagLogTest") {
            built++
            "expensive ${ByteArray(64).toHexish()}"
        }

        assertEquals(0, built)
    }

    @Test
    fun enablingTracesRunsTheMessageExactlyOnce() {
        DiagLog.update(true)
        assertTrue(DiagLog.traceEnabled)

        var built = 0
        DiagLog.trace("DiagLogTest") {
            built++
            "payload"
        }

        assertEquals(1, built)
    }

    private fun ByteArray.toHexish(): String = joinToString("") { "%02x".format(it) }
}
