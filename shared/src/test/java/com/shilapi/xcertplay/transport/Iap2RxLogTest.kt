package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 0x5001/0x5201 repeat every second; they must not dominate the saved report (upstream #473). */
class Iap2RxLogTest {
    @Test fun otherMessagesAreLoggedEveryTime() {
        val log = Iap2RxLog()
        repeat(10) { assertEquals("iap2 rx=0x4300", log.line(0x4300)) }
    }

    @Test fun periodicMessagesAreThinnedAfterTheFirstFive() {
        val log = Iap2RxLog(first = 5, every = 100)
        for (index in 1..5) assertNotNull("line $index must be kept", log.line(0x5001))
        for (index in 6..99) assertNull("line $index must be dropped", log.line(0x5001))
        assertEquals("iap2 rx=0x5001 count=100", log.line(0x5001))
        assertNull(log.line(0x5001))
    }

    @Test fun eachPeriodicMessageKeepsItsOwnCount() {
        val log = Iap2RxLog(first = 1, every = 3)
        assertEquals("iap2 rx=0x5001", log.line(0x5001))
        assertNull(log.line(0x5001))
        assertEquals("iap2 rx=0x5001 count=3", log.line(0x5001))
        // The 0x5201 counter starts at one regardless of what 0x5001 has seen.
        assertEquals("iap2 rx=0x5201", log.line(0x5201))
        assertNull(log.line(0x5201))
        assertEquals("iap2 rx=0x5201 count=3", log.line(0x5201))
    }
}
