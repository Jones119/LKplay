package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** One received datagram: [size] leading bytes of the caller's buffer, and where it came from. */
class ReceivedDatagram(val size: Int, val address: InetAddress?, val port: Int)

/**
 * One UDP endpoint the AirPlay stack can receive on and send from.
 *
 * The wired link is carried by the kernel stack today and will be carried by a userspace stack on
 * API 19 (this ROM has no TUN, so the userspace NCM bridge needs a TCP/IP stack of its own,
 * docs/_digest/58 §1). The protocol code must not care which of the two it owns, so it talks to this
 * interface instead of `java.net.DatagramSocket`; the mapping is deliberately 1:1, including
 * [receive] throwing when the endpoint closes, because the receive loops already rely on that.
 */
interface DatagramTransport : Closeable {
    val localPort: Int

    /**
     * Receive buffer in bytes. The audio path raises it to ride out short decrypt/scheduling pauses
     * and then reads it back because the platform may cap the request; a userspace stack maps this
     * onto lwIP's `SO_RCVBUF` and may ignore it.
     */
    var receiveBufferSize: Int

    fun send(data: ByteArray, size: Int, address: InetAddress, port: Int)

    /** Blocks until a datagram arrives. Throws [IOException] when the endpoint is closed or fails. */
    @Throws(IOException::class)
    fun receive(buffer: ByteArray): ReceivedDatagram
}

/** Creates endpoints on one network: the kernel stack, or a userspace stack on wired API 19. */
interface DatagramNetwork {
    fun open(localAddress: InetAddress): DatagramTransport
}

/** The shipped network: a `java.net.DatagramSocket` bound to the caller's local address. */
object KernelDatagramNetwork : DatagramNetwork {
    override fun open(localAddress: InetAddress): DatagramTransport {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        // This ROM rejects unscoped IPv6 binds entirely; the caller hands us the netdev link-local
        // (see NtpClock.listen and WirelessHostAddress).
        socket.bind(InetSocketAddress(localAddress, 0))
        return KernelDatagramTransport(socket)
    }
}

private class KernelDatagramTransport(private val socket: DatagramSocket) : DatagramTransport {
    override val localPort: Int get() = socket.localPort

    override var receiveBufferSize: Int
        get() = socket.receiveBufferSize
        set(value) {
            socket.receiveBufferSize = value
        }

    override fun send(data: ByteArray, size: Int, address: InetAddress, port: Int) {
        socket.send(DatagramPacket(data, size, address, port))
    }

    override fun receive(buffer: ByteArray): ReceivedDatagram {
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        return ReceivedDatagram(packet.length, packet.address, packet.port)
    }

    override fun close() {
        socket.close()
    }
}
