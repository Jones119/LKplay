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
import com.shilapi.xcertplay.compat.systemServiceOrNull

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
/**
 * @param onLog also receives every line this watchdog writes.
 *
 * Measured on the car on 2026-10-13: the app's `Log.i` output does not reach logcat on this ROM, so a
 * watchdog that logged only there was invisible -- the field log showed the `disableBluetooth` handler
 * running and then nothing at all from the release, which read as "never called" when the release had in
 * fact run and answered false. The caller passes its own diagnostic sink (CarPlayHostActivity passes
 * `appendLog`) so these lines land in `/sdcard/DiPlay/logs/`.
 */
internal class PhoneMediaAudioWatchdog(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {
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
        if (running) {
            // Said out loud because "already running" and "never asked" looked identical in the field log.
            log("phone media watchdog already running for $phoneAddress")
            return
        }
        val adapter = runCatching { context.systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        // The stored address is the one the pairing flow saved, and it is only written there -- so on a
        // wired session it can still be missing and this watchdog used to log
        // `phone media watchdog not started (adapter=true address=false)` and never release anything
        // (measured on the car on 2026-10-13, while the head unit paused every track a second in).
        // The reference asks its Bluetooth backend instead (`connectedAddress(profile)` / `isBonded` in
        // LegacyVendorBluetoothHandoff), so ask the stack the same question here: whichever bonded device
        // is an iPhone is the one CarPlay means. This also keeps the release independent of the AirPlay
        // session having delivered `/info` at all.
        // The Bluetooth key first, then the legacy field only when it really holds a MAC: on the car,
        // `phone_address` held an IP and cost every release attempt (2026-10-13 18:41).
        val stored = DiPlayPreferences.phoneBluetoothAddress(context)
            ?: validPhoneAddress(DiPlayPreferences.phoneAddress(context))
        val address = stored?.takeIf { it.isNotBlank() } ?: bondedIphoneAddress(adapter)
        if (adapter == null || address.isNullOrBlank()) {
            log("phone media watchdog not started (adapter=${adapter != null} address=${!address.isNullOrBlank()})")
            return
        }
        if (address != stored) {
            log("phone media watchdog using the bonded iPhone $address (stored=$stored)")
            runCatching { DiPlayPreferences.savePhone(context, address, DiPlayPreferences.phoneName(context)) }
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
        val adapter = runCatching { context.systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
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

    internal fun log(message: String) {
        Log.i(TAG, message)
        // The file sink is the one that survives on this ROM; a failure here must never cost the release.
        runCatching { onLog(message) }
    }

    private companion object {
        const val TAG = "DiPlay-BtAudio"
        const val INTERVAL_MILLIS = 5_000L
    }
}

/** Convenience for the service: silence when Bluetooth is unavailable. */
/**
 * The bonded device that is an iPhone: the phone this head unit pairs with.
 *
 * The pairing flow stores the address it saw, but a wired session can start without that ever having
 * been written (see [PhoneMediaAudioWatchdog.start]). The reference reads the bonded/connected list
 * instead, which is the same question asked of the stack. A wrong answer costs nothing here: the
 * watcher only releases a link whose own profile state says it is connected.
 */
private fun bondedIphoneAddress(adapter: android.bluetooth.BluetoothAdapter?): String? =
    runCatching {
        adapter?.bondedDevices?.firstOrNull { device ->
            val name = device?.name ?: return@firstOrNull false
            name.contains("iPhone", ignoreCase = true) || name.contains("iPad", ignoreCase = true)
        }?.address
    }.getOrNull()

/**
 * Starts the watchdog, and never silently.
 *
 * Measured on the car on 2026-10-13: `CarPlay asked for Bluetooth off; releasing the phone's A2DP link`
 * reached the diagnostic log and the watchdog itself then produced no line at all, in logcat or in the
 * private log -- because the previous body was a bare `runCatching { start() }`. A release that fails in
 * silence leaves the driver with music that stops after a second and no reason in the log, which is
 * exactly the report this whole path exists to answer, so a failure now names itself.
 */
/**
 * A Bluetooth MAC as the reference validates it (`CarPlayController.BLUETOOTH_ADDRESS`).
 *
 * Measured on the car on 2026-10-13 18:35: the watchdog logged `not started (bad phone address)`, so the
 * stored preference had been handed to `getRemoteDevice` and rejected. Whatever the stored value came
 * from, a release must not be lost to it: an address that is not a MAC is not an address, and the bonded
 * iPhone below is the answer the reference asks its backend for anyway.
 */
internal val BLUETOOTH_MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

/** [address] when it really is a MAC, null otherwise. */
private fun validPhoneAddress(address: String?): String? =
    address?.trim()?.takeIf { BLUETOOTH_MAC.matches(it) }

internal fun PhoneMediaAudioWatchdog.startQuietly() {
    runCatching { start() }.onFailure { error ->
        log("phone media watchdog failed to start: ${error.javaClass.name}: ${error.message}")
    }
}

/**
 * Releases the phone's A2DP link once, without starting the watchdog.
 *
 * Split out so CarPlay's `disableBluetooth` can act immediately and independently of the watchdog's own
 * startup: on 2026-10-13 the handler's line reached the diagnostic log while the watchdog produced no
 * line at all, in either sink, so the command's effect must not depend on that path having gone right.
 * The address follows the same rule as the watchdog -- the stored one, else the bonded iPhone -- and
 * every step is logged to logcat under [DiPlay-BtAudio] so a failure names the call that did not resolve.
 */
internal fun releasePhoneA2dp(context: Context, onLog: (String) -> Unit = {}): Boolean {
    fun say(message: String) {
        Log.i(A2DP_LOG_TAG, message)
        runCatching { onLog(message) }
    }

    return runCatching {
        val adapter = context.systemServiceOrNull(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        // The Bluetooth key first, then the legacy field only when it really holds a MAC: on the car,
        // `phone_address` held an IP and cost every release attempt (2026-10-13 18:41).
        val stored = DiPlayPreferences.phoneBluetoothAddress(context)
            ?: validPhoneAddress(DiPlayPreferences.phoneAddress(context))?.takeIf { it.isNotBlank() }
        val address = stored ?: bondedIphoneAddress(adapter)
        if (address.isNullOrBlank()) {
            say("a2dp release skipped: no phone address (stored=${!stored.isNullOrBlank()})")
            return@runCatching false
        }
        val vendor = VendorA2dpControl(context, address) { message -> say(message) }
        val connected = runCatching { vendor.isConnected() }.getOrNull()
        val hfpConnected = runCatching { vendor.isHfpConnected() }.getOrNull()
        val released = runCatching { vendor.disconnect() }.getOrNull()
        say(
            "a2dp release address=$address source=${if (stored != null) "stored" else "bonded"} " +
                "connected=$connected hfp=$hfpConnected released=$released",
        )
        runCatching { DiPlayPreferences.savePhone(context, address, DiPlayPreferences.phoneName(context)) }
        released == true
    }.getOrElse { error ->
        say("a2dp release failed: ${error.javaClass.name}: ${error.message}")
        false
    }
}

/** The logcat tag the A2DP release uses, shared with [PhoneMediaAudioWatchdog]'s own lines. */
internal const val A2DP_LOG_TAG = "DiPlay-BtAudio"

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
    fun disconnect(): Boolean? = request(a2dp, "reqA2dpDisconnect", "getA2dpConnectedAddress", disconnecting = true)

    /** The hands-free profile of the same adapter API, so CarPlay can own phone calls. */
    private val hfp: Any? = bt?.let { owner ->
        invoke(owner, "getHfp")?.also { log("adaptapi hfp=${it.javaClass.name}") }
    }

    fun connect(): Boolean? = request(a2dp, "reqA2dpConnect", "getA2dpConnectedAddress", disconnecting = false)

    /** Whether the head unit still holds the phone's hands-free profile. */
    fun isHfpConnected(): Boolean? {
        val value = hfp?.let { invoke(it, "isHfpConnected") ?: invoke(it, "getHfpConnectionState") } ?: return null
        return (value as? Boolean) ?: ((value as? Int) == CONNECTION_STATE_CONNECTED)
    }

    fun connectHfp(): Boolean? = request(hfp, "reqHfpConnect", "getHfpConnectedAddress", disconnecting = false)

    fun disconnectHfp(): Boolean? = request(hfp, "reqHfpDisconnect", "getHfpConnectedAddress", disconnecting = true)

    /**
     * Tries the live address first, then the paired one, logging every attempt's result.
     *
     * When releasing, the vendor's connected address must be *our* phone: this head unit can hold
     * another paired device, and disconnecting whatever it reports would drop that device instead
     * (EasyPlay guards its handoff the same way, docs/_digest/37). Connecting ignores that check,
     * because restoring our own link is always the intent.
     */
    private fun request(target: Any?, method: String, addressGetter: String, disconnecting: Boolean): Boolean? {
        if (target == null) return null
        val connected = invoke(target, addressGetter) as? String
        val paired = phoneAddress?.takeIf { it.isNotBlank() }
        val candidates = when {
            connected.isNullOrBlank() -> listOfNotNull(paired)
            paired == null || connected.equals(paired, ignoreCase = true) -> listOf(connected)
            disconnecting -> {
                log("$method skipped: connected device is not the selected phone")
                return false
            }
            else -> listOf(paired)
        }
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

