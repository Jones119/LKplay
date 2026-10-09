package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Package framing for the CarPlayVideo Settings channel (docs/_digest/44).
 *
 * `handlePackages` reads the 8-byte sequence number at package offset 20, so a package whose
 * declared size cannot hold that field must be rejected before the slice rather than letting
 * `copyOfRange` throw out of the read loop (which closes the channel).
 */
class VideoSettingsChannelTest {
    private val readKey = ByteArray(32) { it.toByte() }
    private val writeKey = ByteArray(32) { (it + 1).toByte() }
    private val channel = VideoSettingsChannel(readKey, writeKey) { }

    @Test
    fun aPackageTooShortForTheSequenceNumberIsRejectedBeforeTheSlice() {
        val output = ByteArrayOutputStream()

        for (size in 20..27) {
            // More bytes are buffered than the package declares, which is what makes the declared
            // size - not the buffer - the thing that has to cover the fields that are read.
            val buffer = ByteArray(64)
            putU32Be(buffer, 0, size)
            "sync".toByteArray(Charsets.US_ASCII).copyInto(buffer, 4)
            try {
                channel.handlePackages(buffer, output)
                fail("size=$size must be rejected")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "size=$size rejected for the wrong reason: ${expected.message}",
                    expected.message?.contains("$size") == true,
                )
            } catch (unexpected: IllegalArgumentException) {
                fail("size=$size read past the package instead of being rejected: $unexpected")
            }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun aSyncPackageIsAnsweredWithAnEmptyReplyThatEchoesItsSequenceNumber() {
        val output = ByteArrayOutputStream()
        val sequence = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = ByteArray(32 + 4)
        putU32Be(buffer, 0, buffer.size)
        "sync".toByteArray(Charsets.US_ASCII).copyInto(buffer, 4)
        sequence.copyInto(buffer, 20)

        val tail = channel.handlePackages(buffer, output)

        assertEquals(0, tail.size)
        // A 2-byte little-endian length plus a 32-byte header plus the 16-byte AEAD tag.
        val written = output.toByteArray()
        assertEquals(2 + 32 + 16, written.size)
        val header = AirPlayCrypto.chachaOpen(
            writeKey,
            AirPlayCrypto.nonce64(0),
            written.copyOfRange(2, written.size),
            written.copyOf(2),
        )
        assertEquals("rply", String(header, 4, 4, Charsets.US_ASCII))
        assertArrayEquals(sequence, header.copyOfRange(20, 28))
    }

    @Test
    fun anIncompletePackageIsRetainedWithoutBeingAnswered() {
        val output = ByteArrayOutputStream()
        // Two bytes short of the declared 36, but past the 32-byte header the loop needs.
        val buffer = ByteArray(34)
        putU32Be(buffer, 0, 36)
        "sync".toByteArray(Charsets.US_ASCII).copyInto(buffer, 4)

        val tail = channel.handlePackages(buffer, output)

        assertArrayEquals(buffer, tail)
        assertEquals(0, output.size())
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
