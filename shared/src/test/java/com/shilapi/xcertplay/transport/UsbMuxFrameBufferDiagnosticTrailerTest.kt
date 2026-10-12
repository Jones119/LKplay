package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captured protocol-1 diagnostic reply carries the same optional four-byte trailer as the VERSION
 * and TCP replies (upstream #100 / 5fc7a7af). Only the bounded captured shape may claim it: ordinary
 * diagnostic traffic must keep failing loudly instead of having four bytes eaten.
 */
class UsbMuxFrameBufferDiagnosticTrailerTest {
    @Test fun aCapturedDiagnosticReplyCarriesTheTrailerBeforeTcp() {
        val diagnostics = mutableListOf<String>()
        val buffer = UsbMuxFrameBuffer(diagnostics::add)
        buffer.append(diagnosticFrame("diag-text") + padding + tcpReply())

        val diagnostic = buffer.takeFrame() ?: error("the diagnostic reply was not parsed")
        assertEquals(1, diagnostic.protocol)
        assertEquals("diag-text", String(diagnostic.payload, 1, diagnostic.payload.size - 1))

        val tcp = buffer.takeFrame() ?: error("the trailer was not skipped")
        assertEquals(6, tcp.protocol)
        assertEquals(0, buffer.bufferedBytes)
        assertTrue("the recovery must be reported", diagnostics.any { it.contains("nextProtocol=6") })
    }

    @Test fun aCapturedDiagnosticReplyCarriesTheTrailerBeforeAnotherDiagnosticReply() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(diagnosticFrame("first") + padding + diagnosticFrame("second"))
        assertEquals("first", String(buffer.takeFrame()!!.payload, 1, 5))
        assertEquals("second", String(buffer.takeFrame()!!.payload, 1, 6))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun anUncapturedDiagnosticFrameNeverClaimsTheTrailer() {
        val buffer = UsbMuxFrameBuffer()
        // Subtype 4 must be followed by printable text; a control byte disqualifies the frame, so the
        // four bytes after it are not treated as a trailer and the next header stays malformed.
        buffer.append(frame(protocol = 1, payload = byteArrayOf(4, 0x01, 0x02)))
        assertEquals(1, buffer.takeFrame()!!.protocol)
        buffer.append(padding + tcpReply())
        try {
            buffer.takeFrame()
            error("a malformed frame must still be rejected")
        } catch (_: IphoneUsbException.Protocol) {
        }
    }

    private fun diagnosticFrame(text: String): ByteArray =
        frame(protocol = 1, payload = byteArrayOf(4) + text.toByteArray(Charsets.US_ASCII))

    private fun tcpReply(): ByteArray {
        val tcp = ByteArray(20)
        tcp[0] = 0x12; tcp[1] = 0x34 // source port
        tcp[2] = 0x56; tcp[3] = 0x78 // destination port
        tcp[12] = 0x50 // data offset 5 words = the 20-byte base header
        return frame(protocol = 6, payload = tcp)
    }

    private fun frame(protocol: Int, payload: ByteArray, word8: Int = CAPTURED_REPLY_MAGIC): ByteArray =
        u32(protocol) + u32(16 + payload.size) + u32(word8) + u32(0) + payload

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private companion object {
        const val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
        val padding = ByteArray(4)
    }
}
