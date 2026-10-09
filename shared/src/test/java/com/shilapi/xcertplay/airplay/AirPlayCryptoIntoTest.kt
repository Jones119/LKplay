package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pooled-frame path decrypts into a caller-owned buffer instead of allocating a fresh array per
 * frame (docs/_digest/41 §9), so it has to agree byte for byte with the allocating path.
 */
class AirPlayCryptoIntoTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val nonce = AirPlayCrypto.nonce64(42)
    private val header = ByteArray(128) { (it and 0x7f).toByte() }
    private val payload = ByteArray(4096) { (it * 31 + 5).toByte() }

    @Test
    fun openIntoMatchesTheAllocatingOpen() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header)
        val expected = AirPlayCrypto.chachaOpen(key, nonce, sealed, header)

        val output = ByteArray(sealed.size)
        val written = AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = sealed,
            ciphertextOffset = 0,
            ciphertextLength = sealed.size,
            aad = header,
            aadOffset = 0,
            aadLength = header.size,
            output = output,
            outputOffset = 0,
        )

        assertEquals(payload.size, written)
        assertArrayEquals(expected, output.copyOf(written))
    }

    @Test
    fun offsetsSelectTheCiphertextAndTheAad() {
        // Mirrors the audio path: the AAD is an eight-byte slice of the wire buffer, the sealed body
        // starts after the RTP header, and the plaintext lands at the caller's offset.
        val aad = header.copyOfRange(4, 12)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, aad)
        val wire = ByteArray(12 + sealed.size + 8)
        // The AAD slice lives inside the wire buffer itself, exactly like an RTP packet's header area.
        aad.copyInto(wire, 4)
        sealed.copyInto(wire, 12)
        val output = ByteArray(wire.size)

        val written = AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = wire,
            ciphertextOffset = 12,
            ciphertextLength = sealed.size,
            aad = wire,
            aadOffset = 4,
            aadLength = 8,
            output = output,
            outputOffset = 12,
        )

        assertEquals(payload.size, written)
        assertArrayEquals(payload, output.copyOfRange(12, 12 + written))
    }

    @Test
    fun aPooledBufferLongerThanTheFrameIsFilledOnlyUpToThePlaintext() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header)
        val pool = VideoFramePool()
        val frame = pool.acquire(sealed.size - 16)

        val written = AirPlayCrypto.chachaOpenInto(
            key, nonce, sealed, 0, sealed.size, header, 0, header.size, frame.bytes, 0,
        )

        assertEquals(payload.size, written)
        assertArrayEquals(payload, frame.bytes.copyOf(written))
        frame.close()
        assertEquals(1, pool.retainedFrames())
    }

    @Test
    fun aShortOpenLeavesTheReusedTailOfThePooledBufferAlone() {
        // The guard in ScreenStream.onMessage depends on this property: openInto fills exactly the
        // plaintext it returns and touches nothing after it. Were that ever false, the decoder would
        // read the previous frame's bytes as this frame, because pooled buffers are recycled dirty.
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header)
        val frame = VideoFramePool().acquire(sealed.size + 64)
        frame.bytes.fill(0xA5.toByte())

        val written = AirPlayCrypto.chachaOpenInto(
            key, nonce, sealed, 0, sealed.size, header, 0, header.size, frame.bytes, 0,
        )

        assertEquals(sealed.size - 16, written)
        assertArrayEquals(payload, frame.bytes.copyOf(written))
        assertTrue(
            "openInto wrote past the plaintext it returned",
            frame.bytes.copyOfRange(written, frame.bytes.size).all { it == 0xA5.toByte() },
        )
        frame.close()
    }

    @Test
    fun aTamperedTagIsRejected() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        val output = ByteArray(sealed.size)

        try {
            AirPlayCrypto.chachaOpenInto(
                key, nonce, sealed, 0, sealed.size, header, 0, header.size, output, 0,
            )
            fail("expected an authentication failure")
        } catch (expected: javax.crypto.AEADBadTagException) {
            // Native AEAD reports it this way.
        } catch (expected: org.bouncycastle.crypto.InvalidCipherTextException) {
            // The BouncyCastle fallback reports the same failure with its own type.
        }
    }
}
