package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.diagnostics.DiagnosticLog
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Address the AirPlay data/NTP/event servers bind for the current session.
 *
 * The wired CS11 path reaches the iPhone over the kernel netdev's link-local (fe80::2 on usb1); the
 * wireless path reaches it over the soft-AP's IPv4 address. Publishing the address the control
 * channel was accepted on keeps every server on the interface the phone actually used: this ROM
 * rejects unscoped IPv6 binds ("Can't bind to a link-local address without a scope id"), so the
 * wireless session must not fall back to the all-zero wildcard.
 */
private val sessionBindAddress = AtomicReference<InetAddress?>(null)

internal fun publishSessionBindAddress(address: InetAddress?) {
    sessionBindAddress.set(address?.takeUnless { it.isAnyLocalAddress })
}

internal fun clearSessionBindAddress() {
    sessionBindAddress.set(null)
}

/**
 * The address the AirPlay servers bind: fe80::2, scoped to usb1 when that kernel netdev exists. The kernel
 * cdc_ncm transport is deleted (docs/_digest/58 section 9) and every session publishes its address through
 * publishSessionBindAddress, so this lookup is only a legacy fallback.
 *
 * This ROM's libcore rejects every unscoped IPv6 bind with "Can't bind to a link-local address
 * without a scope id" — the "::" literal and even the programmatic all-zero address fail — so
 * wildcard IPv6 binds are impossible there. All servers therefore bind the exact link-local the
 * iPhone talks to (the same address the control listener already binds successfully). Falls back
 * to the all-zero wildcard for environments without the netdev (standard ROMs accept that).
 */
internal fun serverBindAddress(): InetAddress {
    sessionBindAddress.get()?.let { return it }
    // No session address published. On this ROM the fallbacks below cannot produce a bindable address:
    // the kernel `usb1` netdev the first one looks for is deleted (docs/_digest/58 §9), and the second
    // returns the all-zero wildcard, which libcore rejects for IPv6 ("Can't bind to a link-local address
    // without a scope id"). A channel that asks here before a session published its address therefore
    // fails with a bare SocketException -- measured on the car on 2026-10-13 as the wired microphone's
    // `stage=SOCKET_CREATION`, which recorded WeChat voice notes as silence while Siri (whose channel
    // binds the published address) kept working. Said out loud, into the file sink, because the app's
    // Log.i does not reach this ROM's logcat.
    DiagnosticLog.line("serverBindAddress: no session address published; falling back to the wildcard")
    try {
        val nif = NetworkInterface.getByName("usb1")
        DiagnosticLog.line("serverBindAddress: kernel usb1 netdev present=${nif != null}")
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
    } catch (error: Exception) {
        DiagnosticLog.line("serverBindAddress: netdev lookup failed ${error.javaClass.simpleName}: ${error.message}")
    }
    val wildcard = Inet6Address.getByAddress(null, ByteArray(16), 0)
    DiagnosticLog.line("serverBindAddress: returning wildcard $wildcard (this ROM rejects it for IPv6)")
    return wildcard
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
