package com.shilapi.xcertplay.transport

import android.util.Log

/** Which wired data path this head unit may use. */
enum class NcmLegacyMode {
    /**
     * Use the userspace NCM bridge; while it cannot carry a session yet, the legacy kernel netdev does
     * (a **temporary** fallback - see the deprecation note on [NcmLegacyPolicy]).
     */
    AUTO,

    /** Userspace bridge only - no kernel fallback. A diagnostic mode: it fails loudly instead. */
    FORCE,

    /** Legacy kernel netdev + root channel. Hidden debug fallback only; no product surface. */
    KERNEL,
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
     * the Kotlin seam, so the stack now exists and only the library's presence decides
     * `readiness` (docs/_digest/58 §7, §11).
     *
     * Flipping this to true makes `AUTO` prefer the userspace transport on API 19 and lets FORCE name a
     * missing library instead of a missing stack. The kernel netdev stays as the hidden
     * `debug.diplay.ncm_legacy=kernel` debug fallback until the car retires it.
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
 * **Product decision (2026-10-13)**: the kernel `cdc_ncm` netdev needs a root channel, and that channel
 * needs adb (the fire is `adb-ec ... ppp`, and without adbd on TCP it fails with "device not found" -
 * measured on the car, see docs/_digest/58 §9). A transport that needs adb and root is not shippable, so
 * the userspace NCM bridge is the product path and the kernel netdev is kept **only as a hidden debug
 * fallback** (`debug.diplay.ncm_legacy=kernel`) for car debugging and A/B while the userspace stack is
 * built. It is deleted with the first build that can carry traffic through the userspace bridge.
 *
 * There is deliberately no Settings switch: one shipping transport needs no choice.
 *
 * ```
 * adb shell setprop debug.diplay.ncm_legacy force   # userspace only (diagnostic)
 * adb shell setprop debug.diplay.ncm_legacy kernel  # retired, falls back to AUTO
 * adb shell setprop debug.diplay.ncm_legacy ''      # userspace preferred (default)
 * ```
 */
internal object NcmLegacyPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.ncm_legacy"

    /** The pure part: one property value to one mode. JVM-testable without a device. */
    fun parse(raw: String?): NcmLegacyMode = when (raw?.trim()?.lowercase()) {
        "force", "userspace", "legacy" -> NcmLegacyMode.FORCE
        "kernel", "off", "root", "netdev" -> NcmLegacyMode.AUTO  // retired: the kernel transport is deleted (docs/_digest/58 section 9)
        else -> NcmLegacyMode.AUTO
    }

    fun current(): NcmLegacyMode = parse(systemProperty()).also { mode ->
        if (mode != NcmLegacyMode.AUTO) {
            Log.i(TAG, "wired NCM transport override by $PROPERTY: $mode")
        }
    }

    /** Whether the userspace bridge may be attempted at all. */
    fun allowsUserspace(mode: NcmLegacyMode): Boolean = mode != NcmLegacyMode.KERNEL

    /** Whether the kernel netdev may still be used when the userspace bridge cannot start. */
    fun allowsKernelFallback(mode: NcmLegacyMode): Boolean = mode != NcmLegacyMode.FORCE

    /** `debug.diplay.ncm_legacy`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}

/**
 * Which transport the wired bring-up should drive.
 *
 * API 21+ already runs the userspace NCM bridge (raw USB + the platform's own network stack through
 * the VpnService TUN), so it is userspace there for the same reason it is preferred here. On API 19
 * the preference is honoured as soon as [UserspaceNcmTransport.readiness] says READY; until then the
 * kernel netdev carries the link, and the reason is logged rather than guessed.
 */
internal fun wiredTransportDecision(
    sdkInt: Int,
    mode: NcmLegacyMode,
    readiness: UserspaceNcmReadiness,
): WiredTransportDecision = when {
    sdkInt >= 21 -> WiredTransportDecision(true, "wired opening raw-USB NCM bridge")
    !NcmLegacyPolicy.allowsUserspace(mode) -> WiredTransportDecision(
        false,
        "CS11: legacy kernel netdev selected by the debug switch (docs/_digest/58 §9 deprecation note)",
    )
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
            "using the kernel netdev instead",
    )
}
