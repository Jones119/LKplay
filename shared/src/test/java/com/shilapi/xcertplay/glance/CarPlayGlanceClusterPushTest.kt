package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.media.clusterGuidanceCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The cluster push must leave the caller and the monitor (docs/_digest/46 G9).
 *
 * Every published guidance snapshot turns into up to six *synchronous* binder transactions into the
 * vendor's DIM service. Doing that inside `synchronized(this)` on the iAP2 control thread lets a busy or
 * wedged service stall CarPlay frame handling, so the push is handed to one dedicated daemon thread --
 * the shape the working reference implementation uses. [CarPlayGlance.clusterSubmit] is the injectable
 * seam: these tests watch which thread runs the push, whether the monitor was still held at that moment,
 * and that a slow vendor transaction cannot hold up the caller.
 */
class CarPlayGlanceClusterPushTest {
    private lateinit var originalSubmit: (CarPlayGlance.Snapshot) -> Unit

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    @Before
    fun setUp() {
        originalSubmit = CarPlayGlance.clusterSubmit
        CarPlayGlance.listener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun tearDown() {
        CarPlayGlance.clusterSubmit = originalSubmit
        CarPlayGlance.listener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @Test
    fun thePushRunsOnItsOwnDaemonThreadAndOutsideTheMonitor() {
        val pushes = LinkedBlockingQueue<Push>()
        CarPlayGlance.clusterSubmit = { snapshot ->
            pushes += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), snapshot)
        }

        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) {
            u8(1, 1)
            u32(0x0a, 350)
            u32(0x07, 41_900)
            u64(0x06, 600)
            u16List(0x0d, listOf(7))
        })

        val push = awaitManeuver(pushes)
        assertNotEquals("the push must not run on the caller's thread", Thread.currentThread().name, push.thread.name)
        assertEquals(CLUSTER_PUSH_THREAD, push.thread.name)
        assertTrue("the push thread must not keep the process alive", push.thread.isDaemon)
        assertFalse("the push ran while CarPlayGlance's monitor was held", push.heldMonitor)
        // The snapshot itself still arrives, whole: the hand-off moved the call, not the payload.
        // Maneuver index 7 carries Apple type 2 (a right turn), which is vendor arrow id 6.
        assertEquals(2, push.snapshot.maneuverType)
        assertEquals(350, push.snapshot.distanceMeters)
        assertEquals("Main Street", push.snapshot.road)
        assertEquals(6, clusterGuidanceCard(push.snapshot)!!.arrowId)
        assertEquals(600, clusterGuidanceCard(push.snapshot)!!.remainingSeconds)

        // A second frame goes to the same thread: the pushes are serialised in order, not fanned out.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 200); u16List(0x0d, listOf(7)) })
        val second = awaitManeuver(pushes)
        assertSame("one dedicated thread, not one per frame", push.thread, second.thread)
        assertEquals(200, second.snapshot.distanceMeters)
    }

    @Test
    fun aSlowVendorTransactionDoesNotStallTheFramePath() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CarPlayGlance.clusterSubmit = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            CarPlayGlance.setConnected(true)
            val started = System.nanoTime()
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 1); string(4, "Main Street") })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(1)) })
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertTrue("the push has to have started", entered.await(5, TimeUnit.SECONDS))
            // If the push ran inline, the two frames above would have waited out the 5 s vendor call.
            assertTrue("frame handling waited ${elapsedMillis}ms for the cluster push", elapsedMillis < 1_000)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun anUnchangedSnapshotIsNotPushedAgain() {
        val pushes = LinkedBlockingQueue<Push>()
        CarPlayGlance.clusterSubmit = { snapshot ->
            pushes += Push(Thread.currentThread(), Thread.holdsLock(CarPlayGlance), snapshot)
        }

        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) })
        assertNotNull(awaitManeuver(pushes))

        // Re-reading the same state must not queue another transaction: the dedupe still holds, and it
        // now holds before the hand-off rather than inside the bridge.
        CarPlayGlance.snapshot()
        CarPlayGlance.snapshot()
        assertTrue("a repeated snapshot queued another push", pushes.isEmpty())
    }

    /**
     * One observed hand-off: the thread that ran it, whether the monitor was held, and what it carried.
     */
    private data class Push(
        val thread: Thread,
        val heldMonitor: Boolean,
        val snapshot: CarPlayGlance.Snapshot,
    )

    /**
     * The first push carrying a maneuver, skipping the connection-only snapshots taken while clearing
     * state -- the queue is drained in order, so a snapshot can only be followed by later ones.
     */
    private fun awaitManeuver(pushes: BlockingQueue<Push>): Push {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val push = pushes.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (push.snapshot.maneuverType != null) return push
        }
        throw AssertionError("no maneuver reached the cluster push thread")
    }
}
