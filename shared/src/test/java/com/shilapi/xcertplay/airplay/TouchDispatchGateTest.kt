package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDispatchGateTest {
    private val gate = TouchDispatchGate()

    private fun contact(id: Int, x: Double, down: Boolean = true) = AirPlayContact(id, x, 0.5, down)

    private fun drain(): List<List<AirPlayContact>> = buildList {
        while (true) add(gate.next() ?: return@buildList)
    }

    @Test fun heldFingersCollapseToTheNewestPosition() {
        assertTrue(gate.enqueue(listOf(contact(0, 0.1))))
        assertFalse(gate.enqueue(listOf(contact(0, 0.2))))
        assertFalse(gate.enqueue(listOf(contact(0, 0.3))))
        val sent = drain()
        assertEquals(1, sent.size)
        assertEquals(0.3, sent[0][0].x, 0.0)
        assertTrue(sent[0][0].down)
    }

    @Test fun fingerUpIsNeverCollapsedIntoTheDownState() {
        gate.enqueue(listOf(contact(0, 0.1)))
        gate.enqueue(emptyList())
        val sent = drain()
        assertEquals(2, sent.size)
        assertEquals(1, sent[0].size)
        assertTrue(sent[1].isEmpty())
    }

    @Test fun aSecondFingerIsAShapeChangeAndKeepsItsOrder() {
        gate.enqueue(listOf(contact(0, 0.1)))
        gate.enqueue(listOf(contact(0, 0.1), contact(1, 0.9)))
        gate.enqueue(listOf(contact(0, 0.2), contact(1, 0.8)))
        val sent = drain()
        assertEquals(2, sent.size)
        assertEquals(1, sent[0].size)
        assertEquals(2, sent[1].size)
        assertEquals(0.2, sent[1][0].x, 0.0)
        assertEquals(0.8, sent[1][1].x, 0.0)
    }

    @Test fun movesAfterASentStateFormANewPendingState() {
        gate.enqueue(listOf(contact(0, 0.1)))
        assertEquals(1, drain().size)
        gate.enqueue(listOf(contact(0, 0.2)))
        gate.enqueue(listOf(contact(0, 0.3)))
        val sent = drain()
        assertEquals(1, sent.size)
        assertEquals(0.3, sent[0][0].x, 0.0)
    }

    @Test fun aRunningWorkerIsNotScheduledTwice() {
        assertTrue(gate.enqueue(listOf(contact(0, 0.1))))
        assertFalse(gate.enqueue(listOf(contact(0, 0.2))))
        assertEquals(1, drain().size)
        assertTrue(gate.enqueue(listOf(contact(0, 0.3))))
    }

    @Test fun resetDropsPendingStates() {
        gate.enqueue(listOf(contact(0, 0.1)))
        gate.reset()
        assertNull(gate.next())
    }
}
