package com.shilapi.xcertplay.transport.mdns

import com.shilapi.xcertplay.transport.UserSpaceNetworkJni

/**
 * The socket calls the responder needs.
 *
 * Deliberately separate from [MdnsDatagramSocket]: adding methods to that one would break the announcement
 * test's fake, which is the trap that put a red test source set on the remote once already (see 63f3bdb9).
 * Two narrow interfaces with two small fakes beat one wide interface with a fake that has to grow.
 */
internal interface MdnsQuerySocket {
    fun open(): Int

    /** Binds the group port; the address is all zeroes (`::`) because a link-local group needs no scope here. */
    fun bind(fd: Int, address: ByteArray, port: Int): Int

    /** Joins [group] on the netif with [interfaceIndex]; answers a negative errno on failure. */
    fun joinGroup(fd: Int, group: ByteArray, interfaceIndex: Int): Int

    /** Waits up to the configured timeout; answers the bytes received, 0 on timeout or a negative errno. */
    fun receive(fd: Int, buffer: ByteArray, fromAddress: ByteArray, fromPort: IntArray): Int

    fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int

    fun setTimeout(fd: Int, millis: Int): Int

    fun close(fd: Int)

    companion object {
        /** lwIP's value for SOL_SOCKET (see 1fba7a21: the platform's 1 is not lwIP's 0xfff). */
        const val SOL_SOCKET = 0xFFF

        /** lwIP's value for SO_RCVTIMEO; the platform's 20 would be refused with ENOPROTOOPT. */
        const val SO_RCVTIMEO = 0x1006

        fun over(jni: UserSpaceNetworkJni): MdnsQuerySocket = object : MdnsQuerySocket {
            override fun open(): Int = jni.nativeSocket(MdnsDatagramSocket.SOCK_DGRAM)

            override fun bind(fd: Int, address: ByteArray, port: Int): Int =
                jni.nativeBind(fd, address, port)

            override fun joinGroup(fd: Int, group: ByteArray, interfaceIndex: Int): Int =
                jni.nativeJoinGroupV6(fd, group, interfaceIndex)

            override fun receive(fd: Int, buffer: ByteArray, fromAddress: ByteArray, fromPort: IntArray): Int =
                jni.nativeRecvFrom(fd, buffer, 0, buffer.size, fromAddress, fromPort)

            override fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int =
                jni.nativeSendTo(fd, message, 0, message.size, address, port)

            override fun setTimeout(fd: Int, millis: Int): Int =
                jni.nativeSetSockOptInt(fd, SOL_SOCKET, SO_RCVTIMEO, millis)

            override fun close(fd: Int) {
                jni.nativeClose(fd)
            }
        }
    }
}

/**
 * Answers the iPhone's mDNS queries on the userspace link.
 *
 * The announcement alone was measured not to be enough: the phone sees `_airplay._tcp` and still never
 * opens the AirPlay stream (docs/_digest/64 §7). This is the other half of DNS-SD - hearing the query and
 * answering it - so the phone gets the records as a reply to something it asked, which is the one candidate
 * left for the stream to start.
 *
 * The loop is meant to run on its own daemon thread: it blocks in `receive` for at most one timeout, and it
 * exits when the link goes away (every call then answers a negative errno, which is treated as "keep
 * waiting" only until [stop] says otherwise).
 */
internal class MdnsResponder(
    private val socket: MdnsQuerySocket,
    private val diagnostic: (String) -> Unit = {},
) {
    /** Binds, joins, then answers until [stop] answers true; returns false when it could not even start. */
    fun run(service: DnsSdMessages.Service, interfaceIndex: Int = 0, stop: () -> Boolean = { false }): Boolean {
        val fd = socket.open()
        if (fd < 0) {
            diagnostic("mdns: no socket for queries (errno ${-fd})")
            return false
        }
        try {
            val bound = socket.bind(fd, ANY_V6, DnsSdRecords.PORT)
            if (bound != 0) {
                diagnostic("mdns: could not bind ${DnsSdRecords.PORT} (errno ${-bound})")
                return false
            }
            val joined = socket.joinGroup(fd, GROUP_V6, interfaceIndex)
            diagnostic(
                if (joined == 0) {
                    "mdns: joined ff02::fb on interface $interfaceIndex"
                } else {
                    "mdns: joining ff02::fb failed (errno ${-joined}); queries will not be heard"
                },
            )
            socket.setTimeout(fd, READ_TIMEOUT_MILLIS)
            val buffer = ByteArray(MAX_MESSAGE_BYTES)
            val fromAddress = ByteArray(16)
            val fromPort = IntArray(1)
            while (!stop()) {
                val received = socket.receive(fd, buffer, fromAddress, fromPort)
                // 0 is the read timeout - the link is simply quiet, so wait again. A negative errno is the
                // socket or the link going away, which ends the loop instead of spinning on a dead fd.
                if (received < 0) {
                    diagnostic("mdns: query loop ended (errno ${-received})")
                    break
                }
                if (received == 0) continue
                val questions = DnsSdMessages.questions(buffer.copyOf(received))
                if (questions.isEmpty()) continue
                val answers = DnsSdMessages.answers(service, questions) ?: continue
                val port = fromPort[0].takeIf { it in 1..0xffff } ?: DnsSdRecords.PORT
                val written = socket.sendTo(fd, answers, fromAddress, port)
                diagnostic(
                    "mdns: answered ${questions.size} question(s) with $written of ${answers.size} bytes",
                )
            }
            return true
        } finally {
            socket.close(fd)
        }
    }

    private companion object {
        /** One second: long enough to be quiet, short enough that [stop] is noticed promptly. */
        const val READ_TIMEOUT_MILLIS = 1000

        const val MAX_MESSAGE_BYTES = 1500

        val ANY_V6 = ByteArray(16)

        /** `ff02::fb`, the link-local group mDNS uses. */
        val GROUP_V6 = ByteArray(16).also {
            it[0] = 0xff.toByte()
            it[1] = 0x02
            it[15] = 0xfb.toByte()
        }
    }
}
