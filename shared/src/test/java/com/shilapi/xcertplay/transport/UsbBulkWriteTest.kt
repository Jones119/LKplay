package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBulkWriteTest {
    @Test fun oneFullTransferWritesEverythingOnce() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val result = UsbBulkWrite.all(100, 500, nowMillis = { 0 }) { offset, length, timeout ->
            calls += Triple(offset, length, timeout)
            length
        }
        assertEquals(UsbBulkWriteResult(100, false), result)
        assertTrue(result.isComplete(100))
        assertEquals(listOf(Triple(0, 100, 500)), calls)
    }

    @Test fun aShortTransferResumesFromTheOffsetItReached() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        var clock = 0L
        val result = UsbBulkWrite.all(100, 500, nowMillis = { clock }) { offset, length, timeout ->
            calls += Triple(offset, length, timeout)
            clock += 10
            if (offset == 0) 40 else length
        }
        assertEquals(100, result.written)
        assertFalse(result.timedOut)
        assertEquals(listOf(Triple(0, 100, 500), Triple(40, 60, 490)), calls)
    }

    @Test fun aStalledPipeStopsImmediatelyWithoutClaimingATimeout() {
        var calls = 0
        val result = UsbBulkWrite.all(100, 500, nowMillis = { 0 }) { _, _, _ ->
            calls++
            0
        }
        assertEquals(UsbBulkWriteResult(0, false), result)
        assertEquals(1, calls)
    }

    @Test fun theDeadlineIsSharedAcrossAttemptsInsteadOfRestartingPerChunk() {
        val timeouts = mutableListOf<Int>()
        var clock = 0L
        val result = UsbBulkWrite.all(300, 100, nowMillis = { clock }) { _, _, timeout ->
            timeouts += timeout
            clock += 60
            10
        }
        assertTrue(result.timedOut)
        assertEquals(20, result.written)
        assertFalse(result.isComplete(300))
        assertEquals(listOf(100, 40), timeouts)
    }

    @Test fun aDeadlineReachedBetweenAttemptsNeverCallsTheTransferAgain() {
        var calls = 0
        var clock = 0L
        val result = UsbBulkWrite.all(300, 50, nowMillis = { clock }) { _, _, _ ->
            calls++
            clock += 50
            10
        }
        assertTrue(result.timedOut)
        assertEquals(10, result.written)
        assertEquals(1, calls)
    }
}
