package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class P2pOwnershipTest {
    @Test fun reinstallWithoutPreferencesCanReclaimOnlyItsOwnNamespace() {
        val prefix = P2pOwnership.prefix("com.shihab.diplay", "device-user-signing-key-id")
        val ssid = prefix + "aB23"
        assertTrue(P2pOwnership.canReclaim(true, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(false, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("other.app", "device-user-signing-key-id")))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("com.shihab.diplay", "other-device")))
        assertTrue(ssid.length <= 32)
    }

    @Test fun legacyGroupNeedsExactRecordAndUnknownGroupsNeedExplicitReset() {
        val prefix = P2pOwnership.prefix("app", "id")
        assertTrue(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", "DIRECT-xcaB23", prefix))
        assertFalse(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, null, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "aB23-extra", null, prefix))
    }

    @Test fun aGroupWeAskedTheFrameworkToNameIsStillOursAfterACrashMiddleCreate() {
        val prefix = P2pOwnership.prefix("app", "id")
        // Android 4.x createGroup names the group itself, so "owned_ssid" cannot name it before the
        // call returns; the recorded request is what makes the leftover group recognisable.
        assertTrue(P2pOwnership.canReclaim(true, "DIRECT-8f-Android_1234", null, prefix,
            legacyGroupRequested = true))
        // Still never a foreign group, and never a nameless one.
        assertFalse(P2pOwnership.canReclaim(false, "DIRECT-8f-Android_1234", null, prefix,
            legacyGroupRequested = true))
        assertFalse(P2pOwnership.canReclaim(true, null, null, prefix, legacyGroupRequested = true))
        assertFalse(P2pOwnership.canReclaim(true, "  ", null, prefix, legacyGroupRequested = true))
    }
}
