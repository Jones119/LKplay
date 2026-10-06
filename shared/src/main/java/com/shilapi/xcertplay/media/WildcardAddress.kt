package com.shilapi.xcertplay.media

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The address the media servers bind on the CS11 kernel netdev: fe80::2 scoped to usb1.
 *
 * This ROM's libcore rejects every unscoped IPv6 bind ("Can't bind to a link-local address
 * without a scope id"), including the "::" literal and the all-zero address, so wildcard IPv6
 * binds are impossible there. Bind the exact link-local the iPhone talks to instead. Falls back
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
