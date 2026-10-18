package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.hud.ClusterCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one call push puts in front of the vendor, and when it is not worth sending at all
 * (docs/_digest/86 §2.3, §5 step 2).
 *
 * The statuses are the vendor service's own vocabulary: 12 is an incoming call, 11 an established one,
 * 6 an outgoing one, and 0 is the one value that clears the card whatever the call count says. Both
 * halves are plain functions here so they can be checked without a car and without a clock.
 */
class ClusterCallPushGateTest {
    private fun call(
        phase: ClusterCall.Phase,
        name: String = "张三",
        number: String = "+8613800000000",
        activeSince: Long? = null,
    ) = ClusterCall(phase, name, number, activeSince)

    @Test
    fun aRingingCallAsksForTheIncomingCard() {
        val update = clusterCallUpdate(call(ClusterCall.Phase.RINGING), nowMillis = 1_000)
        assertEquals(CALL_STATUS_IN_COMING, update.status)
        assertEquals(1, update.callCount)
        assertEquals("张三", update.name)
        assertEquals("+8613800000000", update.number)
        assertEquals("a ringing call has no duration yet", 0L, update.durationSeconds)
    }

    @Test
    fun anEstablishedCallAsksForTheHangUpCardWithItsDuration() {
        val update = clusterCallUpdate(call(ClusterCall.Phase.ACTIVE, activeSince = 10_000), nowMillis = 72_500)
        assertEquals(CALL_STATUS_OFFHOOK, update.status)
        assertEquals(62L, update.durationSeconds)
    }

    @Test
    fun anOutgoingCallAsksForTheDiallingCard() {
        assertEquals(CALL_STATUS_DIALING, clusterCallUpdate(call(ClusterCall.Phase.DIALING), 1_000).status)
    }

    @Test
    fun aCallWithoutAStartTimeHasNoDurationRatherThanAnInventedOne() {
        val update = clusterCallUpdate(call(ClusterCall.Phase.ACTIVE, activeSince = null), nowMillis = 72_500)
        assertEquals(0L, update.durationSeconds)
    }

    @Test
    fun noCallAsksForTheIdleStatusThatClearsTheCard() {
        val update = clusterCallUpdate(null, nowMillis = 1_000)
        assertEquals(CALL_STATUS_IDLE, update.status)
        assertEquals("the clearing status ignores the call count, and 1 is never a three-party card", 1, update.callCount)
        assertEquals("", update.name)
        assertEquals("", update.number)
        assertEquals(0L, update.durationSeconds)
    }

    @Test
    fun onlyEverOneCall() {
        // The service picks its three-party branch from this field alone, and that branch relabels the
        // answer button to "accept and hold" -- wrong for an ordinary call.
        for (phase in ClusterCall.Phase.entries) {
            assertEquals(1, clusterCallUpdate(call(phase), 1_000).callCount)
        }
    }

    @Test
    fun theCallerTextIsTrimmedAndCutWithoutBreakingASurrogatePair() {
        assertEquals("张三", clusterCallName("  张三  "))
        assertEquals("", clusterCallName("   "))
        assertEquals(MAX_CALL_NAME_CHARS, clusterCallName("a".repeat(200)).length)

        // An emoji is two chars; a cut that landed between them would render as a broken glyph.
        val emoji = "🙂".repeat(100)
        val cut = clusterCallName(emoji)
        assertTrue(cut.length <= MAX_CALL_NAME_CHARS)
        assertFalse("the cut split a surrogate pair", Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun theNumberIsTrimmedAndCut() {
        val update = clusterCallUpdate(call(ClusterCall.Phase.RINGING, number = "  " + "9".repeat(80) + "  "), 1_000)
        assertEquals(MAX_CALL_NUMBER_CHARS, update.number.length)
    }

    @Test
    fun theFirstPushAlwaysGoesAndAnIdenticalOneNeverDoes() {
        val gate = ClusterCallPushGate(minIntervalMillis = 200)
        val update = clusterCallUpdate(call(ClusterCall.Phase.RINGING), 1_000)

        assertTrue(gate.shouldPush(update, 1_000))
        gate.notePushed(update, 1_000)
        assertFalse("the same card must not be re-sent", gate.shouldPush(update, 5_000))
    }

    @Test
    fun aChangeInsideTheFloorIsCoalescedRatherThanLost() {
        val gate = ClusterCallPushGate(minIntervalMillis = 200)
        val ringing = clusterCallUpdate(call(ClusterCall.Phase.RINGING), 1_000)
        gate.notePushed(ringing, 1_000)

        val active = clusterCallUpdate(call(ClusterCall.Phase.ACTIVE, activeSince = 1_000), 1_050)
        assertFalse("50 ms after the last push is inside the floor", gate.shouldPush(active, 1_050))
        // The bridge keeps this payload pending and the re-push tick asks again: by then it goes.
        assertTrue(gate.shouldPush(active, 1_200))
    }

    @Test
    fun aFailedPushIsNotRememberedAsSent() {
        val gate = ClusterCallPushGate(minIntervalMillis = 200)
        val update = clusterCallUpdate(call(ClusterCall.Phase.RINGING), 1_000)
        gate.notePushed(update, 1_000)

        gate.forget()
        assertTrue("after a failure the very same payload has to be retried", gate.shouldPush(update, 1_000))
    }
}
