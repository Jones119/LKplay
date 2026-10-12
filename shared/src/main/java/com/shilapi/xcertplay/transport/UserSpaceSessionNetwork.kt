package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The [SessionNetwork] that runs on lwIP instead of the kernel stack.
 *
 * This is what makes the wired path work without root: the userspace NCM bridge claims the interfaces, and
 * every AirPlay channel binds and connects through this implementation instead of `java.net` (docs/_digest
 * §2 for the seam, §7 for the stack). The link is IPv6-only, so addresses cross to the native side as their
 * 16 raw bytes and an IPv4 address is refused rather than quietly converted.
 *
 * Blocking behaviour matches the kernel implementation on purpose: `receive` and `accept` block until data
 * arrives, and closing an endpoint unblocks whoever waits on it - the channel loops already rely on that
 * (see [DatagramTransport.receive] and [DuplexServer.accept]).
 */
internal class UserSpaceSessionNetwork(
    private val jni: UserSpaceNetworkJni = UserSpaceNetworkNative,
) : SessionNetwork {

    override fun open(localAddress: InetAddress): DatagramTransport {
        val fd = requireSocket(SOCK_DGRAM, "datagram socket")
        try {
            requireOk(jni.bind(fd, ipv6Bytes(localAddress), 0), "bind ${localAddress.hostAddress}")
        } catch (error: Throwable) {
            jni.close(fd)
            throw error
        }
        return UserSpaceDatagramTransport(fd)
    }

    override fun listen(
        address: InetAddress,
        port: Int,
        backlog: Int,
        reuseAddress: Boolean,
    ): DuplexServer {
        val fd = requireSocket(SOCK_STREAM, "stream socket")
        try {
            // AirPlayPortSelector detects a busy port by failing to bind, so the flag is always explicit.
            requireOk(
                jni.setSockOptInt(fd, SOL_SOCKET, SO_REUSEADDR, if (reuseAddress) 1 else 0),
                "setsockopt SO_REUSEADDR",
            )
            requireOk(jni.bind(fd, ipv6Bytes(address), port), "bind ${address.hostAddress}:$port")
            requireOk(jni.listen(fd, backlog), "listen")
        } catch (error: Throwable) {
            jni.close(fd)
            throw error
        }
        return UserSpaceDuplexServer(fd)
    }

    private fun requireSocket(type: Int, what: String): Int {
        val fd = jni.socket(type)
        if (fd < 0) throw IOException("lwip could not create a $what (errno ${-fd})")
        return fd
    }

    private fun requireOk(result: Int, what: String) {
        if (result < 0) throw IOException("lwip $what failed (errno ${-result})")
    }

    private fun ipv6Bytes(address: InetAddress): ByteArray {
        if (address !is Inet6Address) {
            throw IOException("the userspace NCM link is IPv6-only, not ${address.hostAddress}")
        }
        return address.address
    }

    private fun socketAddress(fd: Int, peer: Boolean): InetSocketAddress? {
        val bytes = ByteArray(16)
        val port = IntArray(1)
        val result = if (peer) jni.peerName(fd, bytes, port) else jni.localName(fd, bytes, port)
        if (result < 0) return null
        return runCatching { InetSocketAddress(InetAddress.getByAddress(bytes), port[0]) }.getOrNull()
    }

    private inner class UserSpaceDatagramTransport(private val fd: Int) : DatagramTransport {
        private var closed = false
        private var requestedReceiveBuffer = 0

        override val localPort: Int get() = socketAddress(fd, peer = false)?.port ?: 0

        override var receiveBufferSize: Int
            get() = requestedReceiveBuffer
            set(value) {
                // lwIP has no getsockopt for SO_RCVBUF through this surface, so the requested value is what
                // the caller reads back - the audio path logs it either way.
                requestedReceiveBuffer = value
                runCatching { jni.setSockOptInt(fd, SOL_SOCKET, SO_RCVBUF, value) }
            }

        override fun send(data: ByteArray, size: Int, address: InetAddress, port: Int) {
            requireOk(jni.sendTo(fd, data, 0, size, ipv6Bytes(address), port), "sendto $port")
        }

        override fun receive(buffer: ByteArray): ReceivedDatagram {
            val from = ByteArray(16)
            val fromPort = IntArray(1)
            val received = jni.recvFrom(fd, buffer, 0, buffer.size, from, fromPort)
            if (received < 0) throw IOException("lwip recvfrom failed (errno ${-received})")
            val address = runCatching { InetAddress.getByAddress(from) }.getOrNull()
            return ReceivedDatagram(received, address, fromPort[0])
        }

        override fun close() {
            if (closed) return
            closed = true
            jni.close(fd)
        }
    }

    private inner class UserSpaceDuplexServer(private val fd: Int) : DuplexServer {
        private var closed = false

        override val localPort: Int get() = socketAddress(fd, peer = false)?.port ?: 0

        override val localSocketAddress: InetSocketAddress? get() = socketAddress(fd, peer = false)

        override val isClosed: Boolean get() = closed

        override var soTimeout: Int = 0
            set(value) {
                field = value
                runCatching { jni.setSockOptInt(fd, SOL_SOCKET, SO_RCVTIMEO, value) }
            }

        override fun accept(): DuplexSocket {
            val accepted = jni.accept(fd)
            if (accepted < 0) {
                throw IOException(if (closed) "listener closed" else "lwip accept failed (errno ${-accepted})")
            }
            return UserSpaceDuplexSocket(accepted)
        }

        override fun close() {
            if (closed) return
            closed = true
            jni.close(fd)
        }
    }

    private inner class UserSpaceDuplexSocket(private val fd: Int) : DuplexSocket {
        private var closed = false
        private var requestedReceiveBuffer = 0

        override val inputStream: InputStream = object : InputStream() {
            private val single = ByteArray(1)

            override fun read(): Int {
                val count = read(single, 0, 1)
                return if (count <= 0) -1 else single[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                // lwip_recv returns 0 for an orderly close, which is this stream's EOF.
                val received = jni.recv(fd, buffer, offset, length)
                if (received < 0) throw IOException("lwip recv failed (errno ${-received})")
                return if (received == 0) -1 else received
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                var written = 0
                while (written < length) {
                    val sent = jni.send(fd, buffer, offset + written, length - written)
                    if (sent <= 0) throw IOException("lwip send failed (errno ${if (sent < 0) -sent else 0})")
                    written += sent
                }
            }
        }

        override val remoteSocketAddress: InetSocketAddress? get() = socketAddress(fd, peer = true)

        override val localSocketAddress: InetSocketAddress? get() = socketAddress(fd, peer = false)

        override var receiveBufferSize: Int
            get() = requestedReceiveBuffer
            set(value) {
                requestedReceiveBuffer = value
                runCatching { jni.setSockOptInt(fd, SOL_SOCKET, SO_RCVBUF, value) }
            }

        override var tcpNoDelay: Boolean = false
            set(value) {
                field = value
                runCatching { jni.setSockOptInt(fd, IPPROTO_TCP, TCP_NODELAY, if (value) 1 else 0) }
            }

        override var keepAlive: Boolean = false
            set(value) {
                field = value
                runCatching { jni.setSockOptInt(fd, SOL_SOCKET, SO_KEEPALIVE, if (value) 1 else 0) }
            }

        override fun setSoLinger(on: Boolean, seconds: Int) {
            runCatching { jni.setSockOptLinger(fd, on, seconds) }
        }

        override fun close() {
            if (closed) return
            closed = true
            jni.close(fd)
        }
    }

    internal companion object {
        /** Linux/bionic values, which lwIP mirrors; kept here so the JNI layer stays free of constants. */
        const val AF_INET6 = 10
        const val SOCK_STREAM = 1
        const val SOCK_DGRAM = 2
        const val SOL_SOCKET = 1
        const val SO_REUSEADDR = 2
        const val SO_RCVBUF = 8
        const val SO_KEEPALIVE = 9
        const val SO_RCVTIMEO = 20
        const val IPPROTO_TCP = 6
        const val TCP_NODELAY = 1

        /** What [Closeable] users of this network should report when the stack is not in the APK. */
        fun unavailable(): IOException =
            IOException("the userspace NCM bridge needs liblwip_bridge.so, which is not in this APK")
    }
}
