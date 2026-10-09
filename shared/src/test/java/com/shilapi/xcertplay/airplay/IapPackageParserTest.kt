package com.shilapi.xcertplay.airplay

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * APTransportPackage framing for the iAP tunnel (docs/_digest/44).
 *
 * The declared size is the only thing that says where the next package starts, so a size outside the
 * header/framing range means the framing is lost. The parser used to keep the unparseable tail, and
 * `IapTunnel.run` appends to it on every read: quadratic copying of a buffer that grows without
 * bound. It must fail the tunnel instead, the way an undecryptable frame already does.
 */
class IapPackageParserTest {
    private val packages = mutableListOf<Pair<Int, ByteArray>>()
    private val parser = IapPackageParser { messageType, body -> packages += messageType to body }

    @Test
    fun aSizeBelowThePackageHeaderFailsInsteadOfRetainingTheTail() {
        val buffer = ByteArray(64)
        putU32Be(buffer, 0, 8)

        try {
            parser.parse(buffer)
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertTrue("message lost the size: ${expected.message}", expected.message?.contains("8") == true)
        }
        assertTrue(packages.isEmpty())
    }

    @Test
    fun aSizeAboveTheMaximumPackageFails() {
        val buffer = ByteArray(64)
        putU32Be(buffer, 0, 4 * 1024 * 1024 + 1)

        try {
            parser.parse(buffer)
            fail("expected an IOException")
        } catch (_: IOException) {
            // The framing is unrecoverable, so the tunnel is failed rather than wedged.
        }
        assertTrue(packages.isEmpty())
    }

    @Test
    fun completePackagesAreDeliveredAndOnlyTheIncompleteTailIsRetained() {
        val body = byteArrayOf(1, 2, 3)
        val complete = packageOf(size = 32 + body.size, messageType = MSG_TYPE_COMM, body = body)
        val partial = ByteArray(24) { 0x5A }

        val tail = parser.parse(complete + partial)

        assertEquals(1, packages.size)
        assertEquals(MSG_TYPE_COMM, packages.single().first)
        assertArrayEquals(body, packages.single().second)
        assertArrayEquals(partial, tail)
    }

    @Test
    fun aNonCommPackageIsReportedWithoutItsBodyBeingForwarded() {
        val complete = packageOf(size = 40, messageType = 0x73796e63, body = ByteArray(8) { 9 })

        val tail = parser.parse(complete)

        assertEquals(0, tail.size)
        assertEquals(1, packages.size)
        // The tunnel filters on the message type; the parser reports every complete package.
        assertEquals(0x73796e63, packages.single().first)
        assertArrayEquals(ByteArray(8) { 9 }, packages.single().second)
    }

    private fun packageOf(size: Int, messageType: Int, body: ByteArray): ByteArray {
        val packageBytes = ByteArray(size)
        putU32Be(packageBytes, 0, size)
        putU32Be(packageBytes, 16, messageType)
        body.copyInto(packageBytes, 32)
        return packageBytes
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private companion object {
        const val MSG_TYPE_COMM = 0x636f6d6d
    }
}
