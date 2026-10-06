package com.shilapi.xcertplay.network

import android.os.Build
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * CS11: kernel cdc_ncm netdev bring-up for the iPhone's NCM function.
 *
 * The CS11 kernel ships cdc_ncm/usbnet as loadable modules, but nothing binds them at boot and
 * the stock modprobe path (/sbin/modprobe) does not exist, so the iPhone's NCM interfaces stay
 * unowned and no usb1 netdev appears. The CS11 kernel also has no TUN driver
 * (/proc/misc has no tun), which makes the classic VpnService + raw-USB NCM bridge unusable:
 * VpnService.establish() fails with "Cannot create interface".
 *
 * DiPlay therefore binds the NCM control interface to cdc_ncm through the on-device root command
 * queue (/data/.android/rootcmd.sh, consumed by the rootd daemon), pins the advertised link-local
 * address on the interface, and then hosts the AirPlay listener directly on the kernel netdev:
 * the phone reaches it over the kernel NCM link and all IPv6 traffic stays in the kernel stack.
 */
object Cs11NetdevBootstrap {
    const val INTERFACE_NAME = "usb1"
    /** Advertised car-side link-local; mirrors the default CarPlayRuntimeConfig.linkLocal. */
    const val ADVERTISED_LINK_LOCAL = "fe80::2"
    private const val QUEUE_DIR = "/data/.android"
    private const val QUEUE_FILE = "$QUEUE_DIR/rootcmd.sh"
    private const val POLL_INTERVAL_MILLIS = 400L
    const val DEFAULT_TIMEOUT_MILLIS = 12_000L

    /** CS11 port targets API 19; other platforms keep the legacy VPN paths. */
    val supported: Boolean get() = Build.VERSION.SDK_INT < 21

    data class Netdev(
        val interfaceName: String,
        /** A link-local address scoped to the interface (kernel EUI-64 unless pruned). */
        val linkLocal: Inet6Address,
        val macString: String?,
    )

    /**
     * Ensures the kernel netdev is up and carries [advertised]. When not, queues the bring-up
     * script through the root command queue and polls for the interface. Null when unavailable.
     */
    fun ensure(
        advertised: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        log: (String) -> Unit,
    ): Netdev? {
        ready(advertised)?.let { netdev ->
            log("netdev ${netdev.interfaceName} ready ll=${netdev.linkLocal.hostAddress}")
            return netdev
        }
        requestBringUp(advertised, log)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            ready(advertised)?.let { netdev ->
                log("netdev ${netdev.interfaceName} appeared ll=${netdev.linkLocal.hostAddress}")
                return netdev
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return null
            }
        }
        log("netdev ${INTERFACE_NAME} unavailable after ${timeoutMillis}ms")
        return null
    }

    /** Rebuilds [literal] as an [Inet6Address] scoped to [interfaceIndex] for socket binds. */
    fun scopedAddress(literal: String, interfaceIndex: Int): Inet6Address? = try {
        val plain = InetAddress.getByName(literal)
        if (plain is Inet6Address) Inet6Address.getByAddress(null, plain.address, interfaceIndex) else null
    } catch (_: Exception) {
        null
    }

    private fun ready(advertised: String): Netdev? = try {
        val nif = NetworkInterface.getByName(INTERFACE_NAME)
        val scoped = nif?.let(::scopedLinkLocalOf)
        val hasAdvertised = nif?.inetAddresses?.toList()?.any {
            it is Inet6Address && it.address.contentEquals(InetAddress.getByName(advertised).address)
        } == true
        if (nif == null || !nif.isUp || scoped == null || !hasAdvertised) null
        else Netdev(INTERFACE_NAME, scoped, macString(nif))
    } catch (_: Exception) {
        null
    }

    private fun scopedLinkLocalOf(nif: NetworkInterface): Inet6Address? {
        val candidates = nif.inetAddresses.toList().filterIsInstance<Inet6Address>()
            .filter { it.isLinkLocalAddress }
        val address = candidates.firstOrNull { it.scopeId == nif.index } ?: candidates.firstOrNull() ?: return null
        return if (address.scopeId == nif.index) address
        else Inet6Address.getByAddress(null, address.address, nif.index)
    }

    private fun macString(nif: NetworkInterface): String? = try {
        nif.hardwareAddress?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Exception) {
        null
    }

    private fun requestBringUp(advertised: String, log: (String) -> Unit) {
        try {
            val queueDir = File(QUEUE_DIR)
            if (!queueDir.isDirectory || !queueDir.canWrite()) {
                log("netdev queue unavailable: $QUEUE_DIR")
                return
            }
            val script = bringUpScript(advertised)
            val staged = File(QUEUE_DIR, "rootcmd.sh.netdev")
            staged.writeText(script)
            val target = File(QUEUE_FILE)
            if (!staged.renameTo(target)) {
                target.writeText(script)
                staged.delete()
            }
            log("netdev bring-up queued")
        } catch (error: Exception) {
            log("netdev bring-up queue failed: ${error.message}")
        }
    }

    /**
     * Binds the iPhone's NCM control interfaces to cdc_ncm, brings usb1 up, pins [advertised] and
     * prunes the kernel's EUI-64 link-local so every socket on the link sources from the
     * advertised address. Executed as full root by the rootd queue.
     */
    private fun bringUpScript(advertised: String): String = """
        # DiPlay CS11 netdev bring-up (rootd executes this as root)
        for dev in /sys/bus/usb/devices/*; do
          [ -f "${'$'}dev/idVendor" ] || continue
          [ "${'$'}(cat ${'$'}dev/idVendor 2>/dev/null)" = "05ac" ] || continue
          [ "${'$'}(cat ${'$'}dev/idProduct 2>/dev/null)" = "12a8" ] || continue
          for ifc in ${'$'}dev:*; do
            [ -f "${'$'}ifc/bInterfaceClass" ] || continue
            [ "${'$'}(cat ${'$'}ifc/bInterfaceClass 2>/dev/null)" = "02" ] || continue
            [ "${'$'}(cat ${'$'}ifc/bInterfaceSubclass 2>/dev/null)" = "0d" ] || continue
            name=${'$'}(basename "${'$'}ifc")
            [ -e "/sys/bus/usb/drivers/cdc_ncm/${'$'}name" ] && continue
            echo "${'$'}name" > /sys/bus/usb/drivers/cdc_ncm/bind 2>/dev/null
          done
        done
        ifconfig $INTERFACE_NAME up 2>/dev/null
        ip -6 addr add $advertised/64 dev $INTERFACE_NAME 2>/dev/null
        busybox sleep 1
        for addr in ${'$'}(ip -6 addr show dev $INTERFACE_NAME scope link 2>/dev/null | busybox grep inet6 | busybox awk '{print ${'$'}2}'); do
          case "${'$'}addr" in
            $advertised/64) ;;
            *) ip -6 addr del "${'$'}addr" dev $INTERFACE_NAME 2>/dev/null ;;
          esac
        done
        busybox sleep 2
        for addr in ${'$'}(ip -6 addr show dev $INTERFACE_NAME scope link 2>/dev/null | busybox grep inet6 | busybox awk '{print ${'$'}2}'); do
          case "${'$'}addr" in
            $advertised/64) ;;
            *) ip -6 addr del "${'$'}addr" dev $INTERFACE_NAME 2>/dev/null ;;
          esac
        done
        echo "cs11_netdev_bootstrap_done"
    """.trimIndent()
}
