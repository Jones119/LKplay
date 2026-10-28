package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call state behind the cluster's phone card (docs/_digest/86).
 *
 * The frames are the ones iOS has been sending all along under the already-sent 0x4154 subscription;
 * what is under test is which of them becomes a card, which caller survives an update that carries
 * only a status, and that a waiting call takes the card over and gives it back.
 */
class ClusterCallStateTest {
    private var now = 1_000L
    private val state = ClusterCallState { now }

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    private fun callState(
        status: Int,
        remote: String? = "+8613800000000",
        display: String? = "张三",
        uuid: String? = "uuid-a",
    ) = frame(ClusterCallState.CALL_STATE_UPDATE) {
        remote?.let { string(0, it) }
        display?.let { string(1, it) }
        u8(2, status)
        uuid?.let { string(4, it) }
    }

    @Test
    fun aRingingCallBecomesTheCard() {
        assertTrue(state.accept(callState(status = 2)))
        val card = state.current()!!
        assertEquals(ClusterCall.Phase.RINGING, card.phase)
        assertEquals("张三", card.name)
        assertEquals("+8613800000000", card.number)
        assertNull("a ringing call has not connected yet", card.activeSinceMillis)
    }

    @Test
    fun anEstablishedCallCarriesWhenItConnected() {
        state.accept(callState(status = 2))
        now = 5_000L
        assertTrue(state.accept(callState(status = 4)))

        val card = state.current()!!
        assertEquals(ClusterCall.Phase.ACTIVE, card.phase)
        assertEquals(5_000L, card.activeSinceMillis)
    }

    @Test
    fun anUpdateCarryingOnlyTheStatusKeepsTheCaller() {
        state.accept(callState(status = 2))
        now = 7_000L
        // The iPhone sends what changed: the connected update repeats neither the name nor the number,
        // but it does name the call it is about.
        assertTrue(
            state.accept(
                frame(ClusterCallState.CALL_STATE_UPDATE) {
                    string(4, "uuid-a")
                    u8(2, 4)
                },
            ),
        )

        val card = state.current()!!
        assertEquals(ClusterCall.Phase.ACTIVE, card.phase)
        assertEquals("张三", card.name)
        assertEquals("+8613800000000", card.number)
    }

    @Test
    fun aFrameThatNamesNoCallIsIgnored() {
        // An unattributable update is accepted on its own (a VOIP call has no number, docs/_digest/121),
        // but it may never take the card from a call the iPhone can name.
        state.accept(callState(status = 2))
        assertFalse(state.accept(frame(ClusterCallState.CALL_STATE_UPDATE) { u8(2, 4) }))
        assertEquals(ClusterCall.Phase.RINGING, state.current()!!.phase)
    }

    @Test
    fun anUnchangedFrameReportsNoChange() {
        val frame = callState(status = 2)
        assertTrue(state.accept(frame))
        assertFalse("the same ringing frame must not be a second card", state.accept(frame))
    }

    @Test
    fun aDisconnectedCallClearsTheCard() {
        state.accept(callState(status = 4))
        assertTrue(state.accept(callState(status = 0)))
        assertNull(state.current())
    }

    @Test
    fun aDisconnectingCallClearsTheCardToo() {
        state.accept(callState(status = 4))
        assertTrue(state.accept(callState(status = 6)))
        assertNull(state.current())
    }

    @Test
    fun aWaitingCallTakesTheCardAndGivesItBack() {
        // The first call is established, then a second one rings: ringing outranks connected.
        state.accept(callState(status = 4, display = "李四", uuid = "uuid-a"))
        val ringing = callState(status = 2, remote = "+8613900000000", display = "王五", uuid = "uuid-b")
        assertTrue(state.accept(ringing))

        assertEquals("王五", state.current()!!.name)
        assertEquals(ClusterCall.Phase.RINGING, state.current()!!.phase)

        // The waiting call goes away: the established one is the card again, with its own start time.
        assertTrue(state.accept(callState(status = 0, remote = "+8613900000000", display = "王五", uuid = "uuid-b")))
        val back = state.current()!!
        assertEquals("李四", back.name)
        assertEquals(ClusterCall.Phase.ACTIVE, back.phase)
        assertEquals(1_000L, back.activeSinceMillis)
    }

