package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The lwIP socket layer, driven by a fake JNI.
 *
 * The library only loads on the car, so the value of these tests is the contract around it: the call
 * sequence a listener has to make (reuse before bind before listen), the IPv6-only address marshalling, the
 * errno-to-exception mapping, and that a closed endpoint releases its descriptor exactly once.
 */
class UserSpaceSessionNetworkTest {
    private val linkLocal = InetAddress.getByName("fe80::2") as Inet6Address
    private val phone = InetAddress.getByName("fe80::4d:abf3:6b34:a259") as Inet6Address

    private class FakeJni : UserSpaceNetworkJni {
        val calls = ArrayList<String>()
        val descriptors = ArrayList<Int>()
        var failWith = 0
        var failBind = false
        var nextDescriptor = 10
        var received = ByteArray(0)
        private var receivedOffset = 0
        var receivedFrom: ByteArray = ByteArray(16)
        var receivedPort = 0
        var acceptDescriptor = 55

        override fun start(hostMac: ByteArray?, mtu: Int): Int {
            calls.add("start")
            return 0
        }

        override fun injectFrame(frame: ByteArray, length: Int) = 0
        override fun pollTxFrame(out: ByteArray) = 0
        override fun txPending() = 0
        override fun stats() = intArrayOf(0, 0, 0, 0, 0)

        override fun socket(type: Int): Int {
            calls.add("socket$type")
            return if (failWith != 0) -failWith else nextDescriptor.also { descriptors.add(it); nextDescriptor++ }
        }

        override fun bind(fd: Int, address: ByteArray, port: Int): Int {
            calls.add("bind:${address.joinToString("") { "%02x".format(it) }}:$port")
            return if (failWith != 0) -failWith else if (failBind) -98 else 0
        }

        override fun listen(fd: Int, backlog: Int): Int {
            calls.add("listen$backlog")
            return if (failWith != 0) -failWith else 0
        }

        override fun accept(fd: Int): Int {
            calls.add("accept")
            return if (failWith != 0) -failWith else acceptDescriptor
        }

