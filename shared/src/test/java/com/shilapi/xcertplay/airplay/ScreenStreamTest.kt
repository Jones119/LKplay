package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the screen stream's receive loop, driven through a real loopback socket
 * (docs/_digest/44).
 *
 * The frame body is not authenticated until it decrypts and the listener does not validate its peer,
 * so a malformed frame must leave the stream running: an exception out of `onMessage` is caught by
 * `run` and closes the whole stream, which is what a 128-byte header plus a four-byte body used to
 * achieve from any host on the link.
 */
class ScreenStreamTest {
    private val key = ByteArray(32) { (it * 5 + 3).toByte() }

    @Test
    fun aClearFrameWithAnOverflowingNalLengthIsDeliveredWithoutClosingTheStream() {
        val body = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        val received = CopyOnWriteArrayList<ByteArray>()
        val failure = AtomicReference<Throwable?>()
        val delivered = CountDownLatch(1)
        val stream = ScreenStream(key)
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) {
                received += naluBytes
                delivered.countDown()
            }

            override fun onClosed(cause: Throwable?) {
                // A null cause is the socket ending normally, which is not the failure under test.
                if (cause != null) {
                    failure.compareAndSet(null, cause)
                    delivered.countDown()
                }
            }
        })
        try {
            // A body shorter than the AEAD tag takes the clear-frame branch, exactly like the
            // unauthenticated frame the audit described.
            Socket(InetAddress.getByName("::1"), port).use { socket ->
                socket.getOutputStream().apply {
                    write(frameHeader(body.size))
                    write(body)
                    flush()
                }
                assertTrue(
                    "no frame delivered; the stream closed with ${failure.get()}",
                    delivered.await(5, TimeUnit.SECONDS),
                )
            }
            assertNull("a malformed frame closed the stream: ${failure.get()}", failure.get())
            assertArrayEquals(body, received.single())
        } finally {
            stream.close()
        }
    }

    @Test
    fun aSealedFrameIsDeliveredWholeAndWithoutADiagnostic() {
        val plaintext = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(2048) { (it and 0xff).toByte() }
        // The header's body size is what the pooled frame is sized from, and the sealed body is the
        // ciphertext plus the 16-byte Poly1305 tag, so the two agree only for this body size.
        val header = frameHeader(plaintext.size + TAG_BYTES)
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plaintext, header)
        assertEquals(plaintext.size + TAG_BYTES, sealed.size)
        val diagnostics = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<ByteArray>()
        val delivered = CountDownLatch(1)
        val stream = ScreenStream(key) { diagnostics += it }
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) {
                received += naluBytes
                delivered.countDown()
            }
        })
        try {
            Socket(InetAddress.getByName("::1"), port).use { socket ->
                socket.getOutputStream().apply {
                    write(header)
                    write(sealed)
                    flush()
                }
                assertTrue("no frame delivered", delivered.await(5, TimeUnit.SECONDS))
            }
            // The payload already starts with an Annex B start code, so the in-place conversion
            // leaves it alone and the delivered frame is the plaintext byte for byte.
            assertArrayEquals(plaintext, received.single())
            assertTrue(
                "the plaintext length check fired on a valid frame: $diagnostics",
                diagnostics.none { it.contains("plaintext mismatch") },
            )
        } finally {
            stream.close()
        }
    }

    /** The 128-byte AirPlayScreenHeader; offset 0 is the little-endian body size, opcode 0 is video. */
    private fun frameHeader(bodySize: Int): ByteArray = ByteArray(128).also {
        it[0] = (bodySize and 0xff).toByte()
        it[1] = ((bodySize ushr 8) and 0xff).toByte()
        it[2] = ((bodySize ushr 16) and 0xff).toByte()
        it[3] = ((bodySize ushr 24) and 0xff).toByte()
        it[4] = 0
    }

    private companion object {
        /** ChaCha20-Poly1305 tag appended to every sealed video frame. */
        const val TAG_BYTES = 16
    }
}