    @Test
    fun aHeldCallIsTheCardWhenNothingElseIs() {
        state.accept(callState(status = 5))
        assertEquals(ClusterCall.Phase.ACTIVE, state.current()!!.phase)
    }

    @Test
    fun connectingIsTheWeakestCard() {
        state.accept(callState(status = 1))
        assertEquals(ClusterCall.Phase.DIALING, state.current()!!.phase)
    }

    @Test
    fun aMissingNameKeepsTheNumberOnly() {
        // An unknown caller: the vendor service owns the name -> number -> "unknown number" fallback, so
        // the parser must hand it an empty name rather than inventing one.
        state.accept(callState(status = 2, display = null))
        assertEquals("", state.current()!!.name)
        assertEquals("+8613800000000", state.current()!!.number)
    }

    @Test
    fun aCallWithoutAUuidIsTrackedByItsNumber() {
        state.accept(callState(status = 2, uuid = null))
        assertTrue(state.accept(callState(status = 0, uuid = null)))
        assertNull(state.current())
    }

    @Test
    fun anotherFrameAndAStatuslessFrameAreIgnored() {
        assertFalse(state.accept(frame(0x5001) { group(0) { string(1, "Numb") } }))
        assertFalse(state.accept(frame(ClusterCallState.CALL_STATE_UPDATE) { string(0, "+8613800000000") }))
        assertNull(state.current())
    }

    @Test
    fun clearForgetsEveryCall() {
        state.accept(callState(status = 2, uuid = "uuid-a"))
        state.accept(callState(status = 4, uuid = "uuid-b", remote = "+8613900000000", display = "王五"))
        state.clear()
        assertNull(state.current())

        // Nothing is left to resurrect: the same ringing call is a fresh card, not a remembered one.
        assertTrue(state.accept(callState(status = 2, uuid = "uuid-a")))
        assertEquals(ClusterCall.Phase.RINGING, state.current()!!.phase)
    }

    /**
     * A VOIP call has no telephone number, so its frames can carry only a display name and a status.
     * Dropping those frames is why the cluster stayed empty through a WeChat call while the same car
     * showed an ordinary telephone call (docs/_digest/121).
     */
    @Test
    fun aCallWithNeitherANumberNorAUuidStillBecomesTheCard() {
        assertTrue(state.accept(callState(status = 2, remote = null, uuid = null)))

        val card = state.current()!!
        assertEquals(ClusterCall.Phase.RINGING, card.phase)
        assertEquals("张三", card.name)
        assertEquals("", card.number)
    }

    @Test
    fun theAnonymousCallIsTrackedThroughItsStatesAndEnds() {
        state.accept(callState(status = 2, remote = null, uuid = null))
        now = 4_000L
        assertTrue(state.accept(callState(status = 4, remote = null, display = null, uuid = null)))
        assertEquals(ClusterCall.Phase.ACTIVE, state.current()!!.phase)
        assertEquals("the name carries over a status-only frame", "张三", state.current()!!.name)
        assertEquals(4_000L, state.current()!!.activeSinceMillis)

        assertTrue(state.accept(callState(status = 0, remote = null, display = null, uuid = null)))
        assertNull(state.current())
    }

    @Test
    fun anAnonymousFrameCannotTakeTheCardFromANamedCall() {
        state.accept(callState(status = 4))

        assertFalse("an unattributable ringing must not shadow a real call", state.accept(callState(status = 2, remote = null, uuid = null)))
        assertEquals(ClusterCall.Phase.ACTIVE, state.current()!!.phase)
        assertEquals("张三", state.current()!!.name)
    }

    @Test
    fun aNamedCallEvictsTheAnonymousOne() {
        // The anonymous frame was either that same call before it was identified, or noise; either way it
        // must not be left behind ringing after the named call ends.
        state.accept(callState(status = 2, remote = null, uuid = null))
        assertTrue(state.accept(callState(status = 4)))

        assertTrue(state.accept(callState(status = 0)))
        assertNull(state.current())
    }
}
