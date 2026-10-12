package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    val selected = HotspotAddressPolicy.select(addresses) ?: return null
    if (selected is Inet6Address && interfaceIndex > 0) {
        return Inet6Address.getByAddress(null, selected.address, interfaceIndex)
    }
    return selected
}

/**
 * Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6.
 */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    val ipv6 = if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    } else {
        null
    }
    return listOfNotNull(ipv4, ipv6)
}

/**
 * Every address a hotspot interface should advertise, primary first.
 *
 * Advertising one family only is not enough: an IPv6-only advertisement leaves many iPhones unable
 * to discover `_carplay-ctrl._tcp` at all, which looks like being stuck on "Opening CarPlay"
 * (upstream #253 / f2dab427). The list is never empty, so a failed interface enumeration still
 * publishes the address the reservation selected.
 */
internal fun hotspotAdvertisedAddresses(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    primary: InetAddress,
): List<InetAddress> = existingWifiHostAddresses(addresses, interfaceIndex).ifEmpty { listOf(primary) }
