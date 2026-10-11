package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 系统服务缺失时的**行为**（不只是类型）：这三处守卫在改用 `systemServiceOrNull` 之前是死代码
 * ——`systemService` 的非空签名会在守卫之前抛异常，于是"WifiManager 不可用"的提示与降级返回
 * 永远不会发生（见 doc 34）。本测试把"缺失时确实走到降级路径"钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ServiceAbsenceGuardsTest {
    private class ContextWithoutWifi(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WIFI_SERVICE) null else super.getSystemService(name)
    }

    private val context get() = ContextWithoutWifi(RuntimeEnvironment.getApplication())

    @Test fun legacySoftApDegradesInsteadOfFailingWhenWifiManagerIsAbsent() {
        val logs = mutableListOf<String>()
        assertFalse(LegacySoftApControl.requestEnable(context) { logs += it })
        assertTrue(logs.any { it.contains("WifiManager unavailable") })
        // Both readers must answer "unknown" rather than throw.
        assertNull(LegacySoftApControl.readStoredConfiguration(context))
        assertNull(LegacySoftApControl.apState(context))
    }
}
