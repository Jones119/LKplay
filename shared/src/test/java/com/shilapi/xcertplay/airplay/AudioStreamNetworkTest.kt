package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.DatagramNetwork
import com.shilapi.xcertplay.transport.DatagramTransport
import com.shilapi.xcertplay.transport.ReceivedDatagram
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio stream's two UDP endpoints on a network the test owns.
 *
 * The wired path has to be able to run its channels on a stack that is not the kernel's (docs/_digest/58
 * §2), so `AudioStream` takes both endpoints - RTP data and RTCP control - from a [DatagramNetwork].
 * This test pins what the migration must not change: which ports are advertised to the phone, that the
 * receive-buffer request still reaches the endpoint, and that `close()` releases both.
 */
class AudioStreamNetworkTest {
    private val listener = object : AudioStream.Listener {}

    @Test
    fun listenAdvertisesBothEndpointPortsAndRequestsTheReceiveBuffer() {
        val data = FakeTransport(5_000)
        val control = FakeTransport(5_001)
        val stream = AudioStream(
            key = ByteArray(32),
            streamType = 100,
            network = FixedNetwork(data, control),
        )
        try {
            val (dataPort, controlPort) = stream.listen(listener)
            assertEquals(5_000, dataPort)
            assertEquals(5_001, controlPort)
            assertTrue(
                "the audio path must still raise the data endpoint's receive buffer",
                data.receiveBufferWrites.isNotEmpty(),
            )
        } finally {
            stream.close()
        }
    }

    @Test
    fun closeReleasesBothEndpoints() {
        val data = FakeTransport(5_002)
        val control = FakeTransport(5_003)
        val stream = AudioStream(
            key = ByteArray(32),
            streamType = 100,
            network = FixedNetwork(data, control),
        )
        stream.listen(listener)
        stream.close()
        assertTrue("close must reach the RTP endpoint", data.closed.get())
        assertTrue("close must reach the RTCP endpoint", control.closed.get())
    }

    /** Hands out the two endpoints in the order `AudioStream.listen` asks for them. */
    private class FixedNetwork(
        private val data: DatagramTransport,
        private val control: DatagramTransport,
    ) : DatagramNetwork {
        private var opened = 0

        override fun open(localAddress: InetAddress): DatagramTransport =
            if (opened++ == 0) data else control
    }

    private class FakeTransport(private val port: Int) : DatagramTransport {
        val closed = AtomicBoolean(false)
        val receiveBufferWrites = ArrayList<Int>()

        override val localPort: Int get() = port

        override var receiveBufferSize: Int = 0
            set(value) {
                field = value
                receiveBufferWrites.add(value)
            }

        override fun send(data: ByteArray, size: Int, address: InetAddress, port: Int) = Unit

        override fun receive(buffer: ByteArray): ReceivedDatagram {
            val inbound = ArrayBlockingQueue<ByteArray>(1)
            while (true) {
                if (closed.get()) throw IOException("closed")
                val packet = inbound.poll(20, TimeUnit.MILLISECONDS) ?: continue
                packet.copyInto(buffer)
                return ReceivedDatagram(packet.size, null, 0)
            }
        }

        override fun close() {
            closed.set(true)
        }
    }
}
