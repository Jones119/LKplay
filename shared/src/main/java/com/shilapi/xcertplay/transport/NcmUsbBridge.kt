package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.os.Build
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
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
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        // Resume a truncated bulk-out from its real offset under one shared deadline (UsbBulkWrite).
        // The slice is only built on the rare resume, so a normal frame writes in one call.
        val result = UsbBulkWrite.all(block.size, timeoutMillis) { offset, length, timeout ->
            val remaining = if (offset == 0 && length == block.size) block
            else block.copyOfRange(offset, offset + length)
            connection.bulkTransfer(outEndpoint, remaining, remaining.size, timeout)
        }
        val transferred = result.written
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes" +
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
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
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
                when (buffered[0].toInt() and 0xff) {
                    0 -> {
                        buffered.copyInto(buffered, 0, 1, bufferedSize)
                        bufferedSize -= 1
                    }
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> throw failSession("Invalid NTB16 short-packet pad")
                }
                optionalShortPacketPad = false
            }
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - blockLength
            buffered.copyInto(buffered, 0, blockLength, bufferedSize)
            bufferedSize = remaining
            optionalShortPacketPad = blockLength % USB_PACKET_SIZE == 0
        }
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
                        // CS11: UsbRequest.queue(ByteBuffer) 是 API26+；4.4 走 queue(buffer, length)
                        if (Build.VERSION.SDK_INT >= 26) {
                            current.queue(queuedBuffer)
                        } else {
                            @Suppress("DEPRECATION")
                            current.queue(queuedBuffer, queuedBuffer.remaining())
                        }
                    }
                    if (!queued.queued) {
                        throw failSession(
                            "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
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
            val fallback = "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                "fallbackBytes=${queued.fallbackBytes}"
            Log.i(IphoneCarPlayConfiguration.TAG, fallback)
            runCatching { onDiagnostic(fallback) }
        }
        try {
            val completed = try {
                // CS11: requestWait(long) 是 API26+；4.4 走无参阻塞版
                if (Build.VERSION.SDK_INT >= 26) {
                    connection.requestWait(timeoutMillis.coerceAtLeast(1))
                } else {
                    @Suppress("DEPRECATION")
                    connection.requestWait()
                }
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

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                // CS11: UsbInterface.getId 是 API21+；4.4 以 -1 表示"任意 CDC Ethernet 描述符"（读错则回退配置值）
                val controlInterfaceId = if (Build.VERSION.SDK_INT >= 21) function.control.id else -1
                val descriptorHostMac = readNcmHostMac(connection, controlInterfaceId)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"} controlId=$controlInterfaceId",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = if (Build.VERSION.SDK_INT >= 21) function.control.id == function.data.id else false
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                // CS11: alternateSetting / getId 是 API21+ 属性
                val altFirst = if (Build.VERSION.SDK_INT >= 21) first.alternateSetting else -1
                val firstId = if (Build.VERSION.SDK_INT >= 21) first.id else -1
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=$firstId/$altFirst class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface $firstId",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    val altDataClaim = if (Build.VERSION.SDK_INT >= 21) function.data.alternateSetting else -1
                    val dataId = if (Build.VERSION.SDK_INT >= 21) function.data.id else -1
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=$dataId/$altDataClaim" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface $dataId",
                        )
                    }
                    claimed.add(function.data)
                }
                // CS11: setInterface 是 API21+；4.4 用 controlTransfer 发标准 SET_INTERFACE(0x0B) 激活 alt1
                val altDataSel = if (Build.VERSION.SDK_INT >= 21) function.data.alternateSetting else DATA_ALTERNATE_SETTING_HINT
                val altSelected = if (Build.VERSION.SDK_INT >= 21) {
                    connection.setInterface(function.data)
                } else {
                    selectDataAlternatesViaControlTransfer(connection)
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=api${Build.VERSION.SDK_INT} alt=$altDataSel ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
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
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
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
                if (!connection.claimInterface(control, true)) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM control interface ${layout.control.id}",
                    )
                }
                claimed.add(control)
                if (data !== control) {
                    if (!connection.claimInterface(data, true)) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${layout.data.id}",
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
                val parkResult = LegacyUsbNative.setInterface(
                    descriptor, layout.idle.id, layout.idle.alternate,
                )
                if (parkResult != 0) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "could not park the NCM data interface ${layout.idle.id} on alternate " +
                            "${layout.idle.alternate} (${LegacyUsbNative.describe(parkResult)})",
                    )
                }
                val activeResult = LegacyUsbNative.setInterface(
                    descriptor, layout.data.id, layout.data.alternate,
                )
                if (activeResult != 0) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "could not select the NCM data alternate ${layout.data.id}/" +
                            "${layout.data.alternate} (${LegacyUsbNative.describe(activeResult)})",
                    )
                }
                onDiagnostic(
                    "NCM legacy stage=activeBeforeNegotiation idle=${layout.idle.id}/" +
                        "${layout.idle.alternate} active=${layout.data.id}/${layout.data.alternate}",
                )
                val parameters = NcmNegotiation.configure(
                    connection, layout.control.id, layout.capabilities, onDiagnostic,
                )
                NcmNegotiation.enablePacketFilter(
                    connection, layout.control.id, layout.capabilities, onDiagnostic,
                )
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
        private const val DATA_ALTERNATE_SETTING_HINT = 1
        private const val USB_REQUEST_TYPE_SET_INTERFACE = 0x01
        private const val USB_REQUEST_SET_INTERFACE = 0x0B

        /**
         * CS11: 4.4 没有 setInterface API——用 controlTransfer 对全部 CDC 数据接口(class 0x0a)
         * 发送标准 SET_INTERFACE(备用设定 1)，激活其 bulk 端点。接口号从 rawDescriptors 解析。
         */
        private fun selectDataAlternatesViaControlTransfer(connection: UsbDeviceConnection): Boolean {
            val raw = connection.rawDescriptors ?: return false
            val numbers = dataInterfaceNumbers(raw)
            if (numbers.isEmpty()) return false
            var any = false
            for (number in numbers) {
                val result = connection.controlTransfer(
                    USB_REQUEST_TYPE_SET_INTERFACE, USB_REQUEST_SET_INTERFACE,
                    DATA_ALTERNATE_SETTING_HINT, number, null, 0, USB_CONTROL_TIMEOUT_MILLIS,
                )
                Log.i(IphoneCarPlayConfiguration.TAG, "CS11 SET_INTERFACE(alt=1, iface=$number) -> $result")
                if (result >= 0) any = true
            }
            return any
        }

        private fun dataInterfaceNumbers(raw: ByteArray): List<Int> {
            val numbers = ArrayList<Int>(2)
            var offset = 0
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) break
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    val ifaceClass = raw[offset + 5].toInt() and 0xff
                    val ifaceNumber = raw[offset + 2].toInt() and 0xff
                    if (ifaceClass == NcmFunctionDiscovery.DATA_CLASS && !numbers.contains(ifaceNumber)) {
                        numbers.add(ifaceNumber)
                    }
                }
                offset += length
            }
            return numbers
        }
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
