package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.ClusterCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the head unit's own banner says about a call -- and when it stays away.
 *
 * The banner replaces what the head unit's native phone UI drew from HFP before this project released it
 * (docs/_digest/121 §8). Its rule is deliberately narrow: a telephone call already has CarPlay's call
 * screen and its cluster card, and a second floating banner over those would be noise.
 */
class CallBannerTest {
    private fun call(
        phase: ClusterCall.Phase,
        name: String = "",
        number: String = "",
    ) = ClusterCall(phase, name, number)

    @Test
    fun noCallMeansNoBanner() {
        assertNull(callBannerFor(null, inferred = false, fallbackTitle = "语音通话"))
    }

    @Test
    fun aRingingVoiceCallIsSomethingToAnswer() {
        val banner = callBannerFor(
            call(ClusterCall.Phase.RINGING, name = "张三"),
            inferred = false,
            fallbackTitle = "语音通话",
        )!!

        assertEquals("张三", banner.title)
        assertTrue(banner.ringing)
        assertFalse(banner.inferred)
    }

    @Test
    fun anEstablishedVoiceCallHasNothingToAnswer() {
        val banner = callBannerFor(
            call(ClusterCall.Phase.ACTIVE, name = "张三"),
            inferred = false,
            fallbackTitle = "语音通话",
        )!!

        assertFalse(banner.ringing)
    }

    @Test
    fun aTelephoneCallGetsNoBanner() {
        // CarPlay draws its own call screen for these, and the cluster card repeats the caller.
        assertNull(
            callBannerFor(
                call(ClusterCall.Phase.RINGING, name = "李四", number = "+8613900000000"),
                inferred = false,
                fallbackTitle = "语音通话",
            ),
        )
    }

    @Test
    fun aCallKnownOnlyFromItsAudioIsAlwaysShown() {
        // Nothing about it can be answered, but the driver still needs to see that he is on a call.
        val banner = callBannerFor(call(ClusterCall.Phase.ACTIVE), inferred = true, fallbackTitle = "语音通话")!!

        assertEquals("语音通话", banner.title)
        assertTrue(banner.inferred)
        assertFalse(banner.ringing)
    }

    @Test
    fun theFallbackTitleIsOnlyUsedWhenTheIphoneNamesNobody() {
        val named = callBannerFor(
            call(ClusterCall.Phase.ACTIVE, name = "王五"),
            inferred = true,
            fallbackTitle = "语音通话",
        )!!
        assertEquals("王五", named.title)

        val anonymous = callBannerFor(call(ClusterCall.Phase.ACTIVE), inferred = false, fallbackTitle = "语音通话")!!
        assertEquals("语音通话", anonymous.title)
    }
}
