package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import java.io.IOException

/**
 * Attaches to an existing station network on platforms that expose the modern ConnectivityManager
 * API. CS11 / Android 4.4 does not, so [start] reports that this backend is unavailable.
 */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        throw IOException("Existing Wi-Fi attachment requires Android 5.0+; wireless CarPlay is unavailable on this Android version")
    }

    override fun connectionDiagnosticSnapshot(): String = "existingWifiLink=unavailable"

    override fun close() = Unit
}
