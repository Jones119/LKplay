package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Random

/**
 * Pins [BplistCodec.encodeHidReport] to the generic [BplistCodec.encode].
 *
 * The fixed-layout writer exists only to remove the per-event allocation on the touch path
 * (docs/_digest/69 P0-4): a drag sends 60-120 `hidSendReport` commands per second. Byte equivalence
 * with the generic encoder is the whole contract — if these two ever diverge, the iPhone stops
 * receiving touches, so the fast path must never be "nearly" right.
 */
class BplistHidReportTest {

    private fun generic(uid: Int, report: ByteArray): ByteArray = BplistCodec.encode(
        linkedMapOf(
            "type" to "hidSendReport",
            "uuid" to uid.toString(16),
            "hidReport" to report,
        ),
    )

    /** The real touch uid, plus the other three HID uids, plus shapes that stress the layout. */
    private val uids = intArrayOf(
        AirPlayHid.TOUCH_HID_UID,
        AirPlayHid.KNOB_HID_UID,
        AirPlayHid.MEDIA_HID_UID,
        AirPlayHid.TELEPHONY_HID_UID,
        0,
        1,
        0x0f,
        0xff,
        0xfff,
        0xffff,
        0xfffff,
        0x7fffffff,
    )

    @Test
    fun matchesTheGenericEncoderForEveryHidUidAndReportLength() {
        for (uid in uids) {
            // 1..40 covers knob (4), media (1) and the 12-byte touch report, plus marker boundaries.
            for (length in 1..40) {
                val report = ByteArray(length) { (it * 7 + uid).toByte() }
                assertArrayEquals(
                    "uid=$uid length=$length",
                    generic(uid, report),
                    BplistCodec.encodeHidReport(uid, report),
                )
            }
        }
        // Multi-byte counts force marker() into its 0x4F + int form instead of the inline count.
        for (length in intArrayOf(0x0e, 0x0f, 0x10, 0xfe, 0xff, 0x100, 0x1000)) {
            val report = ByteArray(length) { it.toByte() }
            assertArrayEquals(
                "boundary length=$length",
                generic(AirPlayHid.TOUCH_HID_UID, report),
                BplistCodec.encodeHidReport(AirPlayHid.TOUCH_HID_UID, report),
            )
        }
    }

    @Test
    fun theFixedLayoutActuallyCoversTheRealTouchReport() {
        // The touch report is always 2 contacts x 6 bytes; assert the constants match that shape so a
        // change to the descriptor width cannot silently desynchronize the offsets.
        val contacts = listOf(
            AirPlayContact(id = 0, x = 0.0, y = 0.0, down = true),
            AirPlayContact(id = 1, x = 1.0, y = 1.0, down = false),
        )
        val report = AirPlayHid.touchReport(contacts, 1920, 1080)
        assertEquals(12, report.size)
        assertArrayEquals(
            generic(AirPlayHid.TOUCH_HID_UID, report),
            BplistCodec.encodeHidReport(AirPlayHid.TOUCH_HID_UID, report),
        )
    }

    @Test
    fun randomReportsStillMatch() {
        val random = Random(20261015L)
        repeat(200) {
            val uid = uids[random.nextInt(uids.size)]
            val length = 1 + random.nextInt(64)
            val report = ByteArray(length).also { random.nextBytes(it) }
            assertArrayEquals(
                "uid=$uid length=$length",
                generic(uid, report),
                BplistCodec.encodeHidReport(uid, report),
            )
        }
    }

    /**
     * The point of the change: the fast path must be *decodable* by the same codec, so a future
     * change to the layout is caught even if the generic encoder is refactored in step.
     */
    @Test
    fun theFastPathOutputRoundTripsThroughTheDecoder() {
        val report = AirPlayHid.touchReport(
            listOf(AirPlayContact(id = 0, x = 0.25, y = 0.75, down = true)),
            1920,
            1080,
        )
        val decoded = BplistCodec.decode(BplistCodec.encodeHidReport(AirPlayHid.TOUCH_HID_UID, report))
        assertNotNull(decoded)
        @Suppress("UNCHECKED_CAST")
        val map = decoded as Map<String, Any?>
        assertEquals("hidSendReport", map["type"])
        assertEquals(AirPlayHid.TOUCH_HID_UID.toString(16), map["uuid"])
        assertArrayEquals(report, map["hidReport"] as ByteArray)
    }

    /** Guards the thread-safety premise: concurrent calls must not share any mutable state. */
    @Test
    fun concurrentCallsProduceTheSameBytesAsTheGenericEncoder() {
        val threads = 4
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = (0 until threads).map { worker ->
            Thread {
                val report = ByteArray(12) { (it + worker).toByte() }
                val uid = uids[worker % uids.size]
                val expected = generic(uid, report)
                repeat(500) {
                    if (!expected.contentEquals(BplistCodec.encodeHidReport(uid, report))) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(0, failures.get())
    }
}
