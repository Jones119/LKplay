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
 * The product rule is "one transport" (docs/_digest/63): on API 19 the userspace NCM bridge carries the
 * link whenever its stack and library are present, and when they are not, the session keeps the iAP2
 * control channel and logs why. There is no kernel netdev left to fall back to, which these tests pin at
 * each level and each setting.
 */
class CarPlayWiredTransportDecisionTest {
    @Test
    fun withNoStackTheBridgeStandsDownAndOnlyTheControlChannelRemains() {
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
        assertTrue(
            "the outcome has to be stated, because there is no kernel netdev any more: ${decision.reason}",
            decision.reason.contains("control channel only"),
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
    fun theDiagnosticModeReportsWhatIsMissingInsteadOfDegradingSilently() {
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
    fun anUnavailableBridgeOnApi19NeverPromisesAKernelCarrier() {
        // The log line is the only place an operator learns why wired media did not start, so it must not
        // name a transport that no longer exists (docs/_digest/63).
        for (mode in NcmLegacyMode.values()) {
            for (readiness in UserspaceNcmReadiness.values()) {
                val decision = wiredTransportDecision(19, mode, readiness)
                if (decision.userspace) continue
                assertFalse(
                    "$mode/$readiness still promises a kernel netdev: ${decision.reason}",
                    decision.reason.contains("kernel netdev"),
                )
            }
        }
    }

    @Test
    fun theLibraryIsTheOnlyThingLeftBetweenTheDefaultAndTheUserspaceBridge() {
        // The stack is implemented now (docs/_digest/58 §11), so readiness turns on the library: with
        // lwip_bridge and legacy_usb in the APK the default transport is the userspace bridge, and a build
        // without them keeps the control channel only (the kernel netdev was deleted, docs/_digest/63).
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
