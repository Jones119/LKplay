package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import com.shilapi.xcertplay.compat.systemService

internal class ManualHotspotInterfaces(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val connectivity = context.systemService(ConnectivityManager::class.java)
    private var lastLegacyDiagnostic: String? = null

    fun sample(): HotspotNetworkSnapshot {
        val ap = legacyApInterfaces()
        // CS11/4.4 has neither getActiveNetwork (API 23) nor allNetworks (API 21), so the default
        // network and the Wi-Fi upstream set are unobservable; an unobservable sample must not look
        // like a changing network.
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { iface ->
                runCatching {
                    if (iface.isLoopback) null else HotspotInterfaceSnapshot(
                        iface.name, iface.index, iface.isUp, Collections.list(iface.inetAddresses),
                        wirelessInterfaceName(iface.name) || File("/sys/class/net/${iface.name}/wireless").isDirectory,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return HotspotNetworkSnapshot(
            interfaces, ap, null, null,
            consistent = true,
            apEnabled = CarHotspotStatus.isEnabled(context),
            upstreamObservable = false,
        )
    }

    // 旧平台只使用允许读取的结果；接口归属读不到时保持 unknown，不放宽普通网卡资格。
    @SuppressLint("PrivateApi")
    private fun legacyApInterfaces(): Set<String>? = runCatching {
        val manager = checkNotNull(connectivity)
        val tethered = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            .invoke(manager) as Array<*>
        val regexes = ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs")
            .invoke(manager) as Array<*>
        val names = tethered.filterIsInstance<String>()
        val patterns = regexes.filterIsInstance<String>()
        val ap = legacyHotspotInterfaces(names, patterns)
        val diagnostic = "legacy hotspot ownership=${if (ap == null) "unobservable" else "observed"} " +
            "tethered=$names wifiRegexes=$patterns matched=$ap"
        if (diagnostic != lastLegacyDiagnostic) {
            lastLegacyDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        ap
    }.getOrNull()

    override fun close() = Unit
}

internal fun legacyHotspotInterfaces(tethered: List<String>, wifiRegexes: List<String>): Set<String>? {
    if (wifiRegexes.isEmpty()) return null
    val patterns = wifiRegexes.map(::Regex)
    val matched = tethered.filter { name -> patterns.any { it.matches(name) } }.toSet()
    // 厂商可能动态使用 wlan0，而静态配置仍写 softap0；匹配失败不能证明没有热点。
    return matched.takeUnless { tethered.isNotEmpty() && it.isEmpty() }
}

internal fun wirelessInterfaceName(name: String): Boolean =
    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") ||
        name.contains("softap", ignoreCase = true) || name.startsWith("wifi") || name.startsWith("p2p")

/**
 * CS11（Android 4.4 / API 19）没有 `ConnectivityManager.getActiveNetwork`（API 23）：默认网络
 * 在旧平台上根本不可观测。此时若把"读不到"判成"网络在变化"，热点采样会被永久拒绝、无线连接
 * 永远起不来，所以不可观测的采样一律视为一致；可观测时仍要求前后两次采样相同。
 */
internal fun defaultNetworkSampleConsistent(observable: Boolean, before: Result<*>?, after: Result<*>?): Boolean =
    !observable || before == null || after == null ||
        (before.isSuccess && after.isSuccess && before.getOrNull() == after.getOrNull())
