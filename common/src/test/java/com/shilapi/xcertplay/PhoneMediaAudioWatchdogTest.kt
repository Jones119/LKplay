package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CS11 paused the phone about a second after play because its own music engine reacts to the
 * phone holding an A2DP link while CarPlay owns the audio. The watcher must release that link while
 * a session lives, and leave it alone whenever it is already down.
 */
class PhoneMediaAudioWatchdogTest {
    private class FakeLink(var current: MediaAudioLinkState = MediaAudioLinkState.UNKNOWN) : PhoneMediaAudioLink {
        var releases = 0
        var restores = 0
        var releaseResult = true
        override fun state(): MediaAudioLinkState = current
        override fun release(): Boolean { releases++; return releaseResult }
        override fun restore(): Boolean { restores++; return true }
    }

    private val messages = mutableListOf<String>()
    private fun watcher(link: FakeLink) = PhoneMediaAudioWatcher(link) { messages += it }

    @Test fun aConnectedPhoneMediaLinkIsReleased() {
        val link = FakeLink(MediaAudioLinkState.CONNECTED)
        assertTrue(watcher(link).tick())
        assertEquals(1, link.releases)
    }

    @Test fun anAlreadyReleasedOrUnknownLinkIsLeftAlone() {
        val disconnected = FakeLink(MediaAudioLinkState.DISCONNECTED)
        val unknown = FakeLink(MediaAudioLinkState.UNKNOWN)
        assertFalse(watcher(disconnected).tick())
        assertFalse(watcher(unknown).tick())
        assertEquals(0, disconnected.releases)
        assertEquals(0, unknown.releases)
    }

    @Test fun everyTickReleasesAReAppearingLink() {
        val link = FakeLink(MediaAudioLinkState.CONNECTED)
        val subject = watcher(link)
        subject.tick()
        subject.tick()
        assertEquals(2, link.releases)
    }

    @Test fun aRejectedReleaseIsReported() {
        val link = FakeLink(MediaAudioLinkState.CONNECTED).apply { releaseResult = false }
        assertFalse(watcher(link).tick())
        assertTrue(messages.last(), messages.last().contains("released=false"))
    }

    @Test fun thePhoneGetsItsMediaLinkBackWhenTheSessionEnds() {
        val link = FakeLink(MediaAudioLinkState.CONNECTED)
        val subject = watcher(link)
        subject.tick()
        subject.restore()
        assertEquals(1, link.restores)
    }

    /**
     * iOS reports its address lower case and that is what is stored, while this head unit's stack only
     * accepts upper case: measured on the car on 2026-10-15, the stored lower case made the framework
     * answer `phone media watchdog not started (bad phone address)` and made the vendor answer
     * `reqA2dpDisconnect(9c:92:4f:02:b7:b7)=false`, while the reconnect — by then answering with its own
     * upper-case address — released both profiles. Both callers take the address from here.
     */
    @Test fun thePhoneAddressIsCanonicalisedToTheCarsSpelling() {
        assertEquals("9C:92:4F:02:B7:B7", validPhoneAddress("9c:92:4f:02:b7:b7"))
        assertEquals("9C:92:4F:02:B7:B7", validPhoneAddress("  9C:92:4F:02:B7:B7  "))
    }

    @Test fun aValueThatIsNotAMacIsRefused() {
        assertNull(validPhoneAddress(null))
        assertNull(validPhoneAddress(""))
        assertNull(validPhoneAddress("192.168.43.1"))
        assertNull(validPhoneAddress("9c:92:4f:02:b7"))
    }
}
