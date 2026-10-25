package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the active configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 * API 19 has no `UsbConfiguration`, so discovery only reads the active configuration.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    /**
     * CS11: API19 没有 UsbConfiguration——直接检查"当前活动配置"是否已含 iPhone 的 USBMUX 接口。
     * iPhone 收到 CarPlay 配置请求后自行切到含 USBMUX+NCM 的配置；NCM 由内核 cdc_ncm 接管。
     */
    fun activeConfigCarPlayReady(device: UsbDevice): Boolean {
        // 同时要求 USBMUX 与 NCM：config 4 里也有 USBMUX（iAP2），只看它会把"非 CarPlay 配置"误判成就绪
        var hasMux = false
        var hasNcm = false
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == USBMUX_CLASS &&
                usbInterface.interfaceSubclass == USBMUX_SUBCLASS &&
                usbInterface.interfaceProtocol == USBMUX_PROTOCOL
            ) {
                hasMux = true
            }
            if (usbInterface.interfaceClass == NCM_CONTROL_CLASS &&
                usbInterface.interfaceSubclass == NCM_CONTROL_SUBCLASS
            ) {
                hasNcm = true
            }
        }
        return hasMux && hasNcm
    }

    /** CS11: 从活动配置接口列表直取 USBMUX 接口（UsbDevice#getInterface 是 API12）。 */
    fun activeUsbMuxInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }
}
