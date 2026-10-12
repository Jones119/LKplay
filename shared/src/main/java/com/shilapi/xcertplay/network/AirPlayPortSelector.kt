package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.DuplexServer
import com.shilapi.xcertplay.transport.KernelSessionNetwork
import com.shilapi.xcertplay.transport.StreamNetwork
import java.io.Closeable
import java.net.BindException
import java.net.InetAddress

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 *
 * The listeners come from a [StreamNetwork] so the wired path can serve them from a userspace stack
 * later (docs/_digest/58 §2). Every bind here is deliberately **non-reusable**: this class detects a
 * busy port by failing to bind, and `reuseAddress = true` would report a busy port as free.
 */
object AirPlayPortSelector {
    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    /** Specific per-family listeners avoid relying on a platform's IPV6_V6ONLY default. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        network: StreamNetwork = KernelSessionNetwork,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<DuplexServer> {
        require(addresses.isNotEmpty()) { "At least one listener address is required" }
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        for (candidate in candidates) {
            val servers = mutableListOf<DuplexServer>()
            try {
                for (address in addresses.distinct()) {
                    servers.add(bindPort(network, address, servers.firstOrNull()?.localPort ?: candidate))
                }
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                if (error is BindException) continue
                throw error
            }
            try {
                val port = servers.first().localPort
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return servers
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                throw error
            }
        }
        throw BindException("No common AirPlay port available for the selected interface addresses")
    }

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        network: StreamNetwork = KernelSessionNetwork,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): DuplexServer {
        tryBind(network, address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(network, address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        return reportFallback(bindPort(network, address, 0), preferredPort, onFallback)
    }

    private fun tryBind(network: StreamNetwork, address: InetAddress, port: Int): DuplexServer? = try {
        bindPort(network, address, port)
    } catch (_: BindException) {
        null
    }

    /**
     * Unlike the old `ServerSocket` form there is no half-bound object to release: [StreamNetwork.listen]
     * either returns a listening endpoint or throws, so a failure leaves nothing behind.
     */
    private fun bindPort(network: StreamNetwork, address: InetAddress, port: Int): DuplexServer =
        network.listen(address, port = port, reuseAddress = false)

    private fun reportFallback(
        server: DuplexServer,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): DuplexServer = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // Ownership transfers to the caller only after notification succeeds.
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: Closeable, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}
