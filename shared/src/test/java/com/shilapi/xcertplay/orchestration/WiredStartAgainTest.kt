package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredStartAgainTest {
    private val start = 1_000_000_000_000L
    private val quiet = WiredStartAgain.QUIET_MILLIS * 1_000_000L

    @Test fun waitsBeforeTheQuietWindow() {
        assertFalse(WiredStartAgain.decide(start + quiet - 1, start, sessionActiveSinceStart = false, alreadySentAgain = false))
    }

    @Test fun sendsOnceTheWindowPassedWithNoSession() {
        assertTrue(WiredStartAgain.decide(start + quiet, start, sessionActiveSinceStart = false, alreadySentAgain = false))
    }

    @Test fun aLiveSessionNeedsNoSecondRequest() {
        assertFalse(WiredStartAgain.decide(start + quiet, start, sessionActiveSinceStart = true, alreadySentAgain = false))
    }

    @Test fun repeatsAtMostOnce() {
        assertFalse(WiredStartAgain.decide(start + quiet * 3, start, sessionActiveSinceStart = false, alreadySentAgain = true))
    }
}
