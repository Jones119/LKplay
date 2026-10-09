package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USBMUX write path must not ask one `bulkTransfer` for more than the usbfs single-URB ceiling
 * (docs/_digest/44).
 *
 * The read path already caps its submissions ([USBFS_BULK_URB_CEILING_BYTES]); the write path used a
 * 16 KiB *payload* constant, and `sendFrame` then prepends a 16-byte USBMUX header and a 20-byte TCP
 * header, so one transfer could be 16420 bytes - 36 over the ceiling the read path treats as real.
 * No device is available to measure `bulkTransfer` itself, so this pins the arithmetic instead.
 */
class Iap2UsbMuxSendChunkTest {
    @Test
    fun oneChunkIncludingBothHeadersStaysUnderTheUsbfsCeiling() {
        val frameBytes = Iap2UsbMuxHost.MAX_TCP_SEND_PAYLOAD_BYTES +
            Iap2UsbMuxHost.MUX_HEADER_BYTES +
            Iap2UsbMuxHost.TCP_HEADER_BYTES

        assertTrue("a full chunk's frame is $frameBytes bytes", frameBytes <= USBFS_BULK_URB_CEILING_BYTES)
        assertEquals(USBFS_BULK_URB_CEILING_BYTES - 36, Iap2UsbMuxHost.MAX_TCP_SEND_PAYLOAD_BYTES)
    }

    @Test
    fun theOldSixteenKiBPayloadWouldHaveExceededIt() {
        // This is the shape the constant replaced: 16384 payload + 36 header bytes.
        val oldFrameBytes = 16 * 1024 + Iap2UsbMuxHost.MUX_HEADER_BYTES + Iap2UsbMuxHost.TCP_HEADER_BYTES

        assertEquals(16_420, oldFrameBytes)
        assertTrue(oldFrameBytes > USBFS_BULK_URB_CEILING_BYTES)
    }
}
