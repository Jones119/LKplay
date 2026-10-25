package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.math.BigInteger
import com.shilapi.xcertplay.transport.DatagramTransport
import com.shilapi.xcertplay.transport.DuplexServer
import com.shilapi.xcertplay.transport.DuplexSocket
import com.shilapi.xcertplay.transport.SessionNetwork
import com.shilapi.xcertplay.transport.kernelSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.compat.WorkerThreads

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    /** Video in car: a playback message on a remote control session (X-Apple-StreamID). */
    fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {}
    /** Video in car: requestUI "videoplayback:", the iPhone asks the car to show its video player. */
    fun onVideoPlaybackUiRequested(session: AirPlaySession) {}
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /** CarPlay's main buffered audio (type 103): the SETUP response, or null to decline it. */
    fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /**
     * SETRATE, SETRATEANCHORTIME, GETANCHOR and FLUSHBUFFERED for the buffered stream: the response
     * plist (the anchor), or null for an empty 200.
     */
    fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession(
    private val socket: DuplexSocket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    /** Endpoints for the keep-alive and event channels; the control socket arrives already accepted. */
    private val network: SessionNetwork = SessionNetwork.kernel,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<Int>()

    /** Counts the iPhone's cluster stream setups; 0 while no cluster stream is up. */
    @Volatile var clusterStream = 0
        private set
    private var clusterStreamSetups = 0
    @Volatile private var mainScreenToken: Any? = null

    private val closed = AtomicBoolean(false)
    internal val isClosed: Boolean get() = closed.get()
    private val notified = AtomicBoolean(false)
    private var eventServer: DuplexServer? = null
    private var eventSocket: DuplexSocket? = null
    private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val ntp = NtpClock(network)
    private var keepAliveSocket: DatagramTransport? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.remoteSocketAddress?.address?.hostAddress ?: ""
    val localAddress: InetAddress? = withLinkLocalScope(socket.localSocketAddress?.address)
    private val peerAddress: InetAddress? = socket.remoteSocketAddress?.address
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()
    val videoInCar: Boolean get() = config.videoInCar
    private val videoPlaybackAvailability = VideoPlaybackAvailability { allowed ->
        sendCommand(
            linkedMapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to linkedMapOf("videoPlaybackAllowed" to allowed),
            ),
        )
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) = debugLog(message)

    internal fun videoFrameRendered() {
        if (!closed.get()) listener.onVideoFrameRendered(this)
    }

    internal fun logTrace(message: String) = trace(message)

    fun start() {
        // Data/NTP/event servers bind whatever address the phone reached the control channel on: the
        // wired netdev link-local or the wireless soft-AP IPv4 address (see serverBindAddress()).
        publishSessionBindAddress(localAddress)
        WorkerThreads.create(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainScreenToken = null
        clearClusterContent()
        safeClose(socket)
        // Tell the owner before the media teardown below. Closing a stream on the userspace stack can
        // park on a reader blocked in recv(), and when that happened (tablet bench, 2026-10-19:
        // "airplay event connection closed" was the last line, the NCM link froze, and no
        // "AirPlay session ended" ever arrived) the owner never learned the session was over, so
        // nothing tore the connection down or reconnected. The notification is idempotent and only
        // touches this session's state, so it is safe ahead of the teardown.
        if (notified.compareAndSet(false, true)) {
            try {
                listener.onSessionEnded(this)
            } catch (error: Exception) {
                Log.w(TAG, "airplay session ended callback failed", error)
            }
        }
        try {
            // Off this thread on purpose: closing a stream whose reader is parked in a userspace recv()
            // can wait for that reader forever (tablet bench, 2026-10-19 - the teardown never returned,
            // so the session-end callback below never ran either). The reconnect must not depend on a
            // stream thread waking up.
            WorkerThreads.create(
                {
                    try {
                        media.onSessionClosed(this)
                    } catch (error: Exception) {
                        Log.w(TAG, "airplay media stream teardown failed", error)
                    }
                },
                "airplay-media-close",
            ).apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "airplay media teardown thread failed", error)
        }
        teardown()
    }

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    internal fun mainScreenSessionToken(): Any? = if (closed.get()) null else mainScreenToken

    /**
     * Asks the iPhone to draw CarPlay's cluster UI on the alt screen (showUI with the display's URL,
     * then a keyframe) or to stop drawing it (stopUI). The stream stays up either way; CarKit handles
     * both as car-initiated commands for a screen UUID.
     */
    fun setClusterUiShown(shown: Boolean): Boolean {
        synchronized(clusterCommandLock) {
            if (closed.get() || clusterStream <= 0) return false
            val content = synchronized(clusterContentLock) { clusterContent } ?: return false
            if (!shown) {
                val sent = sendCommand(mapOf("type" to "stopUI",
                    "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)))
                return synchronized(clusterContentLock) {
                    val current = clusterContent ?: return false
                    if (closed.get() || current.stream != content.stream) {
                        if (sent) clusterContent = current.copy(displayed = null, shown = false, token = Any())
                        return false
                    }
                    if (sent) clusterContent = current.copy(shown = false)
                    sent
                }
            }
            return showClusterUrl(content, content.requested ?: return false)
        }
    }

    private data class ClusterContent(val stream: Int, val requested: String?, val displayed: String?,
        val shown: Boolean = true, val token: Any = Any())
    private val clusterContentLock = Any()
    private val clusterCommandLock = Any()
    @Volatile private var clusterContent: ClusterContent? = null
    private var requestedClusterUrl = config.cluster?.initialUrl // guarded by clusterContentLock

    /** Last delivered content for this stream. Pending selections never grant wheel zoom eligibility. */
    fun clusterUrl(): String? = clusterContentRoute()?.second

    /** One immutable snapshot keeps content and generation checks consistent without locking keys. */
    internal fun clusterContentRoute(): Triple<Int, String?, Any>? {
        val content = clusterContent ?: return null
        if (closed.get() || content.stream != clusterStream) return null
        return Triple(content.stream, content.displayed, content.token)
    }

    /**
     * Switches the dashboard to another of the iPhone's `altScreenURLs` without reconnecting: `showUI`
     * with that URL and a keyframe (on a Tang this switches map, turn card and both at once). With [send]
     * false (the map is paused) the URL is only kept for the next `showUI`. False without a cluster stream.
     */
    fun setClusterUrl(url: String, send: Boolean): Boolean {
        return synchronized(clusterCommandLock) {
            val content = synchronized(clusterContentLock) {
                val current = clusterContent ?: return false
                if (closed.get() || current.stream != clusterStream || config.cluster == null) return false
                requestedClusterUrl = url
                current.copy(requested = url).also { clusterContent = it }
            }
            if (!send) true else showClusterUrl(content, url)
        }
    }

    /** Seeds a retained controller selection even when this phone has not set up its cluster yet. */
    internal fun restoreClusterUrl(url: String, send: Boolean): Boolean = synchronized(clusterCommandLock) {
        val content = synchronized(clusterContentLock) {
            if (closed.get() || config.cluster == null) return false
            requestedClusterUrl = url
            clusterContent?.copy(requested = url)?.also { clusterContent = it }
        }
        if (content == null || !send || (content.shown && content.displayed == url)) true
        else showClusterUrl(content, url)
    }

    private fun showClusterUrl(content: ClusterContent, url: String): Boolean {
        if (closed.get() || clusterStream != content.stream) return false
        val uuid = AirPlayInfoPlist.ALT_UUID
        val uiSent = sendCommand(mapOf("type" to "showUI", "params" to mapOf("uuid" to uuid, "url" to url)))
        val sent = uiSent && clusterStream == content.stream && !closed.get() &&
            sendCommand(mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to uuid)))
        return synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (closed.get() || current.stream != content.stream || clusterStream != content.stream) {
                // A command already in flight can outlast re-SETUP. Its displayed result is unknown.
                if (uiSent) clusterContent = current.copy(displayed = null, token = Any())
                return false
            }
            if (uiSent) clusterContent = current.copy(displayed = if (sent) url else null,
                shown = true, token = Any())
            sent
        }
    }

    private fun beginClusterContent() = synchronized(clusterContentLock) {
        val stream = ++clusterStreamSetups
        val initial = config.cluster?.initialUrl
        clusterContent = ClusterContent(stream, requestedClusterUrl, initial)
        clusterStream = stream
    }

    private fun clearClusterContent() = synchronized(clusterContentLock) {
        clusterStream = 0
        clusterContent = null
    }

    // Re-SETUP starts at initialURL. Reapply the saved live selection only after SETUP was answered,
    // or the event channel becomes ready, without claiming it was displayed before delivery.
    private fun reapplyClusterContent() {
        synchronized(clusterCommandLock) {
            val content = clusterContent ?: return
            val requested = content.requested ?: return
            if (content.shown && requested != content.displayed) showClusterUrl(content, requested)
        }
    }

    /**
     * Zooms CarPlay's dashboard map one step, as a car's own zoom controls do: the car command
     * changeMapZoomLevel for the cluster screen's UUID. Seen with Apple Maps on a Tang: zoomDirection 0
     * zooms in, 1 zooms out. Sent only while the cluster stream is up.
     */
    fun changeMapZoomLevel(zoomIn: Boolean): Boolean = clusterStream > 0 && sendCommand(
        linkedMapOf(
            "type" to "changeMapZoomLevel",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.ALT_UUID,
                "zoomDirection" to if (zoomIn) ZOOM_DIRECTION_IN else ZOOM_DIRECTION_OUT,
            ),
        ),
    )

    /** Moves CarPlay on the main screen to one of its declared view areas, without reconnecting. */
    fun showViewArea(index: Int): Boolean =
        sendCommand(AirPlayInfoPlist.viewAreaCommand(index, config.main.viewAreas?.size ?: 1))

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    /**
     * A message on a video in car remote control session, framed like the iPhone's own: POST /command
     * with X-Apple-StreamID and {params: {data: bplist(message)}}.
     */
    fun sendRemoteControlMessage(streamId: Long, message: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(
            linkedMapOf("params" to linkedMapOf("data" to BplistCodec.encode(message))),
            "X-Apple-StreamID: $streamId\r\n",
        )
    }

    /**
     * Video in car: retains whether video may play now and sends the latest value once SETUP has
     * enabled video and the event channel is ready. An iPhone without the feature gets no command.
     */
    internal fun setVideoPlaybackAllowed(allowed: Boolean): VideoPlaybackDelivery =
        videoPlaybackAvailability.setDesired(allowed)

    private fun sendCommandLocked(command: Map<String, Any?>, extraHeaders: String = ""): Boolean {
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace { "airplay event tx headers=$head bodyHex=${body.toHex()}" }
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.outputStream
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            close()
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>, enqueuedNs: Long = System.nanoTime()): Boolean {
        // CS11: the report is encoded straight from the normalized contacts (docs/_digest/51 §12). The
        // previous `contacts.map { it.copy(x = it.x * widthPixels, ...) }` allocated a scaled list plus
        // one object per finger for each of the 60-120 states a drag produces every second.
        val report = AirPlayHid.touchReport(contacts, config.main.widthPixels, config.main.heightPixels)
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent) {
            com.shilapi.xcertplay.media.TouchLatencyProbe.onStateSent(
                enqueuedNs, sendStartNs, System.nanoTime() - sendStartNs,
            )
        }
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = contacts.firstOrNull()
            val firstX = first?.let { Math.round((it.x * config.main.widthPixels).coerceAtLeast(0.0)) }
            val firstY = first?.let { Math.round((it.y * config.main.heightPixels).coerceAtLeast(0.0)) }
            Log.i(
                TAG,
                "airplay touch report sent contacts=${contacts.size} first=" +
                    "($firstX,$firstY,down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommandBody(BplistCodec.encodeHidReport(uid, report))

    /**
     * [sendCommandLocked] for a body that is already encoded.
     *
     * Why (docs/_digest/69 P0-4, docs/_digest/81): a drag sends 60-120 `hidSendReport` commands per
     * second, and going through `BplistCodec.encode` built roughly 60 objects each time (a `Node` plus
     * several temporary lists and arrays per string and value). [BplistCodec.encodeHidReport] produces
     * byte-identical output from a fixed layout, so this path now allocates only the body itself.
     */
    private fun sendCommandBody(body: ByteArray): Boolean = synchronized(eventWriteLock) {
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val head = "POST /command RTSP/1.0\r\n" +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace { "airplay event tx headers=$head bodyHex=${body.toHex()}" }
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.outputStream
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command body failed bytes=${body.size}", error)
            close()
            false
        }
    }

    private fun runControl() {
        // TcpLiveness tunes keepalive through the socket's file descriptor, which only the kernel
        // stack has; a userspace stack would set those options through lwIP instead.
        socket.kernelSocket()?.let { kernel ->
            com.shilapi.xcertplay.network.TcpLiveness.configure(kernel) { debugLog(it) }
        }
        val input = BufferedInputStream(socket.inputStream)
        val output = BufferedOutputStream(socket.outputStream)
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var closeReason = "session closed"
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    closeReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                accumulated = parsed.rest
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        AirPlayControlDiagnostics.request(request.method, request.path, request.body.size),
                        false,
                    )
                    trace(
                        "airplay control rx headers=${request.headers} " +
                            "bodyHex=${request.body.toHex()}",
                    )
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        debugLog(
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq " +
                                "error=${error.javaClass.name}: ${error.message} at " +
                                error.stackTrace.take(8).joinToString(" <- ") { it.toString() },
                        )
                        RtspMessage.Response(status = 500)
                    }
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        "airplay control response status=${response.status ?: 200} contentBytes=${response.body.size}",
                        false,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    trace("airplay control tx wireHex=${wire.toHex()}")
                    output.write(cipher?.encrypt(wire) ?: wire)
                    if (cipher == null && pairVerify.controlKeys != null) {
                        val keys = pairVerify.controlKeys!!
                        cipher = ControlCipher(keys.readKey, keys.writeKey)
                        debugLog("airplay control encryption enabled")
                    }
                }
                output.flush()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!closed.get()) Log.e(TAG, "airplay $closeReason", error)
        } finally {
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            close()
        }
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        when (request.method) {
            "SETUP" -> return handleSetup(request)
            "RECORD" -> {
                listener.onSessionActive(this)
                return RtspMessage.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        if (config.bufferedAudioOutputEnabled && request.method in BUFFERED_AUDIO_METHODS) {
            val body = if (request.body.isEmpty() && request.method == "GETANCHOR") emptyMap() else {
                runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull()
                    ?: return RtspMessage.Response(status = 400)
            }
            val response = media.onBufferedAudioControl(this, request.method, body)
            if (request.method != "GETANCHOR") debugLog("airplay ${request.method} $body -> ${response ?: "ok"}")
            return if (response == null) RtspMessage.Response(status = 200) else RtspMessage.Response(
                headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                body = BplistCodec.encode(response),
            )
        }

        val path = request.path.lowercase()
        return when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                val info = AirPlayInfoPlist.build(config, AudioFormatPolicy.current())
                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                // The OEM icon never appeared on the CS11 while /info kept declaring it, so what we
                // actually declare is logged once per session (OemIconDiagnostic explains the format).
                Log.i(TAG, oemIconDiagnostic(config))
                debugLog(
                    "airplay /info videoInCar=${config.videoInCar} " +
                        "videoPlaybackAllowed=${if (config.videoInCar) VideoInCar.allowed else "not-offered"}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
        reapplyClusterContent()
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun trace(message: String) {
        // Per-frame/hex traces are gated: they were a large share of the app's logcat volume and the
        // formatting happens in the event and touch paths (docs/_digest/41 §4).
        if (!com.shilapi.xcertplay.diag.DiagLog.traceEnabled) return
        try {
            listener.onDebugLog("TRACE $message")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    /** Per-event traces build hex dumps, so the message is only produced while tracing is on. */
    private inline fun trace(message: () -> String) {
        if (!com.shilapi.xcertplay.diag.DiagLog.traceEnabled) return
        trace(message())
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessage.Response(status = 400)
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            trace("airplay SETUP response bplistHex=${body.toHex()}")
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            try {
                listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
            } catch (error: Exception) {
                debugLog("airplay onDeviceInfo callback failed ${error.javaClass.name}: ${error.message}")
            }
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val timingPort = try {
            openTiming(peerTimingPort)
        } catch (error: Exception) {
            debugLog(
                "airplay openTiming failed ${error.javaClass.name}: ${error.message} at " +
                    error.stackTrace.take(6).joinToString(" <- ") { it.toString() },
            )
            throw error
        }
        val eventPort = try {
            openEvent()
        } catch (error: Exception) {
            debugLog(
                "airplay openEvent failed ${error.javaClass.name}: ${error.message} at " +
                    error.stackTrace.take(6).joinToString(" <- ") { it.toString() },
            )
            throw error
        }
        val response = linkedMapOf<String, Any?>(
            "timingPort" to timingPort,
            "eventPort" to eventPort,
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        val features = setupEnabledFeatures(config, dict["features"] as? List<*>)
        val videoPlaybackEnabled = VideoInCar.FEATURE in features
        val videoDelivery = videoPlaybackAvailability.setFeatureEnabled(videoPlaybackEnabled)
        debugLog("airplay video playback negotiated=$videoPlaybackEnabled availability=$videoDelivery")
        response["enabledFeatures"] = features
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            debugLog("airplay SETUP stream type=$type payload=$stream")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    if (port != null) {
                        activeStreams.add(type)
                        if (type == STREAM_TYPE_ALT_SCREEN) beginClusterContent()
                        else mainScreenToken = Any()
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_MAIN_BUFFERED_AUDIO -> {
                    val streamResponse = if (config.bufferedAudioOutputEnabled) media.onBufferedAudio(this, stream) else null
                    debugLog("airplay buffered audio stream accepted=${streamResponse != null} " +
                        "dataPort=${streamResponse?.get("dataPort") ?: "none"}")
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        debugLog("airplay command type=$type keys=${params.keys.sorted()}")
        val streamId = request.headers["x-apple-streamid"]?.toLongOrNull()
        val data = params["data"] as? ByteArray
        if (streamId != null && data != null) {
            runCatching { asMap(BplistCodec.decode(data)) }.getOrNull()
                ?.let { listener.onRemoteControlMessage(this, streamId, it) }
            return RtspMessage.Response(status = 200)
        }
        if (type == "requestUI" && string(params["url"]) == VideoInCar.UI_URL) {
            listener.onVideoPlaybackUiRequested(this)
        } else if (type == "requestUI") {
            listener.onHostUiRequested(this)
        }
        listener.onCommand(this, type, params)
        return RtspMessage.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)

        debugLog(
            "airplay TEARDOWN types=${types ?: "all"} activeBefore=$activeStreams " +
                "body=${request.body.size} bytes payload=$decodedBody",
        )
        trace("airplay TEARDOWN raw${request.body.size}Hex=${request.body.toHex()}")

        // Restore wheel input before releasing media resources, which may take time to close.
        if (types == null || STREAM_TYPE_MAIN_SCREEN in types) mainScreenToken = null
        if (types == null || STREAM_TYPE_ALT_SCREEN in types) clearClusterContent()
        if (types == null) {
            activeStreams.toList().forEach { media.onTeardown(this, it) }
            activeStreams.clear()
        } else {
            types.forEach { type -> if (activeStreams.remove(type)) media.onTeardown(this, type) }
        }
        if (STREAM_TYPE_ALT_SCREEN !in activeStreams) clearClusterContent()
        return RtspMessage.Response(status = 200)
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        val socket = network.open(serverBindAddress())
        keepAliveSocket = socket
        keepAliveThread = WorkerThreads.create({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramTransport) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                socket.receive(buffer)
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        val server = network.listen(serverBindAddress(), port = 0, backlog = 50, reuseAddress = false)
        eventServer = server
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        videoPlaybackAvailability.setEventReady(false)
        videoPlaybackAvailability.setFeatureEnabled(false)
        clearSessionBindAddress()
        ntp.close()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        safeClose(eventServer)
        eventServer = null
        safeClose(eventSocket)
        eventSocket = null
        eventCipher = null
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: DuplexServer) {
        try {
            val socket = server.accept()
            socket.setSoLinger(true, 0)
            // Avoid Nagle coalescing on this interactive event connection: small HID/event writes
            // need not wait for earlier unacknowledged data.
            socket.tcpNoDelay = true
            debugLog("airplay event connection accepted from ${socket.remoteSocketAddress} noDelay=${socket.tcpNoDelay}")
            eventSocket = socket
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            eventCipher = ControlCipher(readKey, writeKey)
            synchronized(eventWriteLock) {
                sendPendingNightModeLocked()
            }
            val videoDelivery = videoPlaybackAvailability.setEventReady(true)
            debugLog("airplay video event ready availability=$videoDelivery")
            reapplyClusterContent()
            runEventRead(socket)
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "airplay event accept failed", error)
                close()
            }
        }
    }

    private fun runEventRead(socket: DuplexSocket) {
        try {
            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                val cipher = eventCipher ?: break
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                encrypted = decrypted.rest
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) continue
                    com.shilapi.xcertplay.diag.DiagLog.trace(TAG) {
                        "airplay event rx ${message.method} ${message.path} " +
                            "cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}"
                    }
                    val response = RtspMessage.buildResponse(message, RtspMessage.Response(status = 200))
                    trace {
                        "airplay event rx headers=${message.headers} " +
                            "bodyHex=${message.body.toHex()}"
                    }
                    trace { "airplay event tx wireHex=${response.toHex()}" }
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) Log.e(TAG, "airplay event read failed", error)
        } finally {
            debugLog("airplay event connection closed")
            videoPlaybackAvailability.setEventReady(false)
            // Only the connection that owns the field may clear it: a newer event socket may
            // already have replaced this one.
            if (eventSocket === socket) {
                eventSocket = null
                eventCipher = null
            }
            safeClose(socket)
            if (!closed.get()) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = WorkerThreads.create(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103
        private val BUFFERED_AUDIO_METHODS = setOf("SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED")

        const val READ_CHUNK_BYTES = 16 * 1024
        const val ZOOM_DIRECTION_IN = 0
        const val ZOOM_DIRECTION_OUT = 1
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** The features SETUP enables; video in car only when configured and the iPhone [proposed] it. */
/** The session feature the iPhone proposes for main buffered audio. */
internal const val MAIN_BUFFERED_FEATURE = "mainBuffered"

internal fun setupEnabledFeatures(config: AirPlayConfig, proposed: List<*>?): List<String> {
    val features = mutableListOf<String>()
    features.add("iAPChannel")
    features.add("viewAreas")
    if (config.cluster != null) features.add("altScreen")
    if (config.videoInCar && proposed.orEmpty().contains(VideoInCar.FEATURE)) features.add(VideoInCar.FEATURE)
    if (config.bufferedAudioOutputEnabled && proposed.orEmpty().contains(MAIN_BUFFERED_FEATURE)) features.add(MAIN_BUFFERED_FEATURE)
    return features
}

/**
 * The stream types a TEARDOWN body closes, or null when it names none (the whole session). Several
 * type-130 streams can be open, the iAP tunnel (streamID 1) and video in car's data streams; tearing
 * down one of the latter must not close the iAP tunnel.
 */
internal fun teardownStreamTypes(body: Any?): List<Int>? {
    val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
        val stream = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
        type to (stream["streamID"] as? Number)?.toLong()
    }
    if (entries.isEmpty()) return null
    return entries.filterNot { (type, streamId) -> type == 130 && streamId != null && streamId != 1L }.map { it.first }
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
