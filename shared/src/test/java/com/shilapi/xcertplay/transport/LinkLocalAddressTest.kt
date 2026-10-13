package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The MAC-to-EUI-64 derivation, pinned to the value the reference implementation actually advertises.
 *
 * Measured on the car: this app used to pin the netif to `fe80::2` and the iPhone never dialled it - zero
 * inbound TCP frames - while the reference, whose AirPlay SETUP URL reads
 * `rtsp://fe80::9c92:4fff:fe09:bf8b`, streams megabytes. That address is the EUI-64 of the link MAC
 * `9e:92:4f:09:bf:8b`, which is the case these tests hold still (docs/_digest/64).
 */
class LinkLocalAddressTest {
    @Test
    fun theCarsMacDerivesTheAddressTheReferenceAdvertises() {
        val mac = byteArrayOf(
            0x9e.toByte(), 0x92.toByte(), 0x4f, 0x09, 0xbf.toByte(), 0x8b.toByte(),
        )
        assertEquals("fe80::9c92:4fff:fe09:bf8b", LinkLocalAddress.fromMac(mac))
    }

    @Test
    fun theUniversalLocalBitIsFlippedAndTheMarkerSplicedIn() {
        val mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
        // 0x02 -> 0x00 (the bit flips off), and ff:fe goes in after the third byte.
        assertEquals("fe80::11:22ff:fe33:4455", LinkLocalAddress.fromMac(mac))
    }

    @Test
    fun aShorterOrMissingMacIsRefusedOrAnsweredWithNull() {
        assertEquals(null, LinkLocalAddress.fromMacOrNull(null))
        assertEquals(null, LinkLocalAddress.fromMacOrNull(byteArrayOf(1, 2, 3)))
        val thrown = runCatching { LinkLocalAddress.fromMac(byteArrayOf(1, 2, 3)) }
        assertEquals(true, thrown.isFailure)
    }
}