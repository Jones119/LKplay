package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scaled `touchReport` overload exists to keep a drag allocation-free (docs/_digest/51 §12), so it
 * has to produce **byte-identical** reports to the pre-scaling call site it replaced; otherwise the
 * saving would have changed what the iPhone sees.
 */
class AirPlayHidTouchReportTest {
    private val displayWidth = 1280
    private val displayHeight = 614

    /** The previous call-site shape: scale the contacts first, then encode the normalized report. */
    private fun legacyReport(contacts: List<AirPlayContact>): ByteArray =
        AirPlayHid.touchReport(
            contacts.map { it.copy(x = it.x * displayWidth, y = it.y * displayHeight) },
        )

    @Test
    fun scaledReportMatchesThePreScalingEncodingForEveryContactShape() {
        val cases = listOf(
            listOf(AirPlayContact(0, 0.5, 0.25, down = true)),
            listOf(
                AirPlayContact(0, 0.0, 0.0, down = true),
                AirPlayContact(1, 1.0, 1.0, down = true),
            ),
            listOf(AirPlayContact(0, 0.999, 0.001, down = false)),
            listOf(
                AirPlayContact(0, 0.3333, 0.6666, down = true),
                AirPlayContact(1, 0.1234, 0.9876, down = false),
            ),
            emptyList(),
            // More contacts than the report has slots: the extra ones must be ignored identically.
            listOf(
                AirPlayContact(0, 0.1, 0.2, down = true),
                AirPlayContact(1, 0.3, 0.4, down = false),
                AirPlayContact(2, 0.5, 0.6, down = true),
            ),
        )
        for (contacts in cases) {
            assertArrayEquals(
                "contacts=$contacts",
                legacyReport(contacts),
                AirPlayHid.touchReport(contacts, displayWidth, displayHeight),
            )
        }
    }

    @Test
    fun negativeNormalizedCoordinatesClampToZeroLikeTheScalingCallSiteDid() {
        val contacts = listOf(AirPlayContact(0, -0.25, -0.5, down = true))
        assertArrayEquals(
            legacyReport(contacts),
            AirPlayHid.touchReport(contacts, displayWidth, displayHeight),
        )
    }

    @Test
    fun theNormalizedOverloadIsUnchanged() {
        val contacts = listOf(AirPlayContact(0, 640.0, 307.0, down = true))
        val report = AirPlayHid.touchReport(contacts)
        assertEquals(12, report.size)
        assertEquals(0x00, report[0].toInt())
        assertEquals(0x01, report[1].toInt())
        // Little-endian 640 = 0x0280 and 307 = 0x0133.
        assertEquals(0x80, report[2].toInt() and 0xff)
        assertEquals(0x02, report[3].toInt() and 0xff)
        assertEquals(0x33, report[4].toInt() and 0xff)
        assertEquals(0x01, report[5].toInt() and 0xff)
    }
}
