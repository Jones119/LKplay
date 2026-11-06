package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS 17+ offers a USBMUX+NCM configuration without Apple Ethernet; using it succeeds at iAP2 and
 * never starts AirPlay (upstream #634). On this car it is configuration 5 vs 6 (doc 117 §4ter).
 */
class WiredConfigurationActionTest {
    @Test fun aCarPlayModeConfigurationIsUsedImmediately() {
        assertEquals(
            WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(found = true, carPlayMode = true, transitionsTried = 0, maximumTransitions = 2),
        )
        assertEquals(
            WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(found = true, carPlayMode = true, transitionsTried = 1, maximumTransitions = 2),
        )
    }

    @Test fun aConfigurationWithoutAppleEthernetIsRequestedFirst() {
        assertEquals(
            WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(found = true, carPlayMode = false, transitionsTried = 0, maximumTransitions = 2),
        )
        // After one mode request it is the mode the phone offers, so it is used.
        assertEquals(
            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET,
            WiredConfigurationAction.choose(found = true, carPlayMode = false, transitionsTried = 1, maximumTransitions = 2),
        )
    }

    @Test fun aMissingConfigurationIsRequestedOrRejectedByTheAttemptBudget() {
        assertEquals(
            WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(found = false, carPlayMode = false, transitionsTried = 1, maximumTransitions = 2),
        )
        assertEquals(
            WiredConfigurationAction.REJECT,
            WiredConfigurationAction.choose(found = false, carPlayMode = false, transitionsTried = 2, maximumTransitions = 2),
        )
    }

    @Test fun onlyARejectedRequestForANonCarPlayConfigurationFallsBackToIt() {
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = false))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = true))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = false, carPlayMode = false))
    }
}
