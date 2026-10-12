package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessPointAddressTest {
    @Test fun aUsableMacBecomesSixBytes() {
        assertArrayEquals(
            byteArrayOf(0x4e, 0xb1.toByte(), 0xc7.toByte(), 0x94.toByte(), 0x48, 0x3f),
            accessPointAddress("4E:B1:C7:94:48:3F"),
        )
        assertArrayEquals(
            byteArrayOf(0xda.toByte(), 0x12, 0x34, 0x56, 0x78, 0x9a.toByte()),
            accessPointAddress("da:12:34:56:78:9a"),
        )
    }

    @Test fun redactedMulticastAndAllZeroFormsAreNeverReported() {
        assertNull(accessPointAddress(null))
        assertNull(accessPointAddress(""))
        assertNull(accessPointAddress("00:00:00:00:00:00"))
        assertNull(accessPointAddress("02:00:00:00:00:00"))
        assertNull(accessPointAddress("01:00:5e:00:00:01"))
        assertNull(accessPointAddress("not-a-mac"))
        assertNull(accessPointAddress("4E:B1:C7:94:48"))
        assertNull(accessPointAddress("4E:B1:C7:94:48:3F:00"))
    }
}
