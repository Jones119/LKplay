package com.shilapi.xcertplay.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** On API 19 only a broadcast permission can keep another app from forging the USB result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbPermissionBroadcastTest {
    @Test fun thePermissionBelongsToThisPackage() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "${context.packageName}.USB_PERMISSION_RESULT",
            UsbPermissionBroadcast.senderPermission(context),
        )
    }

    @Test fun registrationIsAcceptedAndCanBeUndone() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        UsbPermissionBroadcast.register(context, receiver, IntentFilter("com.shilapi.test.ACTION"))
        context.unregisterReceiver(receiver)
    }
}