        override fun recv(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int {
            // A stream: each read continues where the previous one stopped, and 0 is EOF. The test replaces
            // `received` to simulate EOF, so the offset is clamped to the current buffer.
            val start = receivedOffset.coerceAtMost(received.size)
            val remaining = received.size - start
            val count = minOf(length, remaining)
            received.copyInto(buffer, offset, start, start + count)
            receivedOffset = start + count
            return count
        }

        override fun send(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int = length

        override fun sendTo(
            fd: Int,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            address: ByteArray,
            port: Int,
        ): Int {
            calls.add("sendTo:${address.joinToString("") { "%02x".format(it) }}:$port")
            return if (failWith != 0) -failWith else length
        }

        override fun recvFrom(
            fd: Int,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            fromAddress: ByteArray,
            fromPort: IntArray,
        ): Int {
            val count = minOf(length, received.size)
            received.copyInto(buffer, offset, 0, count)
            receivedFrom.copyInto(fromAddress)
            fromPort[0] = receivedPort
            return count
        }

        override fun close(fd: Int): Int {
            calls.add("close$fd")
            return 0
        }

        override fun setSockOptInt(fd: Int, level: Int, option: Int, value: Int): Int {
            calls.add("setsockopt:$option=$value")
            return 0
        }

        override fun setSockOptLinger(fd: Int, on: Boolean, seconds: Int): Int {
            calls.add("linger:$on:$seconds")
            return 0
        }

        override fun localName(fd: Int, address: ByteArray, port: IntArray): Int {
            linkLocalBytes.copyInto(address)
            port[0] = 7000
            return 0
        }

        override fun peerName(fd: Int, address: ByteArray, port: IntArray): Int {
            phoneBytes.copyInto(address)
            port[0] = 53668
            return 0
        }

        companion object {
            val linkLocalBytes = InetAddress.getByName("fe80::2").address
            val phoneBytes = InetAddress.getByName("fe80::4d:abf3:6b34:a259").address
        }
    }

    @Test
    fun aListenerReusesBeforeBindingAndListening() {
        val jni = FakeJni()
        val network = UserSpaceSessionNetwork(jni)
        val server = network.listen(linkLocal, 7000, backlog = 50, reuseAddress = true)
        assertEquals("socket1", jni.calls[0])
        assertEquals("setsockopt:2=1", jni.calls[1])
        assertTrue("bind was ${jni.calls[2]}", jni.calls[2].startsWith("bind:fe80") && jni.calls[2].endsWith(":7000"))
        assertEquals("listen50", jni.calls[3])
        assertTrue(server.localPort > 0)
        server.close()
        assertTrue(server.isClosed)
        assertEquals(listOf(10), jni.descriptors.take(1))
    }

    @Test
    fun aDatagramTransportBindsToPortZeroAndSendsToThePhone() {
        val jni = FakeJni()
        val network = UserSpaceSessionNetwork(jni)
        val transport = network.open(linkLocal)
        assertTrue(jni.calls.first().startsWith("socket2"))
        assertTrue(jni.calls.any { it.startsWith("bind:") && it.endsWith(":0") })
        transport.send(ByteArray(4), 4, phone, 53668)
        assertTrue(jni.calls.last().startsWith("sendTo:") && jni.calls.last().endsWith(":53668"))
        transport.receiveBufferSize = 262_144
        assertEquals(262_144, transport.receiveBufferSize)
        assertTrue(jni.calls.contains("setsockopt:8=262144"))
    }

    @Test
    fun aDatagramTransportReportsTheSenderOfWhatItReceived() {
        val jni = FakeJni()
        jni.received = byteArrayOf(1, 2, 3, 4, 5)
        jni.receivedFrom = FakeJni.phoneBytes
        jni.receivedPort = 53668
        val transport = UserSpaceSessionNetwork(jni).open(linkLocal)
        val datagram = transport.receive(ByteArray(64))
        assertEquals(5, datagram.size)
        assertEquals(53668, datagram.port)
        assertEquals(phone, datagram.address)
        transport.close()
    }

    @Test
    fun aFailureClosesTheDescriptorAndNamesTheCall() {
        val jni = FakeJni()
        jni.failBind = true
        try {
            UserSpaceSessionNetwork(jni).listen(linkLocal, 7000, 50, true)
            fail("listen should have failed")
        } catch (error: IOException) {
            // The message names the call and keeps the errno, which is what a car log has to show.
            assertTrue("message was ${error.message}", error.message!!.contains("bind "))
            assertTrue("message was ${error.message}", error.message!!.contains("errno 98"))
        }
        // The socket that failed to bind must not leak: exactly one close, of the descriptor it opened.
        assertEquals(1, jni.calls.count { it == "close10" })
    }

    @Test
    fun anIpv4AddressIsRefusedRatherThanConverted() {
        val jni = FakeJni()
        try {
            UserSpaceSessionNetwork(jni).open(InetAddress.getByName("127.0.0.1"))
            fail("an IPv4 address must be refused")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("IPv6-only"))
        }
    }

    @Test
    fun aStreamSocketExposesItsNamesAndOptions() {
        val jni = FakeJni()
        val server = UserSpaceSessionNetwork(jni).listen(linkLocal, 7000, 50, false)
        val socket = server.accept()
        assertNotNull(socket.remoteSocketAddress)
        assertEquals(53668, socket.remoteSocketAddress!!.port)
        assertEquals(7000, socket.localSocketAddress!!.port)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.receiveBufferSize = 4096
        socket.setSoLinger(true, 1)
        assertTrue(jni.calls.contains("setsockopt:1=1"))
        assertTrue(jni.calls.contains("setsockopt:9=1"))
        assertTrue(jni.calls.contains("setsockopt:8=4096"))
        assertTrue(jni.calls.contains("linger:true:1"))
        socket.close()
        server.close()
    }

    @Test
    fun theOutputStreamWritesEverythingAndTheInputStreamReportsEof() {
        val jni = FakeJni()
        jni.received = byteArrayOf(9, 8, 7)
        val socket = UserSpaceSessionNetwork(jni).listen(linkLocal, 7000, 50, true).accept()
        socket.outputStream.write(ByteArray(300))
        assertEquals(9, socket.inputStream.read())
        assertEquals(8, socket.inputStream.read())
        jni.received = ByteArray(0)
        assertEquals(-1, socket.inputStream.read())
        socket.close()
    }
}
