package com.shilapi.xcertplay.compat

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbManager
import android.media.AudioManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 系统服务缺失时的契约：可空访问器必须如实返回 null（否则 `?:`/`?.` 守卫是死代码），
 * 非空访问器则必须拒绝"静默返回 null"。
 *
 * ⚠️ 只覆盖 API≥23 分支。API<23（车机 API 19 走的那条）需要 `android-all` 的 21/22 包，
 * 本机离线缓存里没有（只有 API 25+），因此那条分支只能在车机上验证（见 doc 34）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Cs11CompatSystemServiceTest {
    private class NoUsbContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.USB_SERVICE) null else super.getSystemService(name)
    }

    private val context get() = NoUsbContext(RuntimeEnvironment.getApplication())

    @Test fun nullableAccessorReportsAMissingServiceAsNull() {
        assertNull(context.systemServiceOrNull(UsbManager::class.java))
        assertNotNull(context.systemServiceOrNull(AudioManager::class.java))
    }

    @Test fun requiredAccessorRefusesAMissingServiceInsteadOfReturningNull() {
        try {
            context.systemService(UsbManager::class.java)
            fail("A required system service was absent but the accessor returned anyway")
        } catch (_: Throwable) {
            // NullPointerException on API>=23, IllegalStateException below it: both are "not a silent null".
        }
    }
}
