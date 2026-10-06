package com.shilapi.xcertplay.airplay

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The address the AirPlay servers bind on the CS11 kernel netdev: fe80::2 scoped to usb1.
 *
 * This ROM's libcore rejects every unscoped IPv6 bind with "Can't bind to a link-local address
 * without a scope id" — the "::" literal and even the programmatic all-zero address fail — so
 * wildcard IPv6 binds are impossible there. All servers therefore bind the exact link-local the
 * iPhone talks to (the same address the control listener already binds successfully). Falls back
 * to the all-zero wildcard for environments without the netdev (standard ROMs accept that).
 */
internal fun serverBindAddress(): InetAddress {
    try {
        val nif = NetworkInterface.getByName("usb1")
        if (nif != null) {
            val advertised = InetAddress.getByName("fe80::2").address
            val addresses = nif.inetAddresses.toList().filterIsInstance<Inet6Address>()
            val linkLocal = addresses.firstOrNull {
                it.isLinkLocalAddress && it.address.contentEquals(advertised)
            } ?: addresses.firstOrNull { it.isLinkLocalAddress }
            if (linkLocal != null) {
                return if (linkLocal.scopeId == nif.index) linkLocal
                else Inet6Address.getByAddress(null, linkLocal.address, nif.index)
            }
        }
    } catch (_: Exception) {
    }
    return Inet6Address.getByAddress(null, ByteArray(16), 0)
}

/**
 * Android 4.4 cannot bind a link-local IPv6 address without a scope id, and the platform strips
 * the scope from Socket.getLocalAddress(). Re-attach it from the owning interface so IapTunnel
 * and the data-stream listeners can bind the session's link-local address.
 */
internal fun withLinkLocalScope(address: InetAddress?): InetAddress? {
    if (address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId != 0) return address
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            val owns = nif.inetAddresses.toList().any { candidate ->
                candidate is Inet6Address && candidate.address.contentEquals(address.address)
            }
            if (owns) return Inet6Address.getByAddress(null, address.address, nif.index)
        }
        address
    } catch (_: Exception) {
        address
    }
}
