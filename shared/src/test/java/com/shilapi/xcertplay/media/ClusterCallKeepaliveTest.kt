package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the call card on the cluster is repeated.
 *
 * A call card is pushed only on change, and the vendor's phone card never re-sends itself, so a push lost
 * to the template-negotiation window or to a DIM service restart stays lost for the rest of the call
 * (docs/_digest/86 §2.4 S2, docs/_digest/121). The rule is the media card's (docs/_digest/119) with a
 * call's cadence.
 */
class ClusterCallKeepaliveTest {
    @Test
    fun theFirstTickAfterACardExistsIsDue() {
        // Deliberately immediate: the window a push is most likely to be lost in is the one right after it.
        assertTrue(ClusterCallKeepalive().isDue(1_000L))
    }

    @Test
    fun nothingIsDueInsideTheInterval() {
        val keepalive = ClusterCallKeepalive(intervalMillis = 10_000L)
        keepalive.notePushed(5_000L)

        assertFalse(keepalive.isDue(5_000L))
        assertFalse(keepalive.isDue(14_999L))
    }

    @Test
    fun theCardIsRepeatedOnceTheIntervalHasPassed() {
        val keepalive = ClusterCallKeepalive(intervalMillis = 10_000L)
        keepalive.notePushed(5_000L)

        assertTrue(keepalive.isDue(15_000L))
    }

    @Test
    fun aChangeRestartsTheInterval() {
        val keepalive = ClusterCallKeepalive(intervalMillis = 10_000L)
        keepalive.notePushed(5_000L)
        // The iPhone sent "active" at 12 s: the card is fresh again, so no repeat is owed at 15 s.
        keepalive.notePushed(12_000L)

        assertFalse(keepalive.isDue(15_000L))
        assertTrue(keepalive.isDue(22_000L))
    }
}
