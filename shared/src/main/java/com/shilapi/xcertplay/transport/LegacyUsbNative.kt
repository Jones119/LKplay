package com.shilapi.xcertplay.transport

/**
 * The one native piece the API-19 userspace NCM bridge needs: selecting an interface's alternate
 * setting.
 *
 * `UsbDeviceConnection.setInterface()` arrived in API 21, and on this ROM the kernel only updates an
 * interface's altsetting through the usbfs ioctl, so neither the platform API nor
 * `controlTransfer(SET_INTERFACE)` is enough (docs/_digest/58 §5 item 4; the legacy EasyPlay build
 * makes the same native call). Everything else in the bridge stays Java/Kotlin.
 *
 * The library is built by `src/main/jni/Android.mk` (`legacy_usb`), which the shared module enables
 * once an NDK is installed. [available] is false on any build without it, and every caller must
 * treat that as "the userspace bridge is not usable here" rather than failing the wired path - the
 * kernel netdev transport remains the fallback.
 */
internal object LegacyUsbNative {
    /** Load outcome, kept as a value so a missing library can never crash a caller. */
    private val loaded: Boolean = runCatching { System.loadLibrary("legacy_usb") }.isSuccess

    val available: Boolean get() = loaded

    /** 0 on success, a negative errno otherwise. */
    external fun setInterface(fileDescriptor: Int, interfaceNumber: Int, alternateSetting: Int): Int

    /** Self-report so the car log proves which library build is loaded. */
    external fun version(): String
}
