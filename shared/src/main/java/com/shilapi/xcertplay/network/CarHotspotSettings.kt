package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object CarHotspotSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_enable", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_enable", enabled).apply()

    // Visibility is independent of the saved choice: ADB is only needed to grant permission.
    // A firmware whose soft AP DiPlay can start itself (CS11/4.4) needs no ADB at all, so it is
    // visible regardless of access; otherwise the ADB-granted permissions decide.
    fun visible(access: LocalAdb.Access, legacySupported: Boolean = false): Boolean =
        legacySupported || access == LocalAdb.Access.READY || access == LocalAdb.Access.NOT_APPROVED

    fun shouldEnable(context: Context, wireless: Boolean, mode: WirelessHotspotMode): Boolean =
        enabled(context) && wireless && mode == WirelessHotspotMode.MANUAL
}
