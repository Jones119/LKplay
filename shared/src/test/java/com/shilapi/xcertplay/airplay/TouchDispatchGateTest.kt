package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDispatchGateTest {
    private val gate = TouchDispatchGate()

    private fun contact(id: Int, x: Double, down: Boolean = true) = AirPlayContact(id, x, 0.5, down)

    private fun state(vararg contacts: AirPlayContact, enqueuedNs: Long = 1) =
        TouchState(contacts.toList(), enqueuedNs)

    private fun drain(): List<TouchState> = buildList {
        while (true) add(gate.next() ?: return@buildList)
    }

    @Test fun heldFingersCollapseToTheNewestPosition() {
        assertTrue(gate.enqueue(state(contact(0, 0.1))))
        assertFalse(gate.enqueue(state(contact(0, 0.2))))
        assertFalse(gate.enqueue(state(contact(0, 0.3), enqueuedNs = 3)))
        val sent = drain()
        assertEquals(1, sent.size)
        assertEquals(0.3, sent[0].contacts[0].x, 0.0)
        assertTrue(sent[0].contacts[0].down)
        assertEquals(3L, sent[0].enqueuedNs)
    }

    @Test fun fingerUpIsNeverCollapsedIntoTheDownState() {
        gate.enqueue(state(contact(0, 0.1)))
        gate.enqueue(state())
        val sent = drain()
        assertEquals(2, sent.size)
        assertEquals(1, sent[0].contacts.size)
        assertTrue(sent[1].contacts.isEmpty())
    }

    @Test fun aSecondFingerIsAShapeChangeAndKeepsItsOrder() {
        gate.enqueue(state(contact(0, 0.1)))
        gate.enqueue(state(contact(0, 0.1), contact(1, 0.9)))
        gate.enqueue(state(contact(0, 0.2), contact(1, 0.8)))
        val sent = drain()
        assertEquals(2, sent.size)
        assertEquals(1, sent[0].contacts.size)
        assertEquals(2, sent[1].contacts.size)
        assertEquals(0.2, sent[1].contacts[0].x, 0.0)
        assertEquals(0.8, sent[1].contacts[1].x, 0.0)
    }

    @Test fun movesAfterASentStateFormANewPendingState() {
        gate.enqueue(state(contact(0, 0.1)))
        assertEquals(1, drain().size)
        gate.enqueue(state(contact(0, 0.2)))
        gate.enqueue(state(contact(0, 0.3)))
        val sent = drain()
        assertEquals(1, sent.size)
        assertEquals(0.3, sent[0].contacts[0].x, 0.0)
    }

    @Test fun aRunningWorkerIsNotScheduledTwice() {
        assertTrue(gate.enqueue(state(contact(0, 0.1))))
        assertFalse(gate.enqueue(state(contact(0, 0.2))))
        assertEquals(1, drain().size)
        assertTrue(gate.enqueue(state(contact(0, 0.3))))
    }

    @Test fun resetDropsPendingStates() {
        gate.enqueue(state(contact(0, 0.1)))
        gate.reset()
        assertNull(gate.next())
    }

    @Test fun abortReleasesTheWorkerSoANewStateCanBeScheduledAgain() {
        // The drain worker calls abort() when it exits without an active session; the next
        // session's enqueue must schedule a fresh worker rather than believe one is still running.
        assertTrue(gate.enqueue(state(contact(0, 0.1))))
        gate.abort()
        assertTrue(gate.enqueue(state(contact(0, 0.2))))
        val sent = gate.next()
        assertEquals(0.2, sent?.contacts?.get(0)?.x ?: 0.0, 0.0)
        assertNull(gate.next())
    }
}
