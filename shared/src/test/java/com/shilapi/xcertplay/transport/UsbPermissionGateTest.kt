package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The gate exists because `UsbManager.requestPermission` shows one system dialog per call, and our
 * wired path can call it twice for one attachment (re-enumeration attach + the 500 ms poll fallback)
 * and again right after the user refuses. Doc 42 C5 is the reference implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UsbPermissionGateTest {
    /**
     * UsbDevice has no public constructor in the SDK stub, so the tests build one reflectively.
     * API 28's order is name, vendorId, productId, class, subclass, protocol, manufacturer,
     * product, version, serial (UsbDevice.java:71-80).
     */
    private fun device(name: String = "/dev/bus/usb/001/002"): UsbDevice =
        ReflectionHelpers.callConstructor(
            UsbDevice::class.java,
            ReflectionHelpers.ClassParameter.from(String::class.java, name),
            ReflectionHelpers.ClassParameter.from(Int::class.java, 0x05ac),
            ReflectionHelpers.ClassParameter.from(Int::class.java, 0x12a8),
            ReflectionHelpers.ClassParameter.from(Int::class.java, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.java, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.java, 0),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(String::class.java, "-"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
        )

    @Test fun aFreshAttachmentIsPromptedOnce() {
        val gate = UsbPermissionGate()
        assertTrue(gate.shouldRequest(device()))
        gate.requested(device())
        // Re-enumeration hands us a new UsbDevice object for the same attachment.
        assertFalse(gate.shouldRequest(device()))
    }

    @Test fun aRefusedAttachmentIsNotPromptedAgainUntilItIsReAttached() {
        val gate = UsbPermissionGate()
        gate.requested(device())
        gate.denied(device())
        assertFalse(gate.shouldRequest(device()))
        gate.forgetDetached(listOf(UsbPermissionGate.attachmentKey(device())))
        assertFalse(gate.shouldRequest(device()))
        gate.forgetDetached(emptyList())
        assertTrue(gate.shouldRequest(device()))
    }

    @Test fun anotherAttachmentIsNeverSuppressed() {
        val gate = UsbPermissionGate()
        gate.requested(device())
        assertTrue(gate.shouldRequest(device("/dev/bus/usb/001/003")))
    }

    @Test fun aGrantedAttachmentIsPromptedAgainWhenItComesBackWithoutPermission() {
        val gate = UsbPermissionGate()
        gate.requested(device())
        gate.resolved(device())
        assertTrue(gate.shouldRequest(device()))
    }

    @Test fun onlyAttachmentsStillOnTheBusKeepTheirState() {
        val gate = UsbPermissionGate()
        val first = device("/dev/bus/usb/001/002")
        val second = device("/dev/bus/usb/001/003")
        gate.requested(first)
        gate.requested(second)
        gate.forgetDetached(listOf(UsbPermissionGate.attachmentKey(second)))
        assertTrue(gate.shouldRequest(first))
        assertFalse(gate.shouldRequest(second))
    }
}
