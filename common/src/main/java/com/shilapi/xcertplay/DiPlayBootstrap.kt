package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest
import com.shilapi.xcertplay.compat.noBackupFilesDirCompat

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    /**
     * The receiver name the iPhone lists under 通用 → CarPlay → 我的汽车.
     *
     * It is the AirPlay `deviceName`, so the home page shows the same string instead of a guess. One
     * constant, one place to change it.
     */
    const val CARPLAY_NAME = "Lynk&Co"

    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val target = File(context.noBackupFilesDirCompat(), LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDirCompat(), "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)

    /**
     * The phone's Bluetooth MAC, kept apart from [phoneAddress].
     *
     * Measured on the car on 2026-10-13 18:41: the A2DP release was handed an *IP* out of `phone_address`
     * (the redactor labelled it `[ip]`), so `getRemoteDevice` answered `bad phone address` and every
     * release attempt was lost. That field belongs to the wireless flow's own addressing, so the
     * Bluetooth address gets its own key and only ever holds something that is really a MAC.
     */
    fun phoneBluetoothAddress(context: Context): String? =
        prefs(context).getString("phone_bt_address", null)

    fun savePhoneBluetoothAddress(context: Context, address: String) {
        if (!BLUETOOTH_MAC.matches(address.trim())) return
        prefs(context).edit().putString("phone_bt_address", address.trim()).apply()
    }

    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }

    /**
     * What actually connected last time, kept apart from the *selected* phone above.
     *
     * The selection answers "which phone should this app dial"; this answers "what happened last time",
     * which is what the home page's summary card reports. Written when a session starts.
     */
    fun lastPhoneName(context: Context): String? = prefs(context).getString("last_phone_name", null)

    /** Null until a session has started once; true = wireless, false = USB. */
    fun lastWireless(context: Context): Boolean? =
        if (prefs(context).contains("last_wireless")) prefs(context).getBoolean("last_wireless", false) else null

    fun saveLastConnection(context: Context, phoneName: String, wireless: Boolean) {
        prefs(context).edit()
            .putString("last_phone_name", phoneName)
            .putBoolean("last_wireless", wireless)
            .apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
    // Open DiPlay when the selected iPhone reconnects over Bluetooth (upstream fe1a59d). Opt-in.
    fun connectOnPhoneBluetooth(context: Context) =
        prefs(context).getBoolean("connect_on_phone_bluetooth", false)
    fun saveConnectOnPhoneBluetooth(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("connect_on_phone_bluetooth", value).apply()
    }

    /**
     * Tapping DiPlay while CarPlay is already running comes straight back to the CarPlay screen instead of
     * the app's own home page (docs/_digest/84). **On by default**: that is what the driver expects, and it
     * keeps the host activity's video surface from being torn down and rebuilt for nothing.
     *
     * Only the *already connected* case is governed here. What happens with no session - showing the home
     * page, and whether to dial out by itself - stays with [autoConnect] and [connectOnPhoneBluetooth], so
     * the three switches cannot contradict each other.
     */
    fun resumeCarPlayOnOpen(context: Context) =
        prefs(context).getBoolean("resume_carplay_on_open", true)
    fun saveResumeCarPlayOnOpen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("resume_carplay_on_open", value).apply()
    }
}


