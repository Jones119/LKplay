package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.DatagramNetwork
import com.shilapi.xcertplay.transport.DatagramTransport
import com.shilapi.xcertplay.transport.ReceivedDatagram
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing port on a network the test owns: no socket is opened, the fake plays the phone.
 *
 * This is the sample migration the userspace NCM route needs - the wired path must be able to run its
 * channels on a stack that is not the kernel's (docs/_digest/58 §2), so `NtpClock` takes its endpoint
 * from a [DatagramNetwork] and this test proves the timing behaviour survives the indirection.
 */
class NtpClockNetworkTest {
    // The timing-port wire constants, spelled out: NtpClock keeps its companion private, and these are
    // protocol values rather than implementation details (PT_REQUEST/PT_RESPONSE, 32-byte RTCP packets).
    private val PT_REQUEST = 210
    private val PT_RESPONSE = 211
    private val NTP_PACKET_BYTES = 32
    private val NTP_ORIGINATE_OFFSET = 8
    private val NTP_RECEIVE_OFFSET = 16
    private val NTP_TRANSMIT_OFFSET = 24
    private val peerAddress: InetAddress = InetAddress.getByName("fe80::1")
    private val peerPort = 7_000

    @Test
    fun listenReturnsTheEndpointsPortAndCloseReleasesIt() {
        val endpoint = FakeTransport(port = 4_321)
        val clock = NtpClock(FakeNetwork(endpoint))
        assertEquals(4_321, clock.listen())
        clock.close()
        assertTrue("close must reach the transport", endpoint.closed.get())
    }

    @Test
    fun anIncomingRequestIsAnsweredWithTheOriginTimestamp() {
        val endpoint = FakeTransport(port = 4_322)
        val clock = NtpClock(FakeNetwork(endpoint))
        clock.listen()
        try {
            val request = ByteArray(NTP_PACKET_BYTES)
            request[0] = 0x80.toByte()
            request[1] = PT_REQUEST.toByte()
            for (offset in 0 until 8) {
                request[NTP_TRANSMIT_OFFSET + offset] = (0xA0 + offset).toByte()
            }
            endpoint.inbound.add(request)

            val response = endpoint.awaitOutbound()
            assertEquals(PT_RESPONSE, response.data[1].toInt() and 0xff)
            assertEquals("the response must echo the request's transmit stamp", peerPort, response.port)
            assertArrayEquals(
                "PT_RESPONSE copies the request transmit stamp into the originate field",
                request.copyOfRange(NTP_TRANSMIT_OFFSET, NTP_PACKET_BYTES),
                response.data.copyOfRange(NTP_ORIGINATE_OFFSET, NTP_RECEIVE_OFFSET),
            )
        } finally {
            clock.close()
        }
    }

    @Test
    fun theSenderEmitsARequestToTheTimingPort() {
        val endpoint = FakeTransport(port = 4_323)
        val clock = NtpClock(FakeNetwork(endpoint))
        clock.listen()
        try {
            clock.start(peerAddress, peerPort)
            val sent = endpoint.awaitOutbound()
            assertEquals(PT_REQUEST, sent.data[1].toInt() and 0xff)
            assertEquals("requests go to the peer's timing port", peerPort, sent.port)
            assertEquals(peerAddress, sent.address)
        } finally {
            clock.close()
        }
    }

    private class FakeNetwork(private val endpoint: DatagramTransport) : DatagramNetwork {
        var opened: InetAddress? = null
            private set

        override fun open(localAddress: InetAddress): DatagramTransport {
            opened = localAddress
            return endpoint
        }
    }

    private class FakeTransport(private val port: Int) : DatagramTransport {
        val inbound = ArrayBlockingQueue<ByteArray>(8)
        val outbound = ArrayBlockingQueue<Sent>(8)
        val closed = AtomicBoolean(false)

        override val localPort: Int get() = port

        override var receiveBufferSize: Int = 0

        fun awaitOutbound(): Sent = outbound.poll(2, TimeUnit.SECONDS)
            ?: throw AssertionError("the clock sent nothing within two seconds")

        override fun send(data: ByteArray, size: Int, address: InetAddress, port: Int) {
            outbound.add(Sent(data.copyOf(size), address, port))
        }

        override fun receive(buffer: ByteArray): ReceivedDatagram {
            while (true) {
                if (closed.get()) throw IOException("closed")
                val packet = inbound.poll(20, TimeUnit.MILLISECONDS) ?: continue
                packet.copyInto(buffer)
                return ReceivedDatagram(packet.size, InetAddress.getByName("fe80::1"), 7_000)
            }
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class Sent(val data: ByteArray, val address: InetAddress, val port: Int)
}
