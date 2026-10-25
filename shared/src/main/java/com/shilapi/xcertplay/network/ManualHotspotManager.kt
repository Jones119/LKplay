package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import com.shilapi.xcertplay.compat.systemService

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val interfaces = ManualHotspotInterfaces(appContext, onDiagnostic)
    private val waitLock = Object()
    private var confirmed: HotspotSelection? = null
    private var lastSampleLog = emptyList<String>()
    private val wifiManager = appContext.systemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val apConfiguration = readApConfiguration()
        if (apConfiguration != null && apConfiguration.ssid != expectedSsid) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${apConfiguration.ssid}'",
            )
        }
        validateApConfiguration(apConfiguration)

        // 只在候选证据变化时记录，避免每 250ms 重复输出。
        val selected = ManualHotspotReadiness(
            sample = {
                interfaces.sample().also { snapshot ->
                    val messages = mutableListOf<String>()
                    selectHotspotInterface(snapshot, messages::add)
                    if (messages != lastSampleLog) {
                        messages.forEach(onDiagnostic)
                        lastSampleLog = messages
                    }
                }
            },
            cancelled = { closed || isCancelled() },
            pause = { millis -> synchronized(waitLock) { if (!closed && !isCancelled()) waitLock.wait(millis) } },
            log = {},
        ).await(timeoutMillis)
        confirmed = selected
        onDiagnostic("hotspot interface confirmed iface=${selected.name} index=${selected.index} atNs=${System.nanoTime()}")
        val network = NetworkInterface.getByName(selected.name)
        val localInterface = LocalHotspotInterface(selected.name, selected.address,
            runCatching { network?.hardwareAddress?.toMacAddressString() }.getOrNull()
                ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                ?: HotspotInterfaceBssid.read(selected.name))
        // Advertise every family the AP interface owns, as ExistingWifiManager already does. Only
        // publishing link-local IPv6 leaves many iPhones unable to discover _carplay-ctrl._tcp at
        // all: Bluetooth pairs, the phone joins the hotspot, and the hand-off then times out and
        // retries forever ("stuck on Opening CarPlay", upstream #253 / f2dab427).
        val hostAddresses = hotspotAdvertisedAddresses(
            network?.inetAddresses?.toList().orEmpty(), selected.index, localInterface.hostAddress,
        )
        val scanFrequency = frequencyFromScanResult(localInterface)
        // The AP's own driver is the most direct evidence of the channel it runs on, but read-only
        // and last: the reader is inert until the native module is built (shared/build.gradle), and
        // an unknown channel must stay unknown (never echo the requested one back to the iPhone).
        val driverReading = LegacyHotspotRadio.read(localInterface.name, bandLabelFor(expectedBand))
        val channel = observedManualHotspotChannel(
            apChannel = apConfiguration?.channel ?: 0,
            connectionFrequencyMHz = null,
            scanFrequencyMHz = scanFrequency,
            apFrequencyMHz = apConfiguration?.frequencyMHz,
            driverFrequencyMHz = driverReading.frequencyMHz,
        )
        val frequencyMHz = when {
            apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
            scanFrequency != null -> scanFrequency
            driverReading.frequencyMHz != null -> driverReading.frequencyMHz
            else -> null
        }
        val security = apConfiguration?.security ?: expectedSecurity
        val apBssid = accessPointAddress(localInterface.hardwareAddress)
        onDiagnostic("Manual hotspot configReadable=${apConfiguration != null} " +
            "security=$security channelKnown=${channel > 0} " +
            "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
            "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
            "apBssidKnown=${apBssid != null} driverFrequencyMHz=${driverReading.frequencyMHz ?: "unknown"} " +
            "driverError=${driverReading.error ?: "none"}")
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is secured but no passphrase was provided")
        }

        if (channel == 0) {
            Log.w(
                TAG,
                "Could not read the active hotspot channel from Android public APIs; " +
                    "reporting iAP2 channel 0 (auto) instead of configured channel " +
                    "$expectedChannel",
            )
        }
        val observedBandLabel = wifiBandLabel(apConfiguration?.band)
        return WirelessHotspotInfo(
            ssid = expectedSsid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = localInterface.hardwareAddress,
            interfaceName = localInterface.name,
            hostAddress = localInterface.hostAddress,
            bandLabel = when (expectedBand) {
                ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                ManualHotspotBand.GHZ_5 -> "5 GHz"
                ManualHotspotBand.AUTO ->
                    frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
            },
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
            hostAddresses = hostAddresses,
            accessPointBssid = apBssid,
        )
    }

    /**
     * The band the reservation asked for, or null when it left the choice to the firmware.
     *
     * The legacy driver reader reports a bare channel number on some drivers, which is ambiguous
     * between bands, so it is only allowed to name a channel when the reservation pinned the band.
     */
    private fun bandLabelFor(expectedBand: ManualHotspotBand): String? = when (expectedBand) {
        ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
        ManualHotspotBand.GHZ_5 -> "5 GHz"
        ManualHotspotBand.AUTO -> null
    }

    override fun validateReady() {
        val expected = confirmed ?: throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
        )
        val current = selectHotspotInterface(interfaces.sample(), onDiagnostic)
        if (closed || isCancelled() || current == null || !expected.sameAddress(current)) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Hotspot interface or address changed before publication")
        }
    }

    override fun close() {
        closed = true
        synchronized(waitLock) { waitLock.notifyAll() }
        interfaces.close()
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? = readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
    )

    private companion object {
        const val TAG = "xcertplay-usb"

    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
