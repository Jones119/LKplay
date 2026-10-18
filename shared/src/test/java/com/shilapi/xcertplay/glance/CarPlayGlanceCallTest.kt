package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.ClusterCall
import com.shilapi.xcertplay.hud.ClusterCallState
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A call reaches the cluster's phone card through the same hand-off the guidance card uses
 * (docs/_digest/86 §1): parsed here, submitted on the dedicated cluster thread, never on the iAP2
 * control thread and never with this object's monitor held.
 *
 * The card itself is the vendor service's to draw -- these tests only watch what we hand over, and that
 * ending a call (or the session) hands over "no call" rather than leaving a card behind.
 */
class CarPlayGlanceCallTest {
    private lateinit var originalCallSubmit: (ClusterCall?) -> Unit
    private lateinit var originalClusterSubmit: (CarPlayGlance.Snapshot) -> Unit

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    private fun callState(status: Int, display: String = "张三", uuid: String = "uuid-a") =
        frame(ClusterCallState.CALL_STATE_UPDATE) {
            string(0, "+8613800000000")
            string(1, display)
            u8(2, status)
            string(4, uuid)
        }

    @Before
    fun setUp() {
        originalCallSubmit = CarPlayGlance.callSubmit
        originalClusterSubmit = CarPlayGlance.clusterSubmit
        CarPlayGlance.listener = null
        // The guidance push would go to the real bridge from these frames otherwise.
        CarPlayGlance.clusterSubmit = {}
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun tearDown() {
        CarPlayGlance.callSubmit = originalCallSubmit
        CarPlayGlance.clusterSubmit = originalClusterSubmit
        CarPlayGlance.listener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @Test
    fun aRingingCallIsHandedToTheClusterThreadOutsideTheMonitor() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), call) }
        CarPlayGlance.setConnected(true)

        CarPlayGlance.onFrame(callState(status = 2))

        val push = await(pushed)
        assertNotEquals("the push must not run on the caller's thread", Thread.currentThread().name, push.thread.name)
        assertEquals(CLUSTER_PUSH_THREAD, push.thread.name)
        assertTrue("the push thread must not keep the process alive", push.thread.isDaemon)
        assertFalse("the push ran while CarPlayGlance's monitor was held", push.heldMonitor)
        assertEquals(ClusterCall.Phase.RINGING, push.call!!.phase)
        assertEquals("张三", push.call!!.name)
        assertEquals("+8613800000000", push.call!!.number)
    }

    @Test
    fun theCallIsFollowedThroughConnectedAndEnded() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), call) }
        CarPlayGlance.setConnected(true)

        CarPlayGlance.onFrame(callState(status = 2))
        assertEquals(ClusterCall.Phase.RINGING, await(pushed).call!!.phase)

        CarPlayGlance.onFrame(callState(status = 4))
        assertEquals(ClusterCall.Phase.ACTIVE, await(pushed).call!!.phase)

        CarPlayGlance.onFrame(callState(status = 0))
        assertNull("an ended call hands over no card, which is what clears it", await(pushed).call)
    }

    @Test
    fun anUnchangedCallIsNotPushedTwice() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), call) }
        CarPlayGlance.setConnected(true)

        val ringing = callState(status = 2)
        CarPlayGlance.onFrame(ringing)
        await(pushed)
        // A repeated frame carries the same card, so the bridge must not see it again -- the iPhone
        // repeats the state on its own schedule, and each push costs a frame on the shared channel.
        CarPlayGlance.onFrame(ringing)
        CarPlayGlance.onFrame(ringing)
        assertTrue("a repeated call frame was pushed again", pushed.isEmpty())
    }

    @Test
    fun endingTheSessionClearsACallOnTheCluster() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(callState(status = 4))
        assertEquals(ClusterCall.Phase.ACTIVE, await(pushed).call!!.phase)

        // The cable is pulled mid-call: nobody can answer that card any more, so it has to go.
        CarPlayGlance.setConnected(false)
        assertNull(await(pushed).call)
    }

    private data class Push(val thread: Thread, val heldMonitor: Boolean, val call: ClusterCall?)

    private fun await(pushed: BlockingQueue<Push>): Push =
        pushed.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no call reached the cluster push thread")
}
