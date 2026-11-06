package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.compat.WorkerThreads

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * Bulk-IN read size. The API-21+ path uses [READ_CHUNK_BYTES]; the API-19 userspace path reads the
     * negotiated NTB input size instead (16 KiB for this iPhone, docs/_digest/58 §5 item 7).
     */
    private val readChunkBytes: Int = READ_CHUNK_BYTES,
) : Closeable {
    /**
     * The IN endpoint's real packet size, used for the optional short-packet pad.
     *
     * A block whose length is an exact multiple of the link's packet size must be followed by a zero byte (or
     * a ZLP) or the host cannot tell it finished. That multiple is the endpoint's `maxPacketSize`, which is
     * not always 512: a full-speed link uses 64. Ported from upstream 5a5b5328 ("the optional short-packet
     * pad now follows the IN endpoint's packet size, so a full-speed (64-byte) link is framed correctly").
     */
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE

    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    /** Consecutive bulk-OUT frames the phone refused; a permanent dead link must end the session. */
    private var consecutiveWriteFailures = 0
    /** True once the phone accepted one frame, i.e. StartCarPlaySession has run (upstream #634). */
    private var bulkOutAccepted = false
    private var notReadyWriteTimeouts = 0
    private val frames = ArrayDeque<ByteArray>()
    /** Reused NTB16 block for [send]; only touched under [writeLock]. One Ethernet frame plus headers. */
    private var txBlock = ByteArray(TX_BLOCK_BYTES)
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    // Damaged-block recovery (upstream 5a5b5328, doc 102 P0-1). Android reaps a bulk transfer that failed
    // part way as a normal completion, so one bad NTB used to end the whole wired session; now the stream
    // skips to the next plausible header and lets TCP resend what was lost. Damage that keeps recurring
    // (more than MAX_RESYNCS_PER_WINDOW in 10 s) still fails the session.
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0
    private val readBuffer = ByteArray(readChunkBytes)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(readChunkBytes)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        WorkerThreads.create({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int): Unit = send(frame, frame.size, timeoutMillis)

    /**
     * Same as [send], for the first [length] bytes of a reused buffer. The block is framed into a buffer
     * owned by this bridge (guarded by [writeLock]), so a frame costs no allocation; [frame] may be
     * reused by the caller as soon as this returns.
     */
    fun send(frame: ByteArray, length: Int, timeoutMillis: Int): Unit = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val needed = Ntb16Codec.maxBlockBytes(length)
        if (txBlock.size < needed) txBlock = ByteArray(needed)
        val block = txBlock
        val blockLength = Ntb16Codec.buildInto(frame, length, sequence, block)
        // Until the phone takes its first frame the pipe is NAKed, so waiting the caller's whole
        // timeout on each frame delays the iAP2 handshake. Dropping after 20 ms is what the full
        // timeout would have done anyway (upstream 817ff3fa, #634).
        val effectiveTimeout = NcmWritePolicy.effectiveTimeout(timeoutMillis, bulkOutAccepted)
        // Resume a truncated bulk-out from its real offset under one shared deadline (UsbBulkWrite).
        // The slice is only built on the rare resume, so a normal frame writes in one call.
        val result = UsbBulkWrite.all(blockLength, effectiveTimeout) { offset, chunk, timeout ->
            if (offset == 0) {
                connection.bulkTransfer(outEndpoint, block, chunk, timeout)
            } else {
                val remaining = block.copyOfRange(offset, offset + chunk)
                connection.bulkTransfer(outEndpoint, remaining, remaining.size, timeout)
            }
        }
        val transferred = result.written
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!bulkOutAccepted) notReadyWriteTimeouts++
            // A NAK before StartCarPlaySession is normal and later frames must retry. An endpoint
            // that stays dead is not: without a bound the TX pump keeps discarding frames while its
            // counters still report them as sent, and only the AirPlay layer times out.
            if (++consecutiveWriteFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                throw failSession(
                    "NCM bulk-out failed $consecutiveWriteFailures consecutive frames",
                )
            }
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (!bulkOutAccepted) {
            bulkOutAccepted = true
            runCatching {
                onDiagnostic("NCM bulk-out ready after $notReadyWriteTimeouts short write timeouts")
            }
        }
        consecutiveWriteFailures = 0
        if (transferred != blockLength) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of $blockLength bytes" +
                    if (result.timedOut) " before its deadline" else "",
            )
        }
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // 写路径全程持有 writeLock 做最长 200 ms 的 bulkTransfer；释放接口与关闭 fd 必须等那次
        // 传输结束，否则 close(fd) 可能与 ioctl(USBDEVFS_BULK) 并发，fd 被回收后作用到别的 fd。
        synchronized(writeLock) {
            for (usbInterface in claimedInterfaces.asReversed()) {
                try {
                    connection.releaseInterface(usbInterface)
                } catch (_: RuntimeException) {
                    // Best-effort release; the connection close below is authoritative.
                }
            }
            connection.close()
        }
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        var loggedFailingPoll = false
        var polls = 0
        val startedNs = System.nanoTime()
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "ncm status probe started endpoint=0x${endpoint.address.toString(16)} " +
                "type=${endpoint.type} maxPacket=${endpoint.maxPacketSize} buffer=${buffer.size}",
        )
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (error: RuntimeException) {
                // Evidence for docs/_digest/99: an exception here means the bulk ioctl refuses this
                // interrupt endpoint, so the phone's notifications can never be consumed.
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status poll threw ${error.javaClass.simpleName}: ${error.message} " +
                        "endpoint=0x${endpoint.address.toString(16)} type=${endpoint.type}",
                )
                return
            }
            polls++
            if (transferred <= 0) {
                // Evidence for docs/_digest/99: bulkTransfer is USBDEVFS_BULK, and this endpoint is
                // interrupt. If the phone's NETWORK_CONNECTION is never consumed, the first poll already
                // says so (-1) instead of hiding behind "no notification arrived".
                if (!loggedFailingPoll && polls >= 2) {
                    loggedFailingPoll = true
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "ncm status poll result=$transferred polls=$polls " +
                            "elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L} " +
                            "endpoint=0x${endpoint.address.toString(16)} type=${endpoint.type}",
                    )
                }
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return
                }
            }
            if (bufferedSize < NTH16_LENGTH) return
            if (readU32Le(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return
                continue
            }
            val blockLength = readU16Le(buffered, 8)
            if (blockLength < 28) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return
                continue
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /**
     * Android reaps a bulk transfer that failed part way (babble, CRC, a dropped packet) as a
     * normal completion with whatever bytes arrived, so a damaged NTB can leave the stream
     * misaligned. Like Linux cdc_ncm, drop the damaged bytes and continue at the next valid NTB16
     * header; TCP resends what was lost. Returns false while no header is buffered yet. Damage
     * that keeps recurring still fails the session, as before.
     */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_NANOS) {
                resyncTimes.removeFirst()
            }
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw failSession("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        // Without a header, keep the bytes that could still be the start of one.
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    /** Stricter than the normal path, so payload bytes are unlikely to pass as a header. */
    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32Le(buffered, offset) != Ntb16Codec.NTH16_SIG) return false
        val headerLength = readU16Le(buffered, offset + 4)
        val blockLength = readU16Le(buffered, offset + 8)
        val ndpIndex = readU16Le(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= 28 &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(readChunkBytes, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { queuedBuffer ->
                        @Suppress("DEPRECATION")
                        current.queue(queuedBuffer, queuedBuffer.remaining())
                    }
                    if (!queued.queued) {
                        throw failSession(
                            "Android could not queue the NCM read request (api=19 " +
                                "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                                "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                        )
                    }
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            val fallback = "NCM read queue compatibility fallback api=19 " +
                "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                "fallbackBytes=${queued.fallbackBytes}"
            Log.i(IphoneCarPlayConfiguration.TAG, fallback)
            runCatching { onDiagnostic(fallback) }
        }
        try {
            val completed = try {
                // CS11: 4.4 走无参阻塞版
                @Suppress("DEPRECATION")
                connection.requestWait()
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val TX_BLOCK_BYTES = 1600 + 28 + 1
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        /**
         * ~20 s of failed 200 ms bulk-OUT attempts. Must stay well above the pre-StartCarPlaySession
         * NAK window (a few seconds) so a normal bring-up is never failed by this bound.
         */
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 100
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NTH16_LENGTH = 12
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
        internal const val USB_SETUP_ATTEMPTS = 5
        private const val USB_SETUP_RETRY_MILLIS = 100L

        /**
         * Retries a USB setup call that a vendor kernel can lose to its own cdc_ncm driver, which may
         * rebind between Android's forced detach and the claim (#518). Returns the successful attempt.
         */
        internal fun retryUsbSetup(
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            action: () -> Boolean,
        ): Int? {
            for (attempt in 1..attempts) {
                if (action()) return attempt
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return null
        }

        /** Same retry for the API 19 ioctl path, whose SET_INTERFACE returns an errno instead of a Boolean. */
        internal fun retryLegacySetInterface(
            descriptor: Int,
            interfaceId: Int,
            alternate: Int,
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
        ): Int {
            var result = -1
            for (attempt in 1..attempts) {
                result = LegacyUsbNative.setInterface(descriptor, interfaceId, alternate)
                if (result == 0) return 0
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return result
        }

        /**
         * API-19 userspace NCM open: claim the interfaces the raw descriptors describe, park the data
         * interface on its idle alternate, negotiate NTB16, then select the active alternate.
         *
         * The kernel's `cdc_ncm` driver normally does all of this, but binding it needs the root
         * channel that disappears on every reboot (docs/_digest/57 §7) - which is why this path exists.
         * Nothing in the API-19 Java surface performs the alternate-setting ioctl, so it goes through
         * [LegacyUsbNative]; the kernel-netdev path stays available as the caller's fallback.
         */
        fun openLegacy(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            rawDescriptors: ByteArray,
            configurationId: Int,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            if (!LegacyUsbNative.available) {
                throw IphoneUsbException.DeviceUnavailable(
                    "the userspace NCM bridge needs the legacy_usb native library",
                )
            }
            val layout = LegacyNcmDescriptors.ncm(rawDescriptors, configurationId)
            val order = LegacyNcmSelection.interfaceOrder(rawDescriptors, configurationId)
            val indices = LegacyNcmSelection.locate(order, layout)
                ?: throw IphoneUsbException.Protocol(
                    "The active iPhone USB configuration does not expose the parsed NCM interfaces",
                )
            // The platform list covers every configuration, the parsed order only this one: without the
            // offset the claim lands on another configuration's interfaces and the interface the kernel
            // driver holds is never claimed (measured: EBUSY on both setInterface forms).
            val platformOffset = LegacyNcmSelection.platformOffset(rawDescriptors, configurationId)
            val controlIndex = platformOffset + indices.control
            val dataIndex = platformOffset + indices.data
            if (controlIndex >= device.interfaceCount || dataIndex >= device.interfaceCount) {
                throw IphoneUsbException.Protocol(
                    "The active iPhone USB configuration does not expose the parsed NCM interfaces",
                )
            }
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val control = device.getInterface(controlIndex)
                val data = device.getInterface(dataIndex)
                val controlAttempts = retryUsbSetup { connection.claimInterface(control, true) }
                if (controlAttempts == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM control interface ${layout.control.id}" +
                            " attempts=$USB_SETUP_ATTEMPTS",
                    )
                }
                claimed.add(control)
                if (data !== control) {
                    val dataAttempts = retryUsbSetup { connection.claimInterface(data, true) }
                    if (dataAttempts == null) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${layout.data.id}" +
                                " attempts=$USB_SETUP_ATTEMPTS",
                        )
                    }
                    claimed.add(data)
                }
                // Measured on the car: parking interface 4 answered EBUSY (errno 16) from both the ioctl and
                // the control-transfer fallback, which says the interface we claimed is not the one we park -
                // `locate` returns positions in the descriptor order while these are platform list indices.
                // The platform's endpoint fingerprints are logged next to the parsed order so the two can be
                // compared (endpointCount/getEndpoint exist on API 19; getId/alternateSetting do not).
                onDiagnostic(
                    "NCM claim platformInterfaces=${device.interfaceCount} " +
                        (0 until device.interfaceCount).joinToString(" ") { index ->
                            val usbInterface = device.getInterface(index)
                            val endpoints = (0 until usbInterface.endpointCount).joinToString(",") {
                                "0x${usbInterface.getEndpoint(it).address.toString(16)}"
                            }
                            "p$index=[$endpoints]"
                        } +
                        " parsedOrder=${LegacyNcmSelection.interfaceOrder(rawDescriptors, configurationId)}" +
                        " platformOffset=$platformOffset controlIndex=$controlIndex dataIndex=$dataIndex" +
                        " wantedControl=${layout.control.id}/${layout.control.alternate}" +
                        " wantedData=${layout.data.id}/${layout.data.alternate}" +
                        " wantedIdle=${layout.idle.id}/${layout.idle.alternate}",
                )
                val descriptor = connection.fileDescriptor
                // Measured on this car: with the data interface parked on its idle alternate, the device
                // answered GET_NTB_PARAMETERS but stalled SET_NTB_FORMAT (both wValue 0 and 1) - so the
                // legacy build's order (idle, negotiate, active; V74/NcmUsbBridge.java:1349-1352) does not
                // hold for this iPhone. This activates the data interface first and negotiates afterwards;
                // the parking call stays in front of it so the idle state is still entered explicitly.
                val parkResult = retryLegacySetInterface(
                    descriptor, layout.idle.id, layout.idle.alternate,
                )
                if (parkResult != 0) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "could not park the NCM data interface ${layout.idle.id} on alternate " +
                            "${layout.idle.alternate} (${LegacyUsbNative.describe(parkResult)})",
                    )
                }

                val parameters = NcmNegotiation.configure(
                    connection, layout.control.id, layout.capabilities, onDiagnostic,
                )
                NcmNegotiation.enablePacketFilter(
                    connection, layout.control.id, layout.capabilities, onDiagnostic,
                )
                // Then activate the data interface, which is the order the reference implementation uses and
                // the reason its class requests succeed: measured on the car, its log reads alt=0, parameters,
                // format16, crcOff, inputSize, alt=1, packetFilter, ready - all succeeding - while this port
                // had been made to activate first and every one of those requests came back refused (-1)
                // before the bring-up carried on regardless.
                val activeResult = retryLegacySetInterface(
                    descriptor, layout.data.id, layout.data.alternate,
                )
                if (activeResult != 0) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "could not select the NCM data alternate ${layout.data.id}/" +
                            "${layout.data.alternate} (${LegacyUsbNative.describe(activeResult)})",
                    )
                }
                NcmNegotiation.lastStall?.let { stall ->
                    onDiagnostic("NCM legacy stage=classRequestStalled detail=$stall (continuing to the data path)")
                }
                val inEndpoint = bulkEndpoint(data, UsbConstants.USB_DIR_IN)
                    ?: throw IphoneUsbException.Protocol("NCM data interface exposes no bulk IN endpoint")
                val outEndpoint = bulkEndpoint(data, UsbConstants.USB_DIR_OUT)
                    ?: throw IphoneUsbException.Protocol("NCM data interface exposes no bulk OUT endpoint")
                val statusEndpoint = interruptInEndpoint(control)
                onDiagnostic(
                    "NCM legacy stage=ready iface=${layout.data.id}/${layout.data.alternate} " +
                        "input=${parameters.inputSize} output=${parameters.outputSize} " +
                        "status=${statusEndpoint?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    outEndpoint,
                    inEndpoint,
                    statusEndpoint,
                    claimed,
                    readNcmHostMac(connection, layout.control.id),
                    onDiagnostic,
                    parameters.inputSize,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("userspace NCM open failed", error)
            }
        }

        private fun bulkEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? =
            (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        (it.address and UsbConstants.USB_DIR_IN) == direction
                }

        private fun interruptInEndpoint(usbInterface: UsbInterface): UsbEndpoint? =
            (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        (it.address and UsbConstants.USB_DIR_IN) == UsbConstants.USB_DIR_IN
                }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    (controlInterfaceId < 0 || currentInterface == controlInterfaceId) &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_CONFIGURATION_DESCRIPTOR_TYPE = 0x02
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
