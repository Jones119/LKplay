package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.NcmLegacyMode
import com.shilapi.xcertplay.transport.UserspaceNcmReadiness
import com.shilapi.xcertplay.transport.UserspaceNcmTransport
import com.shilapi.xcertplay.transport.wiredTransportDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wired transport decision behind `CarPlayController.openDataPaths`.
 *
 * The product rule is "keep both transports, prefer the userspace NCM bridge, let the kernel netdev
 * carry the link when the userspace one cannot" (docs/_digest/58 §3). These tests pin that rule at each
 * level and each setting, including the state the car is in today: the userspace bridge has no TCP/IP
 * stack yet, so the preference is logged and the kernel path takes over instead of failing the session.
 */
class CarPlayWiredTransportDecisionTest {
    @Test
    fun withNoStackThePreferenceIsLoggedAndTheKernelNetdevCarriesTheLink() {
        val decision = wiredTransportDecision(19, NcmLegacyMode.AUTO, UserspaceNcmReadiness.MISSING_STACK)
        assertFalse("a half-built userspace bridge must not be driven", decision.userspace)
        assertTrue(
            "the log line has to say the userspace route was preferred and why it stood down: ${decision.reason}",
            decision.reason.contains("preferred but unavailable"),
        )
        assertTrue(
            "and it has to name the missing piece: ${decision.reason}",
            decision.reason.contains("no TCP/IP stack"),
        )
    }

    @Test
    fun onceTheStackExistsThePreferenceIsHonouredOnEveryLevelBelow21() {
        for (level in 1..20) {
            val decision = wiredTransportDecision(level, NcmLegacyMode.AUTO, UserspaceNcmReadiness.READY)
            assertTrue("API $level must prefer the userspace bridge", decision.userspace)
        }
    }

    @Test
    fun theKernelDebugSwitchNeverAttemptsTheUserspaceBridge() {
        for (level in 1..20) {
            val decision = wiredTransportDecision(level, NcmLegacyMode.KERNEL, UserspaceNcmReadiness.READY)
            assertFalse("API $level", decision.userspace)
            assertTrue(
                "the legacy path has to name itself as legacy in the log: ${decision.reason}",
                decision.reason.contains("legacy kernel netdev"),
            )
        }
    }

    @Test
    fun theUserspaceOnlySettingReportsWhatIsMissingInsteadOfFallingBack() {
        val decision = wiredTransportDecision(19, NcmLegacyMode.FORCE, UserspaceNcmReadiness.MISSING_NATIVE)
        assertFalse(decision.userspace)
        assertTrue(
            "FORCE is the diagnostic mode, so the reason must name the missing native library: ${decision.reason}",
            decision.reason.contains("legacy_usb"),
        )
    }

    @Test
    fun api21AndAboveKeepUsingTheRawUsbBridgeForEverySetting() {
        for (level in 21..37) {
            for (mode in NcmLegacyMode.values()) {
                for (readiness in UserspaceNcmReadiness.values()) {
                    val decision = wiredTransportDecision(level, mode, readiness)
                    assertTrue("API $level / $mode / $readiness", decision.userspace)
                }
            }
        }
    }

    @Test
    fun theLibraryIsTheOnlyThingLeftBetweenTheDefaultAndTheUserspaceBridge() {
        // The stack is implemented now (docs/_digest/58 §11), so readiness turns on the library: with
        // lwip_bridge and legacy_usb in the APK the default transport is the userspace bridge, and a build
        // without them still falls back to the kernel netdev.
        assertTrue(UserspaceNcmTransport.stackImplemented)
        assertEquals(UserspaceNcmReadiness.READY, UserspaceNcmTransport.readiness(nativeAvailable = true))
        assertEquals(
            UserspaceNcmReadiness.MISSING_NATIVE,
            UserspaceNcmTransport.readiness(nativeAvailable = false),
        )
        assertTrue(wiredTransportDecision(19, NcmLegacyMode.AUTO, UserspaceNcmReadiness.READY).userspace)
        assertFalse(
            wiredTransportDecision(19, NcmLegacyMode.AUTO, UserspaceNcmReadiness.MISSING_NATIVE).userspace,
        )
        // A verdict that can no longer be produced must still not select the userspace transport.
        assertFalse(wiredTransportDecision(19, NcmLegacyMode.AUTO, UserspaceNcmReadiness.MISSING_STACK).userspace)
    }
}
