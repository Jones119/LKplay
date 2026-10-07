package com.shilapi.xcertplay.network

import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-function coverage for the 4.4 soft-AP configuration mapping; no Android runtime required. */
class LegacySoftApControlTest {
    private fun bits(vararg indices: Int) = BitSet().apply { indices.forEach(::set) }

    private fun stored(
        ssid: String?,
        password: String?,
        security: LegacySoftApControl.StoredSecurity,
    ) = LegacySoftApControl.StoredApConfiguration(ssid, password, security)

    @Test fun wepKeyTakesPrecedenceOverKeyManagement() {
        assertEquals(
            LegacySoftApControl.StoredSecurity.WEP,
            LegacySoftApControl.storedSecurity(bits(1), hasWepKey = true),
        )
    }

    @Test fun wpaAndWpa2KeyManagementMapToPsk() {
        assertEquals(LegacySoftApControl.StoredSecurity.WPA_PSK, LegacySoftApControl.storedSecurity(bits(1), false))
        assertEquals(LegacySoftApControl.StoredSecurity.WPA_PSK, LegacySoftApControl.storedSecurity(bits(4), false))
    }

    @Test fun emptyOrNoneOnlyKeyManagementIsOpen() {
        assertEquals(LegacySoftApControl.StoredSecurity.OPEN, LegacySoftApControl.storedSecurity(bits(), false))
        assertEquals(LegacySoftApControl.StoredSecurity.OPEN, LegacySoftApControl.storedSecurity(bits(0), false))
    }

    @Test fun otherKeyManagementIsUnsupported() {
        assertEquals(LegacySoftApControl.StoredSecurity.UNSUPPORTED, LegacySoftApControl.storedSecurity(bits(2), false))
        assertEquals(LegacySoftApControl.StoredSecurity.UNSUPPORTED, LegacySoftApControl.storedSecurity(null, false))
    }

    @Test fun openNetworkIsUsableWithoutPassword() {
        val usable = LegacySoftApControl.usable(stored("Car hotspot", null, LegacySoftApControl.StoredSecurity.OPEN))
        assertEquals("Car hotspot", usable?.ssid)
        assertNull(usable?.password)
        assertEquals(LegacySoftApControl.StoredSecurity.OPEN, usable?.security)
    }

    @Test fun invalidNamesAreRejected() {
        assertNull(LegacySoftApControl.usable(null))
        assertNull(LegacySoftApControl.usable(stored(null, null, LegacySoftApControl.StoredSecurity.OPEN)))
        assertNull(LegacySoftApControl.usable(stored("   ", null, LegacySoftApControl.StoredSecurity.OPEN)))
        assertNull(LegacySoftApControl.usable(stored("Car\u0000hotspot", null, LegacySoftApControl.StoredSecurity.OPEN)))
        assertNull(LegacySoftApControl.usable(stored("s".repeat(33), null, LegacySoftApControl.StoredSecurity.OPEN)))
        assertNull(LegacySoftApControl.usable(stored("é".repeat(17), null, LegacySoftApControl.StoredSecurity.OPEN)))
    }

    @Test fun nameAtTheByteLimitIsAccepted() {
        assertEquals("s".repeat(32), LegacySoftApControl.usable(stored("s".repeat(32), null, LegacySoftApControl.StoredSecurity.OPEN))?.ssid)
    }

    @Test fun pskPassphraseLengthBoundaries() {
        val security = LegacySoftApControl.StoredSecurity.WPA_PSK
        assertEquals("a".repeat(8), LegacySoftApControl.usable(stored("Car", "a".repeat(8), security))?.password)
        assertEquals("a".repeat(63), LegacySoftApControl.usable(stored("Car", "a".repeat(63), security))?.password)
        assertNull(LegacySoftApControl.usable(stored("Car", "a".repeat(7), security)))
        assertNull(LegacySoftApControl.usable(stored("Car", "g".repeat(64), security)))
    }

    @Test fun rawHexPskIsAcceptedAndNonHexRejected() {
        val security = LegacySoftApControl.StoredSecurity.WPA_PSK
        val rawPsk = "0123456789abcdef".repeat(4)
        assertEquals(rawPsk, LegacySoftApControl.usable(stored("Car", rawPsk, security))?.password)
        assertNull(LegacySoftApControl.usable(stored("Car", "z".repeat(64), security)))
    }

    @Test fun nonAsciiOrMissingPasswordsAreRejected() {
        val security = LegacySoftApControl.StoredSecurity.WPA_PSK
        assertNull(LegacySoftApControl.usable(stored("Car", null, security)))
        assertNull(LegacySoftApControl.usable(stored("Car", "é".repeat(10), security)))
        assertNull(LegacySoftApControl.usable(stored("Car", "pass\tword", security)))
    }

    @Test fun wepAndUnsupportedConfigurationsAreRejected() {
        assertNull(LegacySoftApControl.usable(stored("Car", "12345", LegacySoftApControl.StoredSecurity.WEP)))
        assertNull(LegacySoftApControl.usable(stored("Car", null, LegacySoftApControl.StoredSecurity.UNSUPPORTED)))
    }
}
