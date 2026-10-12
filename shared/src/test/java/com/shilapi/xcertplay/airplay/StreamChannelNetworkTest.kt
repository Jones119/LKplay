package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.DuplexServer
import com.shilapi.xcertplay.transport.DuplexSocket
import com.shilapi.xcertplay.transport.StreamNetwork
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TCP channels on a network the test owns.
 *
 * The wired path must be able to listen on a stack that is not the kernel's (docs/_digest/58 §2), so
 * both channels take their listener from a [StreamNetwork]. These tests pin the two things the
 * migration must not change: the port that gets advertised to the phone comes from that network, and
 * the arguments the channels bind with (the link-local address, `reuseAddress`) still reach it.
 */
class StreamChannelNetworkTest {
    private val bindAddress: InetAddress = InetAddress.getByName("fe80::1")

    @Test
    fun videoSettingsListensOnTheInjectedNetwork() {
        val server = FakeServer(7_111)
        val network = RecordingNetwork(server)
        val channel = VideoSettingsChannel(ByteArray(32), ByteArray(32), network, log = {})
        try {
            assertEquals(7_111, channel.listen(bindAddress))
            assertEquals(bindAddress, network.addressSeen)
            assertEquals("the channel binds reusable, as it did before", true, network.reuseAddressSeen)
        } finally {
            channel.close()
        }
        assertTrue("close must reach the listener", server.closed.get())
    }

    @Test
    fun screenStreamListensOnTheInjectedNetwork() {
        val server = FakeServer(7_222)
        val network = RecordingNetwork(server)
        val stream = ScreenStream(key = ByteArray(32), onDiagnostic = {}, network = network)
        try {
            // Unlike the video-settings channel this one picks its own address (serverBindAddress),
            // so only the port and the bind flags are this test's business.
            assertEquals(7_222, stream.listen(object : ScreenStream.Listener {}))
            assertEquals(true, network.reuseAddressSeen)
        } finally {
            stream.close()
        }
        assertTrue("close must reach the listener", server.closed.get())
    }

    /** Records how a channel asks for its listener, and hands back the endpoint the test owns. */
    private class RecordingNetwork(private val server: DuplexServer) : StreamNetwork {
        var addressSeen: InetAddress? = null
        var reuseAddressSeen: Boolean? = null

        override fun listen(
            address: InetAddress,
            port: Int,
            backlog: Int,
            reuseAddress: Boolean,
        ): DuplexServer {
            addressSeen = address
            reuseAddressSeen = reuseAddress
            return server
        }
    }

    /** Blocks in accept until it is closed, exactly like an unused listener. */
    private class FakeServer(private val port: Int) : DuplexServer {
        val closed = AtomicBoolean(false)

        override val localPort: Int get() = port

        override fun accept(): DuplexSocket {
            while (!closed.get()) Thread.sleep(10)
            throw IOException("closed")
        }

        override fun close() {
            closed.set(true)
        }
    }
}
