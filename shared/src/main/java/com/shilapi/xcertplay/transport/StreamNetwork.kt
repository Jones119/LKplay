package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * One accepted TCP connection the AirPlay stack can read and write.
 *
 * The wired link is carried by the kernel stack today and will be carried by a userspace stack on
 * API 19 (this ROM has no TUN, so the userspace NCM bridge needs its own TCP/IP stack,
 * docs/_digest/58 §1). The channels only ever use the two streams, so that is all this exposes; the
 * mapping onto lwIP is direct (`lwip_recv`/`lwip_send` behind the streams).
 */
interface DuplexSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val remoteAddress: InetAddress?
}

/** One listening TCP endpoint. */
interface DuplexServer : Closeable {
    val localPort: Int

    /** Blocks until the peer connects. Throws [IOException] once the endpoint is closed. */
    @Throws(IOException::class)
    fun accept(): DuplexSocket
}

/**
 * Creates listening endpoints on one network: the kernel stack, or a userspace stack on wired API 19.
 *
 * [reuseAddress] is a parameter rather than a default because the call sites genuinely differ: the
 * CarPlay channels set it, while `AirPlayPortSelector` must not (it probes for a free port, and a
 * reusable port would report a busy one as free) and `ClusterArtworkHttpServer` deliberately sets it
 * to false.
 */
interface StreamNetwork {
    /** [port] 0 lets the system choose. Throws [IOException] when the address or port cannot be bound. */
    @Throws(IOException::class)
    fun listen(
        address: InetAddress,
        port: Int = 0,
        backlog: Int = 50,
        reuseAddress: Boolean = true,
    ): DuplexServer
}

/** The whole network one session uses: the kernel stack today, a userspace stack on wired API 19. */
interface SessionNetwork : DatagramNetwork, StreamNetwork {
    companion object {
        val kernel: SessionNetwork = KernelSessionNetwork
    }
}

/** The shipped network: `java.net` sockets bound to the caller's address. */
object KernelSessionNetwork : SessionNetwork {
    override fun open(localAddress: InetAddress): DatagramTransport =
        KernelDatagramNetwork.open(localAddress)

    override fun listen(address: InetAddress, port: Int, backlog: Int, reuseAddress: Boolean): DuplexServer {
        val server = ServerSocket()
        server.reuseAddress = reuseAddress
        server.bind(InetSocketAddress(address, port), backlog)
        return KernelDuplexServer(server)
    }
}

private class KernelDuplexServer(private val server: ServerSocket) : DuplexServer {
    override val localPort: Int get() = server.localPort

    override fun accept(): DuplexSocket = KernelDuplexSocket(server.accept())

    override fun close() {
        server.close()
    }
}

private class KernelDuplexSocket(private val socket: Socket) : DuplexSocket {
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override val remoteAddress: InetAddress? get() = socket.inetAddress

    override fun close() {
        socket.close()
    }
}
