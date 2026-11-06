package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wired control plane must not log one line per frame (upstream #634 caps at 50). */
class UsbmuxLogBudgetTest {
    @Test fun pureAcksAreLoggedOnlyUpToTheBudget() {
        val budget = UsbmuxLogBudget(limit = 50)
        repeat(50) { assertTrue("ACK #${it + 1} must be logged", budget.controlFrame(flags = 0x10)) }
        repeat(10) { assertFalse("ACK after the budget must stay silent", budget.controlFrame(flags = 0x10)) }
    }

    @Test fun controlFlagsAlwaysLogAndDoNotConsumeTheBudget() {
        val budget = UsbmuxLogBudget(limit = 2)
        repeat(3) {
            assertTrue(budget.controlFrame(flags = 0x02)) // SYN
            assertTrue(budget.controlFrame(flags = 0x01)) // FIN
            assertTrue(budget.controlFrame(flags = 0x04)) // RST
        }
        assertTrue(budget.controlFrame(flags = 0x10))
        assertTrue(budget.controlFrame(flags = 0x10))
        assertFalse(budget.controlFrame(flags = 0x10))
    }

    @Test fun largeWritesShareTheSameLimitIndependently() {
        val budget = UsbmuxLogBudget(limit = 3)
        repeat(3) { assertTrue(budget.largeWrite()) }
        assertFalse(budget.largeWrite())
        // The control-frame budget is separate.
        assertTrue(budget.controlFrame(flags = 0x10))
    }
}
