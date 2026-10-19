package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * CS11: 版本隔离持有者——`android.hardware.usb.UsbConfiguration` 整类都是 **API 21** 才有的
 * （lint 的 API 库记 `since="21"`，`UsbDevice#getConfigurationCount/getConfiguration` 同样是 21），
 * 其成员 `getInterfaceCount/getInterface/getId` 也随之是 21。按本项目约定
 * （`docs/_digest/08 §4.3`、`docs/_digest/17 §2` 第 3 条），引用必须集中到"只在 21+ 上被加载的类"。
 *
 * 现状说明：[IphoneCarPlayConfiguration.find] 在 `< 21` 时**先 early return null**（`IphoneUsbHost`
 * 里对应的调用点也都在 `SDK_INT >= 21` 块内），所以 4.4 上这些方法体不会被执行；本类把这层保证从
 * "调用者记得守卫"升级为"类型只在 21+ 被加载"，也顺手消掉 lint 的 8 条 `NewApi`。
 */
internal object Api21Configuration {
    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d

    /**
     * Only called on API 21+: every `UsbConfiguration` method below is 21+, and the class itself does
     * not exist on 4.4, so a missed guard here is a `NoClassDefFoundError` rather than a wrong result.
     */
    @RequiresApi(21)
    fun find(device: UsbDevice): UsbConfiguration? {
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val chosen = configurations.firstOrNull {
            usbMuxInterfaceOf(it) != null && hasCdcNcm(it) && hasAppleEthernet(it)
        } ?: configurations.firstOrNull { usbMuxInterfaceOf(it) != null && hasCdcNcm(it) }
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "carplay config chosen=${chosen?.id} " +
                "available=${configurations.map { it.id }} detail=${chosen?.let(::describe)}",
        )
        return chosen
    }

    /**
     * Switches the device to configuration [id] - used by the wired teardown to move the iPhone off
     * the CarPlay configuration so the next connect re-selects it. Returns false when that
     * configuration is not exposed or the kernel refused the switch. API 19 uses
     * [LegacyUsbNative.setConfiguration] instead.
     */
    @RequiresApi(21)
    fun resetTo(connection: android.hardware.usb.UsbDeviceConnection, device: UsbDevice, id: Int): Boolean {
        val target = (0 until device.configurationCount)
            .map(device::getConfiguration)
            .firstOrNull { it.id == id } ?: return false
        return connection.setConfiguration(target)
    }

    @RequiresApi(21)
    fun describe(configuration: UsbConfiguration): String =
        (0 until configuration.interfaceCount).joinToString(",") { index ->
            val usbInterface = configuration.getInterface(index)
            "${usbInterface.id}/${usbInterface.alternateSetting}" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    @RequiresApi(21)
    fun usbMuxInterfaceOf(configuration: UsbConfiguration): UsbInterface? =
        (0 until configuration.interfaceCount).map(configuration::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    @RequiresApi(21)
    private fun hasCdcNcm(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    @RequiresApi(21)
    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
