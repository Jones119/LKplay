package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.ClusterCall
import com.shilapi.xcertplay.hud.ClusterCallState
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A VOIP call reaches the cluster without an iAP2 frame to describe it, and the iPhone's own call state
 * keeps precedence when both exist (docs/_digest/121).
 *
 * The two sources are deliberately kept apart: one knows who is calling, the other only knows that a
 * two-way audio stream is up. These tests pin which one the cluster sees.
 */
class CarPlayGlanceVoiceCallTest {
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
        CarPlayGlance.callListener = null
        // Neither reset may reach the real bridge from a unit test.
        CarPlayGlance.clusterSubmit = {}
        CarPlayGlance.callSubmit = {}
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun tearDown() {
        CarPlayGlance.callSubmit = originalCallSubmit
        CarPlayGlance.clusterSubmit = originalClusterSubmit
        CarPlayGlance.listener = null
        CarPlayGlance.callListener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @Test
    fun aVoiceCallAlonePutsAnActiveCardOnTheCluster() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)

        CarPlayGlance.onVoiceCall(true, 1_000L)

        val call = await(pushed).call!!
        assertEquals(ClusterCall.Phase.ACTIVE, call.phase)
        assertEquals(CarPlayGlance.VOICE_CALL_NAME, call.name)
        // Nobody told us who is calling, and inventing a number would show up on the card as one.
        assertEquals("", call.number)
        assertEquals("the card's timer starts when the call did", 1_000L, call.activeSinceMillis)
        assertTrue(CarPlayGlance.voiceCallActive())
    }

    @Test
    fun theInferredCardIsWithdrawnWhenTheStreamEnds() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onVoiceCall(true, 1_000L)
        await(pushed)

        CarPlayGlance.onVoiceCall(false, 2_000L)

        assertNull("an ended voice call hands over no card, which is what clears it", await(pushed).call)
        assertFalse(CarPlayGlance.voiceCallActive())
    }

    @Test
    fun theIphonesOwnCallStateKeepsTheCard() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(callState(status = 4))
        val answered = await(pushed).call!!
        assertEquals("张三", answered.name)

        // The stream a real call runs on starts after the iPhone has already reported the call. Treating it
        // as a second call would rename the caller to "语音通话" the moment the call ends and its audio
        // stream lags behind the iAP2 state by a few hundred milliseconds.
        CarPlayGlance.onVoiceCall(true, 1_000L)
        CarPlayGlance.onVoiceCall(false, 2_000L)

        assertTrue("a stream that belongs to a reported call must not touch the card", pushed.isEmpty())
        assertFalse(CarPlayGlance.voiceCallActive())
    }

    @Test
    fun aReportedCallTakesTheCardOverAndGivesItBackWhenItEnds() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onVoiceCall(true, 1_000L)
        assertEquals(CarPlayGlance.VOICE_CALL_NAME, await(pushed).call!!.name)

        // A cellular call arrives on top of the VOIP call: the one that can name its caller wins.
        CarPlayGlance.onFrame(callState(status = 2))
        assertEquals("张三", await(pushed).call!!.name)

        // It is declined; the VOIP call is still up, so the cluster goes back to it rather than blank.
        CarPlayGlance.onFrame(callState(status = 0))
        assertEquals(CarPlayGlance.VOICE_CALL_NAME, await(pushed).call!!.name)
    }

    @Test
    fun anUnchangedCardIsNotPushedAgain() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onVoiceCall(true, 1_000L)
        await(pushed)

        CarPlayGlance.onVoiceCall(true, 1_000L)

        assertTrue("the same voice call was pushed twice", pushed.isEmpty())
    }

    @Test
    fun endingTheSessionWithdrawsTheInferredCard() {
        val pushed = LinkedBlockingQueue<Push>()
        CarPlayGlance.callSubmit = { call -> pushed += Push(call) }
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onVoiceCall(true, 1_000L)
        await(pushed)

        CarPlayGlance.setConnected(false)

        assertNull(await(pushed).call)
        assertFalse(CarPlayGlance.voiceCallActive())
    }

    private data class Push(val call: ClusterCall?)

    private fun await(pushed: BlockingQueue<Push>): Push =
        pushed.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no call reached the cluster push thread")

    @Test
    fun theBannerFollowsTheInferredCallAndGoesAwayWithIt() {
        val banners = LinkedBlockingQueue<Notice>()
        CarPlayGlance.callListener = { banner -> banners += Notice(banner) }
        CarPlayGlance.setConnected(true)

        CarPlayGlance.onVoiceCall(true, 1_000L)

        val shown = awaitNotice(banners).banner!!
        assertEquals(CarPlayGlance.VOICE_CALL_NAME, shown.title)
        // Known only from its audio: there is no ringing phase to answer.
        assertFalse(shown.ringing)
        assertTrue(shown.inferred)

        CarPlayGlance.onVoiceCall(false, 2_000L)
        assertNull("the banner must go when the call does", awaitNotice(banners).banner)
    }

    @Test
    fun aTelephoneCallLeavesTheBannerAlone() {
        val banners = LinkedBlockingQueue<Notice>()
        CarPlayGlance.callListener = { banner -> banners += Notice(banner) }
        CarPlayGlance.setConnected(true)

        // A number is what distinguishes a call CarPlay already draws from a VOIP one.
        CarPlayGlance.onFrame(callState(status = 2))

        assertNull(awaitNotice(banners).banner)
    }

    private data class Notice(val banner: CallBanner?)

    private fun awaitNotice(banners: BlockingQueue<Notice>): Notice =
        banners.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the banner was never handed over")
}
