package com.shilapi.xcertplay.network

import java.io.IOException
import java.security.MessageDigest

/** Reinstall-stable namespace; never treats a generic DIRECT-* name as ours. */
internal object P2pOwnership {
    fun prefix(packageName: String, scopedId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$scopedId".toByteArray(Charsets.UTF_8))
        return "DIRECT-dp" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 255) } + "-"
    }

    /**
     * Whether an existing group is ours to reclaim.
     *
     * [legacyGroupRequested] covers the Android 4.x path: the two-argument `createGroup` names the
     * group itself, so the SSID we asked for is not the SSID that exists, and this firmware leaves
     * the resulting group behind. Recording the request before creating it means a group that
     * outlives a crash mid-create is still recognised as ours instead of forcing a reset (the Geely
     * H52 firmware review documents the persistent-group behaviour).
     */
    fun canReclaim(isOwner: Boolean, ssid: String?, recorded: String?, prefix: String,
        legacyGroupRequested: Boolean = false): Boolean =
        isOwner && !ssid.isNullOrBlank() &&
            (ssid == recorded || legacyGroupRequested ||
                (ssid.startsWith(prefix) && ssid.removePrefix(prefix).matches(Regex("[A-Za-z0-9]{4}"))))
}

class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")
