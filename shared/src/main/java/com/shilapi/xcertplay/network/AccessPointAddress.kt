package com.shilapi.xcertplay.network

/**
 * The 6-byte BSSID of the access point an iPhone is being handed over to, or null when there is
 * none to report.
 *
 * Wi-Fi AP hints travel in the iAP2 0x5703 hand-off message as optional field 0 (`bssid=`), and an
 * absent value must stay absent: a fabricated or redacted MAC is worse than none, because the phone
 * would try to match an address no AP is using. All-zero, multicast and the Android-redacted
 * `02:00:00:00:00:00` forms are therefore rejected instead of being passed on.
 */
internal fun accessPointAddress(text: String?): ByteArray? {
    if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
    val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
    if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
        text.equals("02:00:00:00:00:00", ignoreCase = true)) return null
    return bytes
}
