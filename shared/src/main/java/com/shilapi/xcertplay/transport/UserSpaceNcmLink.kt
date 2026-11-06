package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.shilapi.xcertplay.compat.WorkerThreads

/**
 * The userspace NCM link: lwIP's TCP/IP stack carried over the USB bridge (docs/_digest/58 §7).
 *
 * It owns the two directions between the netif and [NcmUsbBridge]:
 *
 * - RX: a reader thread takes one Ethernet frame at a time from the bridge and injects it into lwIP. That
 *   thread is the USB reader, so it must never block on a full mailbox for long - lwIP applies backpressure
 *   by making `tcpip_input` wait, which is exactly the pacing the video stream needs.
 * - TX: a poller thread drains the netif's ring and writes each frame through the bridge's NTB framer.
 *   Polling instead of a JNI upcall keeps lwIP's thread out of JNI (bridge/cs11_netif.c).
 *
 * [SessionNetwork] is delegated to [UserSpaceSessionNetwork], so this object is what the wired channels bind
 * on. Close stops the pump; the bridge itself belongs to the caller.
 */
internal class UserSpaceNcmLink(
    private val bridge: NcmUsbBridge,
    private val jni: UserSpaceNetworkJni = UserSpaceNetworkNative,
    private val onDiagnostic: (String) -> Unit = {},
) : SessionNetwork by UserSpaceSessionNetwork(jni), Closeable {
    /** The MAC the iPhone's NCM configuration reports; iAP2 is told this is our device identifier. */
    val hostMac: ByteArray? get() = bridge.hostMac

    private val running = AtomicBoolean(false)
    private val firstFrameLogged = AtomicBoolean(false)
    private val injectedFrames = AtomicLong(0)
    private val injectedBytes = AtomicLong(0)
    private val writtenFrames = AtomicLong(0)
    private val writtenBytes = AtomicLong(0)
    private val transmitFailures = AtomicInteger(0)
    private var receiveThread: Thread? = null
    private var transmitThread: Thread? = null
    private var statisticsThread: Thread? = null

    /**
     * Brings lwIP up and pins the netif's address. Returns the native result (0 when the netif exists), so
     * the caller can decide to fall back to the kernel netdev instead of pretending the link is ready.
     */
    fun start(mtu: Int = NCM_MTU): Int {
        val result = jni.nativeStart(bridge.hostMac, mtu)
        onDiagnostic(
            "userspace NCM network started mtu=$mtu hostMac=" +
                (bridge.hostMac?.joinToString(":") { "%02x".format(it) } ?: "descriptor-none") +
                " result=$result",
        )
        return result
    }

    /** Starts both directions and the periodic statistics line. Safe to call once, after [start]. */
    fun startPump() {
        if (!running.compareAndSet(false, true)) return
        receiveThread = WorkerThreads.create(::pumpReceive, "ncm-us-rx").apply { isDaemon = true; start() }
        transmitThread = WorkerThreads.create(::pumpTransmit, "ncm-us-tx").apply { isDaemon = true; start() }
        statisticsThread = WorkerThreads.create(::reportStatistics, "ncm-us-stats").apply { isDaemon = true; start() }
    }

    private fun pumpReceive() {
        TransportThreadPriority.applyToCurrentThread()
        val timeout = RECEIVE_TIMEOUT_MILLIS
        while (running.get()) {
            val frame = try {
                bridge.recv(timeout)
            } catch (error: Throwable) {
                if (running.get()) onDiagnostic("userspace NCM receive ended: ${error.javaClass.simpleName}")
                return
            } ?: continue
            if (frame.isEmpty()) continue
            if (firstFrameLogged.compareAndSet(false, true)) {
                // Distinguishes "the phone never spoke" from "we dropped it" when the connect step is slow
                // (docs/_digest/99): this line marks the phone's first frame on the NCM link.
                onDiagnostic("userspace NCM first inbound frame bytes=${frame.size}")
            }
            val result = jni.nativeInjectFrame(frame, frame.size)
            if (result == 0) {
                injectedFrames.incrementAndGet()
                injectedBytes.addAndGet(frame.size.toLong())
            }
        }
    }

    private fun pumpTransmit() {
        TransportThreadPriority.applyToCurrentThread()
        val frame = ByteArray(TRANSMIT_BUFFER_BYTES)
        while (running.get()) {
            // Blocks on the native ring's condition variable until lwIP queues a frame, so an ACK, a window
            // update or a touch report leaves as soon as it exists; the timeout only bounds how long a
            // stopping pump takes to see `running` go false (the native wait ignores interrupts).
            //
            // The catch is kept for the JVM-fake default (a 1 ms sleep): `close()` interrupts this thread,
            // and an InterruptedException escaping a thread body kills the whole process - measured on the
            // car 2026-10-18 as `FATAL EXCEPTION: ncm-us-tx / java.lang.InterruptedException ... pumpTransmit`.
            val pending = try {
                jni.nativeWaitTx(TRANSMIT_WAIT_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (pending <= 0) continue
            val length = jni.nativePollTxFrame(frame)
            if (length <= 0) continue
            try {
                // `frame` is framed into the bridge's own block before send returns, so it is reused as is.
                bridge.send(frame, length, TRANSMIT_TIMEOUT_MILLIS)
                writtenFrames.incrementAndGet()
                writtenBytes.addAndGet(length.toLong())
            } catch (error: Throwable) {
                transmitFailures.incrementAndGet()
                if (running.get()) {
                    onDiagnostic("userspace NCM transmit failed: ${error.javaClass.simpleName}")
                }
                return
            }
        }
    }

    private fun reportStatistics() {
        while (running.get()) {
            try {
                Thread.sleep(STATISTICS_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            val native = runCatching { jni.nativeStats() }.getOrNull() ?: IntArray(10)
            onDiagnostic(
                "userspace NCM link rxFrames=${native.getOrElse(0) { 0 }} rxBytes=${native.getOrElse(1) { 0 }} " +
                    "txFrames=${native.getOrElse(2) { 0 }} txBytes=${native.getOrElse(3) { 0 }} " +
                    "txDropped=${native.getOrElse(4) { 0 }} injected=$injectedFrames/$injectedBytes " +
                    "written=$writtenFrames/$writtenBytes writeFailures=$transmitFailures " +
                    "pending=${runCatching { jni.nativeTxPending() }.getOrDefault(0)} " +
                    "ipv6Rx=${native.getOrElse(5) { 0 }} tcpRx=${native.getOrElse(6) { 0 }} " +
                    "udpRx=${native.getOrElse(7) { 0 }} icmp6Rx=${native.getOrElse(8) { 0 }} " +
                    "lastTcpDst=${native.getOrElse(9) { 0 }}",
            )
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val threads = listOfNotNull(receiveThread, transmitThread, statisticsThread)
        threads.forEach { it.interrupt() }
        receiveThread = null
        transmitThread = null
        statisticsThread = null
        // Wait for the pumps to actually stop before anyone starts another link.
        //
        // Returning here while a pump still runs lets two links feed the same process-wide lwIP stack at
        // once. Measured on the car 2026-10-18: seven `ncm-status-in` threads were alive after six attempts,
        // and the tcpip thread then died calling a handler it read as 0x08000000 - a message whose memory had
        // already been reused, not a NULL one. A pump blocked inside a native call cannot be interrupted, so
        // it is reported instead of waited on forever.
        val deadline = System.currentTimeMillis() + CLOSE_JOIN_MILLIS
        for (thread in threads) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try {
                thread.join(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (thread.isAlive) {
                onDiagnostic("userspace NCM pump ${thread.name} did not stop within ${CLOSE_JOIN_MILLIS}ms")
            }
        }
    }

    internal companion object {
        /** NCM's segment size on this link; lwIP's netif MTU. */
        const val NCM_MTU = 1500

        /** How long the RX thread waits for a frame before re-checking whether it should stop. */
        const val RECEIVE_TIMEOUT_MILLIS = 100L
        /** Upper bound of one native TX wait; frames wake the pump immediately, this only bounds shutdown. */
        private const val TRANSMIT_WAIT_MILLIS = 20
        private const val TRANSMIT_TIMEOUT_MILLIS = 200
        private const val STATISTICS_INTERVAL_MILLIS = 5_000L

        /** One Ethernet frame plus room for a VLAN tag; lwIP caps frames at the MTU anyway. */
        private const val TRANSMIT_BUFFER_BYTES = 1600

        /** How long `close` waits for a pump to stop before giving up and reporting it. */
        private const val CLOSE_JOIN_MILLIS = 1_500L

        /** The one live link; see the `init` block. */
        @Volatile
        private var activeInstance: UserSpaceNcmLink? = null
    }
}
