package com.shilapi.xcertplay.media

import java.net.InetAddress

/**
 * The media servers bind the same per-session address as the AirPlay control channel: the wired CS11
 * netdev link-local, or the soft-AP IPv4 address on the wireless path. See
 * [com.shilapi.xcertplay.airplay.serverBindAddress] for why this ROM cannot use an unscoped IPv6
 * wildcard bind.
 */
internal fun serverBindAddress(): InetAddress = com.shilapi.xcertplay.airplay.serverBindAddress()
