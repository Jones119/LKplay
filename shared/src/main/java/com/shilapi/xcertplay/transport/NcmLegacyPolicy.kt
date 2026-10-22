package com.shilapi.xcertplay.transport

import android.util.Log

/** Which wired data path this head unit may use. */
enum class NcmLegacyMode {
    /**
     * Use the userspace NCM bridge. It is the only wired transport this build has: when its native library
     * is missing the session keeps the iAP2 control channel and says why, rather than quietly taking a path
     * that needs root (docs/_digest/63).
     */
    AUTO,

    /**
     * Userspace bridge only, and loud about it: when it cannot be driven the bring-up still probes the
     * descriptors and the negotiation and names the missing piece instead of degrading silently.
     */
    FORCE,
}

/** Whether the userspace NCM bridge can carry a session at all, and what it would be missing. */
enum class UserspaceNcmReadiness {
    READY,

    /** The native altsetting call is not in this APK (needs an NDK build, docs/_digest/58 §5 item 4). */
    MISSING_NATIVE,

    /** The userspace TCP/IP stack does not exist yet (docs/_digest/58 §7: lwIP). */
    MISSING_STACK,
}

/** What the wired bring-up should drive, and the line to log when it is not the userspace bridge. */
internal data class WiredTransportDecision(val userspace: Boolean, val reason: String)

/**
 * The userspace NCM route's two prerequisites, kept in one place so the selection logic reads from a
 * single verdict instead of guessing.
 */
internal object UserspaceNcmTransport {
    /**
     * This ROM has no TUN (docs/_digest/58 §1), so the userspace bridge can only carry AirPlay once it
     * owns a TCP/IP stack. Stage 1 vendored lwIP for that and stage 2 built it into the APK together with
     * the Kotlin seam, so the stack now exists and only the library's presence decides `readiness`
     * (docs/_digest/58 §7, §11).
     *
     * The constant stays because the verdict it produces ([UserspaceNcmReadiness.MISSING_STACK]) is how a
     * build that dropped lwIP would say so up front, instead of failing later inside a socket call.
     */
    const val stackImplemented = true

    fun readiness(nativeAvailable: Boolean): UserspaceNcmReadiness = when {
        !stackImplemented -> UserspaceNcmReadiness.MISSING_STACK
        !nativeAvailable -> UserspaceNcmReadiness.MISSING_NATIVE
        else -> UserspaceNcmReadiness.READY
    }

    fun describe(readiness: UserspaceNcmReadiness): String = when (readiness) {
        UserspaceNcmReadiness.READY -> "userspace NCM bridge ready"
        UserspaceNcmReadiness.MISSING_NATIVE ->
            "the userspace NCM bridge needs the legacy_usb native library (NDK build)"
        UserspaceNcmReadiness.MISSING_STACK ->
            "the userspace NCM bridge has no TCP/IP stack yet (this ROM has no TUN; docs/_digest/58 §7)"
    }
}

/**
 * Which wired transport this head unit uses.
 *
 * **Product decision (2026-10-13, completed by `2ab6be92`)**: the kernel `cdc_ncm` netdev needed a root
 * channel, and that channel needed adb (the fire was `adb-ec ... ppp`, and without adbd on TCP it failed
 * with "device not found" - measured on the car, docs/_digest/63). A transport that needs adb and root is
 * not shippable, so the userspace NCM bridge is the product path and the kernel netdev has been **deleted**
 * together with its root fire and with the switch that used to select it. What is left is one transport and
 * one diagnostic mode.
 *
 * There is deliberately no Settings switch: one shipping transport needs no choice.
 *
 * ```
 * adb shell setprop debug.diplay.ncm_legacy force   # userspace only + a probe when it is unavailable
 * adb shell setprop debug.diplay.ncm_legacy kernel  # retired spelling, parses to AUTO (the path is gone)
 * adb shell setprop debug.diplay.ncm_legacy ''      # userspace (default)
 * ```
 */
internal object NcmLegacyPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.ncm_legacy"

    /** The pure part: one property value to one mode. JVM-testable without a device. */
    fun parse(raw: String?): NcmLegacyMode = when (raw?.trim()?.lowercase()) {
        "force", "userspace", "legacy" -> NcmLegacyMode.FORCE
        // Retired spellings of the deleted kernel transport: still accepted so an old script does not read
        // like a parse failure, but they select nothing beyond the default (docs/_digest/63 §4).
        "kernel", "off", "root", "netdev" -> NcmLegacyMode.AUTO
        else -> NcmLegacyMode.AUTO
    }

    fun current(): NcmLegacyMode = parse(systemProperty()).also { mode ->
        if (mode != NcmLegacyMode.AUTO) {
            Log.i(TAG, "wired NCM transport override by $PROPERTY: $mode")
        }
    }

    /** `debug.diplay.ncm_legacy`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}

/**
 * Which transport the wired bring-up should drive.
 *
 * API 21+ already runs the userspace NCM bridge (raw USB + the platform's own network stack through the
 * VpnService TUN), so it is userspace there for the same reason it is preferred here. On API 19 the
 * preference is honoured as soon as [UserspaceNcmTransport.readiness] says READY.
 *
 * `userspace == false` means **no wired media transport**: the kernel netdev that used to carry this link
 * was deleted (docs/_digest/63), so the caller keeps the iAP2 control channel and logs the reason. It never
 * means "the kernel netdev will take over".
 */
internal fun wiredTransportDecision(
    sdkInt: Int,
    mode: NcmLegacyMode,
    readiness: UserspaceNcmReadiness,
): WiredTransportDecision = when {
    sdkInt >= 21 -> WiredTransportDecision(true, "wired opening raw-USB NCM bridge")
    readiness == UserspaceNcmReadiness.READY -> WiredTransportDecision(
        true,
        "wired opening the API-19 userspace NCM bridge",
    )
    mode == NcmLegacyMode.FORCE -> WiredTransportDecision(
        false,
        "CS11: userspace NCM bridge forced but unavailable: ${UserspaceNcmTransport.describe(readiness)}",
    )
    else -> WiredTransportDecision(
        false,
        "CS11: userspace NCM bridge preferred but unavailable (${UserspaceNcmTransport.describe(readiness)}); " +
            "no wired media transport - control channel only",
    )
}
