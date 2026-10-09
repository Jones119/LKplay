package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

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
        // CS11: `UsbConfiguration` 整类是 API 21+，所以这里没有 4.4 变体可写——旧版那段反射
        // (`UsbConfiguration::class.java.getMethod(...)`) 在 4.4 上**永远不可能成功**（类不存在，
        // runCatching 只会得到 emptyList），是死代码兼误导，已删除。4.4 的媒体传输走内核 cdc_ncm
        // netdev（Cs11NetdevBootstrap），不再需要按配置发现 NCM（见 openNcm 的 KDoc）。
        (0 until configuration.interfaceCount).map(configuration::getInterface)

    private fun alternateSettingOf(usbInterface: UsbInterface): Int =
        // CS11: UsbInterface#getAlternateSetting 是 API21+；本文件只有 API21+ 调用点（findCdcNcm
        // 由 openNcm 在 SDK_INT >= 21 时调用），旧版的 4.4 反射兜底同样已删除。
        usbInterface.alternateSetting

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        // CS11: iPhone 的接口列表含多个 alt（alt0 无端点、alt1 才有），所以按"第一个带该方向 bulk
        // 端点"取，而不是要求唯一一个。
        val input = endpoints.firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.firstOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
