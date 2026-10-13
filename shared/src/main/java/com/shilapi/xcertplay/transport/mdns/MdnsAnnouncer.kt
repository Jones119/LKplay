package com.shilapi.xcertplay.transport.mdns

import com.shilapi.xcertplay.transport.UserSpaceNetworkJni

/**
 * The three socket calls an announcement needs, so the sender can be tested without a device.
 *
 * The bridge's JNI interface has eighteen methods; depending on all of them to send one datagram would
 * make every test carry eighteen stubs for no gain.
 */
internal interface MdnsDatagramSocket {
    /** Opens a datagram socket and answers its descriptor, or a negative errno. */
    fun open(): Int

    /** Sends [message] to the 16 [address] bytes at [port]; answers the bytes written or a negative errno. */
    fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int

    /** Releases the descriptor; ignored when it is already invalid. */
    fun close(fd: Int)

    companion object {
        /** The production socket: lwIP through the bridge. */
        fun over(jni: UserSpaceNetworkJni): MdnsDatagramSocket = object : MdnsDatagramSocket {
            override fun open(): Int = jni.nativeSocket(SOCK_DGRAM)

            override fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int =
                jni.nativeSendTo(fd, message, 0, message.size, address, port)

            override fun close(fd: Int) {
                jni.nativeClose(fd)
            }
        }

        /** See `lwip/sockets.h`: lwIP's SOCK_DGRAM is 2, the same value the platform uses. */
        const val SOCK_DGRAM = 2
    }
}

/**
 * Announces the AirPlay receiver on the userspace link, which is what an iPhone needs before it opens the
 * AirPlay stream (docs/_digest/64).
 *
 * Send-only on purpose. Sending to a multicast group needs no membership, while *receiving* the phone's
 * queries does, and joining a group needs a native call the bridge does not have yet (`ipv6_mreq`, see
 * doc 64 §6). An unsolicited announcement is enough for iOS to learn the records, so this is the smallest
 * step that can make the phone connect - and the log says exactly how far it got if it does not.
 */
internal class MdnsAnnouncer(
    private val socket: MdnsDatagramSocket,
    private val diagnostic: (String) -> Unit = {},
) {
    /** Sends [message] to the mDNS group [times] times, [gapMillis] apart. */
    fun announce(message: ByteArray, times: Int = 2, gapMillis: Long = 1000L): Boolean {
        val fd = socket.open()
        if (fd < 0) {
            diagnostic("mdns: no socket for the announcement (errno ${-fd})")
            return false
        }
        var delivered = 0
        try {
            repeat(times.coerceAtLeast(1)) { index ->
                val written = socket.sendTo(fd, message, GROUP_V6, PORT)
                if (written != message.size) {
                    diagnostic(
                        "mdns: announcement ${index + 1} wrote $written of ${message.size} bytes " +
                            "(errno ${if (written < 0) -written else 0})",
                    )
                    return delivered > 0
                }
                delivered++
                diagnostic("mdns: announcement ${index + 1}/$times sent (${message.size} bytes)")
                if (index + 1 < times) Thread.sleep(gapMillis)
            }
            return true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return delivered > 0
        } finally {
            socket.close(fd)
        }
    }

    private companion object {
        const val PORT = DnsSdRecords.PORT

        /** `ff02::fb`, the link-local multicast group mDNS uses. */
        val GROUP_V6: ByteArray = ByteArray(16).also {
            it[0] = 0xff.toByte()
            it[1] = 0x02
            it[15] = 0xfb.toByte()
        }
    }
}
