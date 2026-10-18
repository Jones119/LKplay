package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
) {
    private val appContext = context.applicationContext

    /**
     * Remembers which attachment is already being prompted.
     *
     * Re-enumeration makes the controller ask again for a device the user is still looking at
     * (CarPlayController.onIphoneAttached -> requestIphonePermission), and Android shows one
     * system dialog per `requestPermission` call. EasyPlay v71 keeps the same per-attach state
     * (`IphoneUsbPermissionCoordinator.observe`, `:222-283`).
     */
    private val permissionGate = UsbPermissionGate()

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) {
            permissionGate.resolved(device)
            return PermissionRequest.AlreadyGranted(device)
        }
        permissionGate.forgetDetached(usbManager.deviceList.keys)
        if (!permissionGate.shouldRequest(device)) {
            // The same attachment already has a prompt pending, or the user refused it. Asking
            // again would stack a second system dialog on the one they are looking at.
            Log.i(IphoneCarPlayConfiguration.TAG, "usb permission prompt suppressed for ${device.deviceName}")
            return PermissionRequest.Requested(device)
        }
        permissionGate.requested(device)
        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /**
     * Returns null for unrelated broadcasts, malformed results, or devices that are not currently
     * attached.
     *
     * The device handed back is the one from the current enumeration, never the object the system
     * carried through the permission dialog: after the iPhone switches USB mode the old object no
     * longer opens, and every caller here goes on to open or reconfigure the device
     * (`IphoneCarPlayConfiguration.find`, `activeConfigCarPlayReady`, `beginReenumeration`).
     */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        val attached = usbManager.deviceList[device.deviceName] ?: return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            permissionGate.resolved(attached)
            PermissionResult.Granted(attached)
        } else {
            permissionGate.denied(attached)
            PermissionResult.Denied(attached)
        }
    }

    /**
     * Forgets which attachment is mid-prompt or was refused.
     *
     * The user asking for a reconnect is the one case where a suppressed prompt must come back:
     * without this, refusing the dialog once would leave that attachment silent until it is
     * unplugged.
     */
    fun resetPermissionGate() {
        permissionGate.forgetDetached(emptyList())
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection -> sendCarPlayConfigurationRequest(connection) })
        }
    }

    /**
     * Synchronous form of [requestCarPlayReenumerationAsync], for the teardown path.
     *
     * A teardown runs on a background thread and closes the session's connection right after this
     * call, so the vendor request has to be finished before it returns - an executor hop would race
     * the close and the iPhone would never see the detach that makes it drop the old CarPlay session.
     */
    fun requestCarPlayReenumeration(device: UsbDevice): TransitionResult =
        runTransition(device) { connection -> sendCarPlayConfigurationRequest(connection) }

    private fun sendCarPlayConfigurationRequest(connection: UsbDeviceConnection): TransitionResult {
        val response = ByteArray(VENDOR_RESPONSE_LENGTH)
        val transferred = connection.controlTransfer(
            USB_VENDOR_DEVICE_IN,
            CARPLAY_CONFIGURATION_REQUEST,
            0,
            CARPLAY_CONFIGURATION_INDEX,
            response,
            response.size,
            CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        if (transferred != response.size) {
            throw IphoneUsbException.Protocol(
                "CarPlay configuration request transferred $transferred of ${response.size} bytes",
            )
        }
        return TransitionResult.ReenumerationRequested
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            // CS11: API19 无配置枚举/setConfiguration——用活动配置直取接口；API21+ 走原有配置选择
            var configId = -1
            val usbMux = if (Build.VERSION.SDK_INT >= 21) {
                val configuration = IphoneCarPlayConfiguration.find(device)
                    ?: throw IphoneUsbException.Protocol(
                        "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                    )
                configId = configuration.id
                if (!connection.setConfiguration(configuration)) {
                    Log.w(
                        IphoneCarPlayConfiguration.TAG,
                        "setConfiguration ${configuration.id} reported failure; claiming anyway",
                    )
                }
                IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                    ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            } else {
                Log.i(IphoneCarPlayConfiguration.TAG, "config selection skipped (api<21); using active USBMUX interface")
                IphoneCarPlayConfiguration.activeUsbMuxInterface(device)
                    ?: throw IphoneUsbException.Protocol("Active iPhone configuration exposes no USBMUX interface")
            }
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            // CS11: alternateSetting / UsbInterface.getId 是 API21+ 属性
            val altSetting = if (Build.VERSION.SDK_INT >= 21) usbMux.alternateSetting else -1
            val ifaceId = if (Build.VERSION.SDK_INT >= 21) usbMux.id else -1
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=$configId iface=$ifaceId alt=$altSetting " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        // The system answers by filling EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into this
        // intent. An immutable PendingIntent silently discards those extras (API 31+), so the
        // answer never reaches parsePermissionResult and the connection waits forever.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    // One persistent read request and one direct buffer for the whole session, as in the NCM
    // bridge: the previous shape allocated both per read and cancelled plus drained the request at
    // every idle timeout. Only close() cancels it now.
    private var pendingRead: UsbRequest? = null
    private var readQueued = false
    private val readBuffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        // A short transfer is resumed from the offset the pipe reached (MUSB can truncate a large
        // buffer) under one shared deadline; see UsbBulkWrite. The slice is only built on the rare
        // resume, so the normal single-shot path allocates nothing.
        val result = UsbBulkWrite.all(data.size, timeoutMillis) { offset, length, timeout ->
            val remaining = if (offset == 0 && length == data.size) data
            else data.copyOfRange(offset, offset + length)
            connection.bulkTransfer(outEndpoint, remaining, remaining.size, timeout)
        }
        if (!result.isComplete(data.size)) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred ${result.written} of ${data.size} bytes" +
                    if (result.timedOut) " before its deadline" else "",
            )
        }
    }

    /**
     * Returns null only when no completed USB request arrives before [timeoutMillis].
     *
     * The request and its direct buffer live for the whole session (the shape the NCM bridge already
     * uses): the previous version allocated a UsbRequest plus a 64 KiB direct buffer on every read
     * and cancelled plus drained the request at every idle timeout, churning the Dalvik heap and
     * re-queueing a fresh URB for every poll. A timeout now leaves the request queued and the next
     * read simply waits again; only [close] cancels it.
     */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue under one lock hold: close() cancels whatever is published here, and
            // the policy must not retry a rejected submission after the session was closed.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = pendingRead ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                        )
                    }
                    pendingRead = it
                }
                if (!readQueued) {
                    readBuffer.clear()
                    val queueResult = readQueuePolicy.queue(readBuffer, ::checkOpenLocked) { queuedBuffer ->
                        // CS11: UsbRequest.queue(ByteBuffer) 是 API26+；4.4 走 queue(buffer, length)
                        if (Build.VERSION.SDK_INT >= 26) {
                            current.queue(queuedBuffer)
                        } else {
                            @Suppress("DEPRECATION")
                            current.queue(queuedBuffer, queuedBuffer.remaining())
                        }
                    }
                    if (!queueResult.queued) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis, readBuffer.capacity())} " +
                                "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                        )
                    }
                    readQueued = true
                    acceptedFallback = queueResult
                }
                current
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
        // The policy caches the accepted size, so the controller refuses the requested read size
        // once per pipe. Log it as well as reporting it: the session has no diagnostic listener
        // wired in production, and logcat is how this head unit is diagnosed.
        acceptedFallback?.let { queued ->
            if (queued.fallbackBytes != null) {
                val fallback = "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                    "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                    "fallbackBytes=${queued.fallbackBytes}"
                Log.i(IphoneCarPlayConfiguration.TAG, fallback)
                runCatching { onDiagnostic(fallback) }
            }
        }
        try {
            val completed = try {
                // CS11: requestWait(long) 是 API26+；4.4 走无参阻塞版（由 close() 的 cancel 唤醒）
                if (Build.VERSION.SDK_INT >= 26) {
                    connection.requestWait(timeoutMillis)
                } else {
                    @Suppress("DEPRECATION")
                    connection.requestWait()
                }
            } catch (_: TimeoutException) {
                // Nothing arrived; the request stays queued for the next read instead of being
                // cancelled and drained on every idle period.
                return@synchronized null
            }
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request")
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            readQueued = false
            val transferred = readBuffer.position()
            if (transferred <= 0) return@synchronized ByteArray(0)
            return@synchronized ByteArray(transferred).also {
                readBuffer.flip()
                readBuffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        connection.close()
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long, bufferBytes: Int? = null): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
        if (bufferBytes != null) append(" bufferBytes=").append(bufferBytes)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi authentication failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
