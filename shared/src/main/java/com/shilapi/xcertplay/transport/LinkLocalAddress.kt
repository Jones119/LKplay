package com.shilapi.xcertplay.transport

/**
 * The link-local address a link derives from its 48-bit MAC, which is the address the phone actually dials.
 *
 * Measured on the car: with the netif pinned to `fe80::2`, not one TCP frame ever arrived from the iPhone,
 * while the reference implementation - whose AirPlay SETUP URL reads `rtsp://fe80::9c92:4fff:fe09:bf8b`, the
 * EUI-64 of the link MAC - streams megabytes per window. The advertised address in CarPlayStartSession and
 * the address the netif carries therefore have to be this one, and deriving both from the same MAC keeps the
 * two sides in step (docs/_digest/64).
 */
internal object LinkLocalAddress {
    /** e.g. `9e:92:4f:09:bf:8b` -> `fe80::9c92:4fff:fe09:bf8b`. */
    fun fromMac(mac: ByteArray, prefix: String = "fe80"): String {
        require(mac.size >= 6) { "a MAC has six bytes, got ${mac.size}" }
        val eui = IntArray(8)
        // Flip the universal/local bit, then splice in the ff:fe marker EUI-64 requires.
        eui[0] = ((mac[0].toInt() and 0xff) xor 0x02)
        eui[1] = mac[1].toInt() and 0xff
        eui[2] = mac[2].toInt() and 0xff
        eui[3] = 0xff
        eui[4] = 0xfe
        eui[5] = mac[3].toInt() and 0xff
        eui[6] = mac[4].toInt() and 0xff
        eui[7] = mac[5].toInt() and 0xff
        val tail = (0 until 8 step 2).joinToString(":") { index ->
            Integer.toHexString((eui[index] shl 8) or eui[index + 1])
        }
        return "$prefix::$tail"
    }

    /** The same derivation for a MAC that may be missing, so callers can fall back to their configured value. */
    fun fromMacOrNull(mac: ByteArray?, prefix: String = "fe80"): String? =
        if (mac != null && mac.size >= 6) fromMac(mac, prefix) else null
}