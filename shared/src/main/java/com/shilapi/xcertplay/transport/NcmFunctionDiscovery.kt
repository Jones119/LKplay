package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    private fun findCdcNcm(configuration: UsbConfiguration): NcmFunction? {
        val control = interfaces(configuration).firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces(configuration)
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (alternateSettingOf(it) == DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun interfaces(configuration: UsbConfiguration): List<UsbInterface> =
        // CS11: getInterfaceCount/getInterface 是 API21+；4.4 走反射探测，缺失则空列表（NCM 发现降级）
        if (Build.VERSION.SDK_INT >= 21) {
            (0 until configuration.interfaceCount).map(configuration::getInterface)
        } else {
            runCatching {
                val count = UsbConfiguration::class.java.getMethod("getInterfaceCount").invoke(configuration) as Int
                val getInterface = UsbConfiguration::class.java.getMethod("getInterface", Int::class.javaPrimitiveType)
                (0 until count).map { getInterface.invoke(configuration, it) as UsbInterface }
            }.getOrDefault(emptyList())
        }

    private fun alternateSettingOf(usbInterface: UsbInterface): Int =
        // CS11: UsbInterface.getAlternateSetting 是 API21+；4.4 走反射探测，缺失按 0 处理
        if (Build.VERSION.SDK_INT >= 21) usbInterface.alternateSetting
        else runCatching {
            UsbInterface::class.java.getMethod("getAlternateSetting").invoke(usbInterface) as Int
        }.getOrDefault(0)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
