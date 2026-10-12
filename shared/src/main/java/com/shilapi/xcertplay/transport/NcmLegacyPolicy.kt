package com.shilapi.xcertplay.transport

import android.util.Log

/** Which wired data path this head unit may use. */
enum class NcmLegacyMode {
    /** Try the API-19 userspace bridge first and fall back to the kernel netdev when it cannot. */
    AUTO,

    /** Never use the kernel netdev: the userspace bridge must work or the wired link fails. */
    FORCE,

    /** Never attempt the userspace bridge - the shipped behaviour before this route existed. */
    KERNEL,
}

/**
 * `debug.diplay.ncm_legacy` - whether the wired link may be carried by the userspace NCM bridge.
 *
 * The userspace bridge exists because the kernel path needs a root channel that evaporates on every
 * reboot (`tools/cs11_root_fire.sh`, docs/_digest/57 §7), while a userspace bridge claims the NCM
 * interfaces at app level and therefore survives a restart. It cannot carry traffic until it also
 * brings its own TCP/IP stack, so during the port the kernel netdev stays the default: [AUTO] tries
 * the userspace bridge and falls back, [KERNEL] reproduces the shipped behaviour for an A/B, and
 * [FORCE] is for the car session where the userspace path must stand or fall on its own.
 *
 * ```
 * adb shell setprop debug.diplay.ncm_legacy force   # userspace bridge only
 * adb shell setprop debug.diplay.ncm_legacy kernel  # kernel netdev only (shipped)
 * adb shell setprop debug.diplay.ncm_legacy ''      # AUTO
 * ```
 *
 * Read once per wired bring-up, so it takes effect on the next session.
 */
internal object NcmLegacyPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.ncm_legacy"

    /** The pure part: one property value to one mode. JVM-testable without a device. */
    fun parse(raw: String?): NcmLegacyMode = when (raw?.trim()?.lowercase()) {
        "force", "userspace", "legacy" -> NcmLegacyMode.FORCE
        "kernel", "off", "root", "netdev" -> NcmLegacyMode.KERNEL
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
