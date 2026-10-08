package com.shilapi.xcertplay

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.compat.systemService

/** Whether the phone still holds the Bluetooth media (A2DP) link to this head unit. */
internal enum class MediaAudioLinkState { CONNECTED, DISCONNECTED, UNKNOWN }

/** The part of the Bluetooth stack the watcher needs; abstracted so the decision can be unit tested. */
internal interface PhoneMediaAudioLink {
    fun state(): MediaAudioLinkState
    fun release(): Boolean
    fun restore(): Boolean
}

/**
 * Keeps the phone's Bluetooth media link out of the CarPlay session.
 *
 * The CS11's own music engine pauses whatever the phone plays over its A2DP link whenever the head
 * unit's active source is not Bluetooth ("current audio is not bt, so pause bt music" ->
 * `reqAvrcpPause`). With wireless CarPlay the active source is CarPlay, so the phone was paused
 * about a second after the user pressed play in Apple Music or QQ Music. CarPlay itself only needs
 * the iAP2 link, so the media profile is released (and kept released) while a session lives.
 */
internal class PhoneMediaAudioWatcher(
    private val link: PhoneMediaAudioLink,
    private val log: (String) -> Unit,
) {
    /** Releases the media link when the phone holds it. Returns true when this tick acted. */
    fun tick(): Boolean {
        if (link.state() != MediaAudioLinkState.CONNECTED) return false
        val released = link.release()
        log("released the phone's Bluetooth media link (A2DP) released=$released")
        return released
    }

    /** Called when the session ends so the phone gets its media link back. */
    fun restore() {
        if (link.restore()) log("restored the phone's Bluetooth media link (A2DP)")
    }
}

/**
 * Runs [PhoneMediaAudioWatcher] on a background thread against the real A2DP profile.
 *
 * The iPhone may re-establish A2DP on its own, so the check is periodic rather than a one-shot.
 */
internal class PhoneMediaAudioWatchdog(private val context: Context) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var proxy: BluetoothProfile? = null
    private var device: BluetoothDevice? = null
    private var watcher: PhoneMediaAudioWatcher? = null
    private var phoneAddress: String? = null
    private var running = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.A2DP) return
            this@PhoneMediaAudioWatchdog.proxy = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) proxy = null
        }
    }

    fun start() {
        if (running) return
        val address = DiPlayPreferences.phoneAddress(context)
        val adapter = runCatching { context.systemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        if (adapter == null || address.isNullOrBlank()) {
            log("phone media watchdog not started (adapter=${adapter != null} address=${!address.isNullOrBlank()})")
            return
        }
        val remote = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (remote == null) {
            log("phone media watchdog not started (bad phone address)")
            return
        }
        val requested = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) }.getOrDefault(false)
        if (!requested) {
            log("phone media watchdog not started (A2DP profile proxy unavailable)")
            return
        }
        device = remote
        phoneAddress = address
        watcher = PhoneMediaAudioWatcher(AndroidMediaAudioLink(), ::log)
        val worker = HandlerThread("diplay-media-audio").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
        running = true
        log("phone media watchdog started for $address")
        handler?.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        watcher?.restore()
        watcher = null
        val adapter = runCatching { context.systemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        proxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        proxy = null
        device = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { watcher?.tick() }
            handler?.postDelayed(this, INTERVAL_MILLIS)
        }
    }

    private inner class AndroidMediaAudioLink : PhoneMediaAudioLink {
        private val vendor = VendorA2dpControl(context, phoneAddress, ::log)

        override fun state(): MediaAudioLinkState {
            // A2DP and HFP are released independently. Once CarPlay owns the media audio the A2DP link
            // is already gone while HFP still carries phone calls to the head unit — and that is
            // exactly when the head unit's own dialer takes a call over. Either profile being
            // connected means there is still something to release, so both count here.
            val media = vendor.isConnected()
            val handsFree = vendor.isHfpConnected()
            // Raw profile states every tick: the vendor API is the only source on this head unit, and
            // whether it reports a connection decides if the release below ever runs.
            if (media != null || handsFree != null) log("bluetooth profiles a2dp=$media hfp=$handsFree")
            if (media == true || handsFree == true) return MediaAudioLinkState.CONNECTED
            if (media == false && handsFree == false) return MediaAudioLinkState.DISCONNECTED
            val current = proxy as? BluetoothA2dp ?: return MediaAudioLinkState.UNKNOWN
            val target = device ?: return MediaAudioLinkState.UNKNOWN
            return when (runCatching { current.getConnectionState(target) }.getOrNull()) {
                BluetoothProfile.STATE_CONNECTED -> MediaAudioLinkState.CONNECTED
                BluetoothProfile.STATE_DISCONNECTED -> MediaAudioLinkState.DISCONNECTED
                else -> MediaAudioLinkState.UNKNOWN
            }
        }

        override fun release(): Boolean {
            vendor.disconnect()?.let { if (it) return true }
            // CarPlay has to own phone calls: while HFP stays connected the head unit's own dialer
            // takes the call over and CarPlay's telephony stream never carries its audio.
            if (vendor.disconnectHfp() == true) return true
            return callProfileMethod("disconnect")
        }

        override fun restore(): Boolean =
            vendor.connectHfp() == true || vendor.connect() == true || callProfileMethod("connect")
    }

    /**
     * CS11: `BluetoothA2dp.connect/disconnect` have always existed but are @hide, so they are absent
     * from the compile SDK and reached reflectively on the live profile proxy (like this project's
     * other hidden-API calls).
     */
    private fun callProfileMethod(name: String): Boolean {
        val current = proxy ?: return false
        val target = device ?: return false
        return runCatching {
            val method = current.javaClass.getMethod(name, BluetoothDevice::class.java)
            method.invoke(current, target) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "DiPlay-BtAudio"
        const val INTERVAL_MILLIS = 5_000L
    }
}

