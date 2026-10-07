package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CS11（Android 4.4 / API 19）没有 ConnectivityManager.getActiveNetwork（API 23）：
 * 默认网络在旧平台上不可观测，若把"读不到"当成"网络在变化"，热点采样会被永久拒绝、
 * 无线热点永远准备不好；现代平台仍按前后一致性判断。
 */
class ManualHotspotInterfacesTest {
    private val wlan: Result<String> = Result.success("wlan0")
    private val sameAgain: Result<String> = Result.success("wlan0")
    private val other: Result<String> = Result.success("eth0")
    private val unreadable: Result<String> = Result.failure(IllegalStateException("no getActiveNetwork"))

    @Test fun unobservableDefaultNetworkIsNotReportedAsAChange() {
        assertTrue(defaultNetworkSampleConsistent(observable = false, before = null, after = null))
    }

    @Test fun observableDefaultNetworkStillRequiresAnUnchangedSample() {
        assertTrue(defaultNetworkSampleConsistent(observable = true, before = wlan, after = sameAgain))
        assertFalse(defaultNetworkSampleConsistent(observable = true, before = wlan, after = other))
        assertFalse(defaultNetworkSampleConsistent(observable = true, before = wlan, after = unreadable))
        assertFalse(defaultNetworkSampleConsistent(observable = true, before = unreadable, after = wlan))
    }
}
