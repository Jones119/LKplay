package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice

/**
 * Suppresses USB permission prompts that Android would otherwise repeat for one attachment.
 *
 * `UsbManager.requestPermission` shows a system dialog per call, and the wired iPhone path can
 * call it again for the same attachment: re-enumeration delivers `ACTION_USB_DEVICE_ATTACHED`
 * for a device that is already being prompted (CarPlayController.onIphoneAttached ->
 * requestIphonePermission -> IphoneUsbHost.requestPermission), and after the user refuses, our
 * re-enumeration/attach path would ask again immediately. EasyPlay v71 solved the same problem
 * with its per-attach dedupe (`IphoneUsbPermissionCoordinator.observe`, `:222-283`); this is the
 * small part of that coordinator we can prove we need.
 *
 * State is keyed by the USB attachment, not by the `UsbDevice` object: the same iPhone can be
 * re-enumerated into a fresh object, and a new object for the same attachment must not re-prompt.
 * [forgetDetached] drops the state as soon as the attachment leaves the bus, so unplugging and
 * replugging the phone prompts again.
 */
internal class UsbPermissionGate {
    private enum class State { PENDING, DENIED }

    private val state = LinkedHashMap<String, State>()

    /** True when this attachment needs a system prompt; false when one is pending or was refused. */
    fun shouldRequest(device: UsbDevice): Boolean = when (state[attachmentKey(device)]) {
        State.PENDING -> false
        // The user refused this plug-in: they get a diagnostic instead of the same dialog again
        // until the phone is re-attached (EasyPlay surfaces it after a timeout for the same reason).
        State.DENIED -> false
        null -> true
    }

    fun requested(device: UsbDevice) {
        state[attachmentKey(device)] = State.PENDING
    }

    /** Accepts the result, so a later grant through another path can prompt again. */
    fun resolved(device: UsbDevice) {
        state.remove(attachmentKey(device))
    }

    fun denied(device: UsbDevice) {
        state[attachmentKey(device)] = State.DENIED
    }

    /** Drops every attachment that is no longer on the bus, including devices granted elsewhere. */
    fun forgetDetached(attached: Collection<String>) {
        state.keys.retainAll { key -> key in attached }
    }

    companion object {
        fun attachmentKey(device: UsbDevice): String = device.deviceName ?: ""
    }
}