/** Convenience for the service: silence when Bluetooth is unavailable. */
internal fun PhoneMediaAudioWatchdog.startQuietly() = runCatching { start() }

/**
 * The head unit's own adapter API (`com.ecarx.xui.adaptapi`, in `/system/framework/ecarx.adapterapi.jar`)
 * hands car apps the Bluetooth A2DP profile, which is what this vendor Bluetooth stack actually
 * services — the framework's `BluetoothA2dp` proxy never reports a connection here. Reached
 * reflectively because that jar is not part of our build, and every step is logged so a failure
 * names the exact call that did not resolve.
 */
private class VendorA2dpControl(
    private val context: Context,
    private val phoneAddress: String?,
    private val log: (String) -> Unit,
) {
    private val bt: Any? = createBt()
    private val a2dp: Any? = bt?.let { owner ->
        invoke(owner, "getA2dp")?.also { log("adaptapi a2dp=${it.javaClass.name} methods=${methodNames(it).take(14)}") }
    }

    fun isConnected(): Boolean? {
        val value = a2dp?.let { invoke(it, "isA2dpConnected") ?: invoke(it, "getA2dpConnectionState") } ?: return null
        return (value as? Boolean) ?: ((value as? Int) == CONNECTION_STATE_CONNECTED)
    }

    /** True when the head unit accepted a disconnect for the phone's media profile. */
    fun disconnect(): Boolean? {
        val target = a2dp ?: return null
        val connected = invoke(target, "getA2dpConnectedAddress") as? String
        // The API takes the address to release; try the live one first, then the paired address.
        val candidates = listOfNotNull(connected?.takeIf { it.isNotBlank() }, phoneAddress?.takeIf { it.isNotBlank() })
        for (address in candidates.distinct()) {
            val result = invoke(target, "reqA2dpDisconnect", address) as? Boolean
            log("reqA2dpDisconnect($address)=$result")
            if (result == true) return true
        }
        return false
    }

    /** The hands-free profile of the same adapter API, so CarPlay can own phone calls. */
    private val hfp: Any? = bt?.let { owner ->
        invoke(owner, "getHfp")?.also { log("adaptapi hfp=${it.javaClass.name}") }
    }

    fun connect(): Boolean? = request(a2dp, "reqA2dpConnect", "getA2dpConnectedAddress")

    /** Whether the head unit still holds the phone's hands-free profile. */
    fun isHfpConnected(): Boolean? {
        val value = hfp?.let { invoke(it, "isHfpConnected") ?: invoke(it, "getHfpConnectionState") } ?: return null
        return (value as? Boolean) ?: ((value as? Int) == CONNECTION_STATE_CONNECTED)
    }

    fun connectHfp(): Boolean? = request(hfp, "reqHfpConnect", "getHfpConnectedAddress")

    fun disconnectHfp(): Boolean? = request(hfp, "reqHfpDisconnect", "getHfpConnectedAddress")

    /** Tries the live address first, then the paired one, logging every attempt's result. */
    private fun request(target: Any?, method: String, addressGetter: String): Boolean? {
        if (target == null) return null
        val connected = invoke(target, addressGetter) as? String
        val candidates = listOfNotNull(connected?.takeIf { it.isNotBlank() }, phoneAddress?.takeIf { it.isNotBlank() })
        for (address in candidates.distinct()) {
            val result = invoke(target, method, address) as? Boolean
            log("$method($address)=$result")
            if (result == true) return true
        }
        return false
    }

    private fun createBt(): Any? {
        val type = runCatching { Class.forName(BT) }.getOrNull()
        if (type == null) {
            log("bt api class not on the classpath")
            return null
        }
        log("bt api methods=${methodNames(type).take(20)}")
        val creator = type.methods.firstOrNull { it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(context) }
        val instance = creator?.let { runCatching { it.invoke(null, context) }.getOrNull() }
        log("bt instance via create(Context)=${instance != null}")
        return instance
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size &&
                it.parameterTypes.indices.all { index -> args[index] == null || it.parameterTypes[index].isInstance(args[index]) }
        } ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    private fun methodNames(target: Any): List<String> =
        target.javaClass.methods.filterNot { it.declaringClass == Any::class.java }.map { it.name }.distinct().sorted()

    private companion object {
        const val BT = "com.ecarx.xui.adaptapi.bt.Bt"
        const val CONNECTION_STATE_CONNECTED = 2
    }
}

