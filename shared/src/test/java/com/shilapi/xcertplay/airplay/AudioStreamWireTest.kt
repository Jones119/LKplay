package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the 16-byte allocation bug behind the type-102 stutter (docs/_digest/43).
 *
 * `runData` decrypts in place into a buffer sized from the sealed body, and the AEAD consumes the
 * 16-byte Poly1305 tag without writing anything for it. Sizing that buffer from the sealed length
 * instead of the plaintext length left 16 untouched zero bytes at the end of every access unit; the
 * decoder read them as a second, truncated frame, produced twice the PCM and logged "substituting
 * silence". Nothing failed loudly, which is exactly why these invariants need to be asserted.
 */
class AudioStreamWireTest {
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }
    private val nonce = AirPlayCrypto.nonce64(7)

    /** One RTP payload byte pattern that is not all zero, so trailing zeroes cannot be mistaken for it. */
    private val accessUnit = ByteArray(652) { ((it * 37 + 9) and 0xff).toByte() }

    private fun wireFor(aad: ByteArray, sealed: ByteArray): ByteArray {
        // Layout: RTP header (12), sealed body, 8-byte nonce. The AAD is the header slice at 4..12.
        val wire = ByteArray(12 + sealed.size + 8)
        aad.copyInto(wire, 4)
        sealed.copyInto(wire, 12)
        return wire
    }

    @Test
    fun plaintextLengthExcludesTheAeadTagAndTheNonce() {
        // 12-byte RTP header + 16-byte tag + 8-byte nonce = 36 bytes of framing.
        assertEquals(652, AudioStreamWire.sealedPlaintextLength(652 + 36))
        assertEquals(668, AudioStreamWire.sealedPayloadLength(652 + 36))
        // One byte shorter and the datagram can no longer hold a whole tag.
        assertTrue(AudioStreamWire.sealedPlaintextLength(35) < 0)
    }

    @Test
    fun decryptingIntoASealedLengthBufferLeavesNoTrailingZeroes() {
        val aad = ByteArray(8) { (it + 1).toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, accessUnit, aad)
        val wire = wireFor(aad, sealed)
        assertEquals(652 + 36, wire.size)

        // Exactly the buffer the fixed code allocates.
        val rtp = ByteArray(12 + AudioStreamWire.sealedPlaintextLength(wire.size))
        val written = AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = wire,
            ciphertextOffset = 12,
            ciphertextLength = AudioStreamWire.sealedPayloadLength(wire.size),
            aad = wire,
            aadOffset = 4,
            aadLength = 8,
            output = rtp,
            outputOffset = 12,
        )

        assertEquals(accessUnit.size, written)
        assertArrayEquals(accessUnit, rtp.copyOfRange(12, 12 + written))
        // The access unit ends with real bytes, never padding.
        assertEquals(12 + accessUnit.size, rtp.size)
        assertNotEquals(0.toByte(), rtp[rtp.size - 1])
    }

    @Test
    fun theOldSealedLengthBufferWouldHaveKeptSixteenZeroBytes() {
        val aad = ByteArray(8) { (it + 1).toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, accessUnit, aad)
        val wire = wireFor(aad, sealed)

        // What the buggy code sized: the whole sealed body, which is 16 bytes too long.
        val buggy = ByteArray(12 + AudioStreamWire.sealedPayloadLength(wire.size))
        val written = AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = wire,
            ciphertextOffset = 12,
            ciphertextLength = AudioStreamWire.sealedPayloadLength(wire.size),
            aad = wire,
            aadOffset = 4,
            aadLength = 8,
            output = buggy,
            outputOffset = 12,
        )

        // The AEAD reports the plaintext length, but the caller's buffer is 16 bytes longer and the
        // tail is exactly the zero padding the decoder used to choke on.
        assertEquals(accessUnit.size, written)
        assertEquals(16, buggy.size - (12 + written))
        for (index in 12 + written until buggy.size) {
            assertEquals("byte $index should still be the untouched zero padding", 0.toByte(), buggy[index])
        }
    }
}
