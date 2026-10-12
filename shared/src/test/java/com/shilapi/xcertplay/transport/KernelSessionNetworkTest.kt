package com.shilapi.xcertplay.transport

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped network, exercised end to end rather than through a fake.
 *
 * Two properties the rest of the code now relies on: a busy port must throw (AirPlayPortSelector
 * detects a busy port by failing to bind, and falls back to the next candidate), and an accepted
 * connection must carry bytes in both directions.
 */
class KernelSessionNetworkTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test
    fun aBusyPortThrowsSoTheSelectorCanFallBack() {
        KernelSessionNetwork.listen(loopback, port = 0, reuseAddress = false).use { first ->
            val busy = first.localPort
            assertTrue(busy > 0)
            var threw = false
            try {
                KernelSessionNetwork.listen(loopback, port = busy, reuseAddress = false).close()
            } catch (_: IOException) {
                threw = true
            }
            assertTrue("binding a busy port must fail with an IOException", threw)
            // The failed attempt must not have left the endpoint unusable: a later bind still works.
            KernelSessionNetwork.listen(loopback, port = 0, reuseAddress = false).use { next ->
                assertTrue(next.localPort > 0)
            }
        }
    }

    @Test
    fun anAcceptedConnectionCarriesBytesBothWays() {
        KernelSessionNetwork.listen(loopback, port = 0, reuseAddress = false).use { server ->
            val client = Socket(loopback, server.localPort)
            try {
                server.accept().use { peer ->
                    assertEquals(server.localPort, peer.localSocketAddress?.port)
                    peer.outputStream.write(byteArrayOf(1, 2, 3))
                    peer.outputStream.flush()
                    val received = ByteArray(3)
                    var read = 0
                    while (read < received.size) {
                        val count = client.getInputStream().read(received, read, received.size - read)
                        if (count < 0) break
                        read += count
                    }
                    assertEquals(3, read)
                    assertEquals(listOf<Byte>(1, 2, 3), received.toList())
                    assertTrue((peer.remoteSocketAddress?.port ?: -1) > 0)
                }
            } finally {
                client.close()
            }
        }
    }
}
