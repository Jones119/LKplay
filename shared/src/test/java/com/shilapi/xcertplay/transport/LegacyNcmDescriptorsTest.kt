package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for [LegacyNcmDescriptors] — the API 19 port of EasyPlay v74's
 * `LegacyUsbConfiguration.ncm()`/`parse()` (`_analysis/easyplay/src/sources/com/shilapi/xcertplay/
 * transport/LegacyUsbConfiguration.java:574-949`).
 *
 * Every byte array below is built here, little-endian, from the USB 2.0 descriptor layout; no device
 * is involved. The happy path mirrors the real iPhone CarPlay function set (vendor 0x05AC, product
 * 0x12A8, configuration 6, CDC-NCM control interface 3, data interface 4 idle at alternate 0 and
 * active at alternate 1). The USBMUX interface (1) is part of that shape **and** is required by the
 * reference semantics: `ncm()` only selects a configuration whose USBMUX function exists *and* has a
 * bulk pair (`usbMux` :393, guard :595).
 *
 * The failure cases assert the reference's message strings verbatim, because the port is meant to
 * fail identically to the decompiled original.
 */
class LegacyNcmDescriptorsTest {

    // ------------------------------------------------------------------ happy path

    @Test
    fun `parses the iPhone configuration into its four interfaces`() {
        val configurations = LegacyNcmDescriptors.parse(iphoneRaw())

        assertEquals(1, configurations.size)
        val configuration = configurations.single()
        assertEquals(6, configuration.id)
        assertEquals(listOf(1, 3, 4, 4), configuration.interfaces.map { it.id })
        assertEquals(listOf(0, 0, 0, 1), configuration.interfaces.map { it.alternate })
        assertEquals(listOf(0xFF, 0x02, 0x0A, 0x0A), configuration.interfaces.map { it.deviceClass })
        assertEquals(listOf(0xFE, 0x0D, 0x00, 0x00), configuration.interfaces.map { it.subclass })
        assertEquals(listOf(0x02, 0x01, 0x00, 0x00), configuration.interfaces.map { it.protocol })
        assertEquals(listOf(2, 1, 0, 2), configuration.interfaces.map { it.endpoints.size })
    }

    @Test
    fun `resolves the NCM layout and its CDC functional fields`() {
        val layout = LegacyNcmDescriptors.ncm(iphoneRaw(), configurationId = 6)

        assertEquals(3, layout.control.id)
        assertEquals(0, layout.control.alternate)
        assertTrue(layout.control.isCdcNcmControl)
        assertFalse(layout.control.isUsbMux)
        assertFalse(layout.control.hasBulkPair)
        assertEquals(listOf(0x85), layout.control.endpoints.map { it.address })
        assertEquals(listOf(0x03), layout.control.endpoints.map { it.attributes })
        assertEquals(listOf(16), layout.control.endpoints.map { it.maxPacketSize })
        assertEquals(listOf(8), layout.control.endpoints.map { it.interval })
        assertTrue(layout.control.endpoints.single().isInterrupt)
        assertTrue(layout.control.endpoints.single().isIn)

        assertEquals(4, layout.data.id)
        assertEquals(1, layout.data.alternate)
        assertTrue(layout.data.hasBulkPair)
        assertEquals(listOf(0x04, 0x86), layout.data.endpoints.map { it.address })
        assertEquals(listOf(512, 512), layout.data.endpoints.map { it.maxPacketSize })
        assertEquals(listOf(0, 0), layout.data.endpoints.map { it.interval })
        assertFalse(layout.data.endpoints[0].isIn)
        assertTrue(layout.data.endpoints[1].isIn)
        assertTrue(layout.data.endpoints.all { it.isBulk })

        assertEquals(4, layout.idle.id)
        assertEquals(0, layout.idle.alternate)
        assertTrue(layout.idle.endpoints.isEmpty())

        assertEquals(7, layout.iMacAddressIndex)
        assertEquals(1514, layout.maxSegmentSize)
        assertEquals(0, layout.capabilities)
    }

    @Test
    fun `reads the raw Ethernet and NCM functional descriptor fields`() {
        val layout = LegacyNcmDescriptors.ncm(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ethernetFunctional(iMacAddress = 0x0B, maxSegmentSize = 0x1234),
                            ncmFunctional(capabilities = 0x1F),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            configurationId = 6,
        )

        assertEquals(0x0B, layout.iMacAddressIndex)
        assertEquals(0x1234, layout.maxSegmentSize)
        assertEquals(0x1F, layout.capabilities)
    }

    @Test
    fun `keeps the control class descriptors verbatim`() {
        val control = LegacyNcmDescriptors.parse(iphoneRaw()).single().interfaces[1]

        assertEquals(listOf(5, 13, 6), control.classDescriptors.map { it.size })
        assertEquals(
            listOf(5, 0x24, 6, 3, 4),
            control.classDescriptors[0].map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(13, 0x24, 15, 7, 0, 0, 0, 0, 0xEA, 0x05, 0, 0, 0),
            control.classDescriptors[1].map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(6, 0x24, 26, 0, 0x10, 0),
            control.classDescriptors[2].map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `exposes the USBMUX and NCM summaries of the configuration`() {
        val configuration = LegacyNcmDescriptors.parse(iphoneRaw()).single()

        assertTrue(configuration.hasUsbMux)
        assertEquals(1, configuration.usbMux?.id)
        assertTrue(configuration.usbMux?.hasBulkPair == true)
        assertTrue(configuration.hasNcm)
    }

    @Test
    fun `parse reports nothing for a device descriptor alone`() {
        assertTrue(LegacyNcmDescriptors.parse(deviceDescriptor()).isEmpty())
    }

    @Test
    fun `parse drops class descriptors that precede any interface`() {
        val configurations = LegacyNcmDescriptors.parse(
            iphoneRaw(
                interfaces = listOf(
                    cdcUnion(master = 3, slaves = listOf(4)),
                    usbMuxInterface(),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
        )

        assertEquals(1, configurations.size)
        assertEquals(listOf(1, 3, 4, 4), configurations.single().interfaces.map { it.id })
        assertTrue(configurations.single().interfaces[1].classDescriptors.isNotEmpty())
    }

    @Test
    fun `parse drops a configuration whose interface block does not fill wTotalLength`() {
        val payload = concat(usbMuxInterface(), bytes(0x00))
        val totalLength = 9 + payload.size
        val header = bytes(
            9, 0x02, totalLength and 0xFF, (totalLength shr 8) and 0xFF,
            1, 6, 0, 0x80, 250,
        )

        assertTrue(
            LegacyNcmDescriptors.parse(concat(deviceDescriptor(), header, payload)).isEmpty(),
        )
    }

    // ------------------------------------------------------------------ device descriptor

    @Test
    fun `rejects a device descriptor shorter than eighteen bytes`() {
        expectProtocol(deviceDescriptor().copyOf(17), MESSAGE_APPLE_DEVICE)
    }

    @Test
    fun `rejects a wrong device descriptor length`() {
        expectProtocol(iphoneRaw(device = deviceDescriptor(length = 17)), MESSAGE_APPLE_DEVICE)
    }

    @Test
    fun `rejects a wrong device descriptor type`() {
        expectProtocol(iphoneRaw(device = deviceDescriptor(type = 0x02)), MESSAGE_APPLE_DEVICE)
    }

    @Test
    fun `rejects a non Apple vendor id`() {
        expectProtocol(iphoneRaw(device = deviceDescriptor(vendorId = 0x04E8)), MESSAGE_APPLE_DEVICE)
    }

    @Test
    fun `rejects a vendor id whose byte order is not little endian`() {
        expectProtocol(iphoneRaw(device = deviceDescriptor(vendorId = 0xAC05)), MESSAGE_APPLE_DEVICE)
    }

    // ------------------------------------------------------------------ configuration selection

    @Test
    fun `rejects a configuration without a USBMUX function`() {
        expectProtocol(
            iphoneRaw(interfaces = listOf(ncmControlInterface(), ncmDataIdle(), ncmDataActive())),
            MESSAGE_NO_USBMUX,
        )
    }

    @Test
    fun `rejects a USBMUX function without a bulk pair`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    interfaceDescriptor(1, 0, 0, 0xFF, 0xFE, 0x02),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_USBMUX,
        )
    }

    @Test
    fun `rejects a USBMUX function that only has a bulk OUT endpoint`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    concat(
                        interfaceDescriptor(1, 0, 1, 0xFF, 0xFE, 0x02),
                        endpointDescriptor(0x02, 0x02, 512),
                    ),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_USBMUX,
        )
    }

    @Test
    fun `rejects two configurations that share the requested id`() {
        val raw = concat(
            deviceDescriptor(),
            configuration(6, iphoneInterfaces()),
            configuration(6, iphoneInterfaces()),
        )

        expectProtocol(raw, MESSAGE_NO_USBMUX)
    }

    @Test
    fun `rejects an unknown configuration id`() {
        val raw = iphoneRaw()

        val error = try {
            LegacyNcmDescriptors.ncm(raw, configurationId = 7)
            throw AssertionError("expected a Protocol failure for an unknown configuration id")
        } catch (error: IphoneUsbException.Protocol) {
            error
        }

        assertEquals(MESSAGE_NO_USBMUX, error.message)
    }

    // ------------------------------------------------------------------ control interface

    @Test
    fun `rejects two notifying control interfaces`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(id = 3),
                    ncmControlInterface(id = 8),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_CONTROL,
        )
    }

    @Test
    fun `rejects two control candidates that do not notify`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(id = 3, endpoints = emptyList()),
                    ncmControlInterface(id = 8, endpoints = emptyList()),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_CONTROL,
        )
    }

    @Test
    fun `uses the only control candidate when nothing notifies`() {
        val layout = LegacyNcmDescriptors.ncm(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(endpoints = emptyList()),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            configurationId = 6,
        )

        assertEquals(3, layout.control.id)
        assertTrue(layout.control.endpoints.isEmpty())
    }

    @Test
    fun `an interrupt endpoint that is not IN does not make a control interface notify`() {
        val layout = LegacyNcmDescriptors.ncm(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        endpoints = listOf(endpointDescriptor(0x05, 0x03, 16, interval = 8)),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            configurationId = 6,
        )

        assertEquals(3, layout.control.id)
        assertEquals(listOf(0x05), layout.control.endpoints.map { it.address })
    }

    // ------------------------------------------------------------------ CDC Union

    @Test
    fun `rejects a control interface without a CDC Union descriptor`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(ethernetFunctional(), ncmFunctional()),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_UNION,
        )
    }

    @Test
    fun `rejects two CDC Union descriptors on one control interface`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_UNION,
        )
    }

    @Test
    fun `rejects a CDC Union descriptor shorter than five bytes`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            bytes(4, 0x24, 0x06, 3),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_UNION,
        )
    }

    @Test
    fun `rejects a CDC Union that does not name the control interface`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 5, slaves = listOf(4)),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_UNION_NOT_SELF,
        )
    }

    // ------------------------------------------------------------------ data interface

    @Test
    fun `rejects a data interface without an active alternate setting`() {
        expectProtocol(
            iphoneRaw(interfaces = listOf(usbMuxInterface(), ncmControlInterface(), ncmDataIdle())),
            MESSAGE_NO_DATA,
        )
    }

    @Test
    fun `rejects a data active alternate setting with a single bulk endpoint`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    concat(
                        interfaceDescriptor(4, 1, 1, 0x0A, 0x00, 0x00),
                        endpointDescriptor(0x04, 0x02, 512),
                    ),
                ),
            ),
            MESSAGE_NO_DATA,
        )
    }

    @Test
    fun `rejects a data active alternate setting with three bulk endpoints`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    concat(
                        interfaceDescriptor(4, 1, 3, 0x0A, 0x00, 0x00),
                        endpointDescriptor(0x04, 0x02, 512),
                        endpointDescriptor(0x86, 0x02, 512),
                        endpointDescriptor(0x07, 0x02, 512),
                    ),
                ),
            ),
            MESSAGE_BULK_COUNT,
        )
    }

    @Test
    fun `rejects a bulk endpoint with a zero max packet size`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    concat(
                        interfaceDescriptor(4, 1, 2, 0x0A, 0x00, 0x00),
                        endpointDescriptor(0x04, 0x02, 0),
                        endpointDescriptor(0x86, 0x02, 512),
                    ),
                ),
            ),
            MESSAGE_BULK_COUNT,
        )
    }

    @Test
    fun `rejects a data interface that the CDC Union does not list`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(5)),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_DATA,
        )
    }

    @Test
    fun `rejects two data interface ids inside the CDC Union`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4, 5)),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                    ncmDataActive(id = 5, bulkOut = 0x08, bulkIn = 0x89),
                ),
            ),
            MESSAGE_NO_DATA,
        )
    }

    @Test
    fun `rejects ambiguous data alternate settings`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(),
                    ncmDataIdle(),
                    ncmDataActive(alternate = 2),
                    ncmDataActive(alternate = 3),
                ),
            ),
            MESSAGE_DATA_ALTERNATE,
        )
    }

    @Test
    fun `rejects a data interface without an idle alternate setting`() {
        expectProtocol(
            iphoneRaw(interfaces = listOf(usbMuxInterface(), ncmControlInterface(), ncmDataActive())),
            MESSAGE_NO_IDLE,
        )
    }

    // ------------------------------------------------------------------ functional descriptors

    @Test
    fun `rejects a control interface without the Ethernet functional descriptor`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_ETHERNET,
        )
    }

    @Test
    fun `rejects two Ethernet functional descriptors`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ethernetFunctional(),
                            ethernetFunctional(),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_ETHERNET,
        )
    }

    @Test
    fun `rejects an Ethernet functional descriptor shorter than thirteen bytes`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            bytes(12, 0x24, 0x0F, 7, 0, 0, 0, 0, 0xEA, 0x05, 0, 0),
                            ncmFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_ETHERNET,
        )
    }

    @Test
    fun `rejects a control interface without the NCM functional descriptor`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ethernetFunctional(),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_NCM,
        )
    }

    @Test
    fun `rejects an NCM functional descriptor shorter than six bytes`() {
        expectProtocol(
            iphoneRaw(
                interfaces = listOf(
                    usbMuxInterface(),
                    ncmControlInterface(
                        classDescriptors = listOf(
                            cdcUnion(master = 3, slaves = listOf(4)),
                            ethernetFunctional(),
                            bytes(5, 0x24, 0x1A, 0x00, 0x10),
                        ),
                    ),
                    ncmDataIdle(),
                    ncmDataActive(),
                ),
            ),
            MESSAGE_NO_NCM,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun expectProtocol(raw: ByteArray, expectedMessage: String) {
        val error = try {
            LegacyNcmDescriptors.ncm(raw, configurationId = CARPLAY_CONFIGURATION_ID)
            throw AssertionError("expected IphoneUsbException.Protocol '$expectedMessage'")
        } catch (error: IphoneUsbException.Protocol) {
            error
        }

        assertEquals(expectedMessage, error.message)
    }

    private fun iphoneRaw(
        device: ByteArray = deviceDescriptor(),
        configurationId: Int = CARPLAY_CONFIGURATION_ID,
        interfaces: List<ByteArray> = iphoneInterfaces(),
    ): ByteArray = concat(device, configuration(configurationId, interfaces))

    private fun iphoneInterfaces(): List<ByteArray> = listOf(
        usbMuxInterface(),
        ncmControlInterface(),
        ncmDataIdle(),
        ncmDataActive(),
    )

    private fun usbMuxInterface(): ByteArray = concat(
        interfaceDescriptor(1, 0, 2, 0xFF, 0xFE, 0x02),
        endpointDescriptor(0x02, 0x02, 512),
        endpointDescriptor(0x83, 0x02, 512),
    )

    private fun ncmControlInterface(
        id: Int = CONTROL_INTERFACE_ID,
        endpoints: List<ByteArray> = listOf(endpointDescriptor(0x85, 0x03, 16, interval = 8)),
        classDescriptors: List<ByteArray> = defaultClassDescriptors(id),
    ): ByteArray = concat(
        interfaceDescriptor(id, 0, endpoints.size, 0x02, 0x0D, 0x01),
        *endpoints.toTypedArray(),
        *classDescriptors.toTypedArray(),
    )

    private fun defaultClassDescriptors(controlId: Int): List<ByteArray> = listOf(
        cdcUnion(master = controlId, slaves = listOf(DATA_INTERFACE_ID)),
        ethernetFunctional(),
        ncmFunctional(),
    )

    private fun ncmDataIdle(id: Int = DATA_INTERFACE_ID): ByteArray =
        interfaceDescriptor(id, 0, 0, 0x0A, 0x00, 0x00)

    private fun ncmDataActive(
        id: Int = DATA_INTERFACE_ID,
        alternate: Int = 1,
        bulkOut: Int = 0x04,
        bulkIn: Int = 0x86,
        maxPacketSize: Int = 512,
    ): ByteArray = concat(
        interfaceDescriptor(id, alternate, 2, 0x0A, 0x00, 0x00),
        endpointDescriptor(bulkOut, 0x02, maxPacketSize),
        endpointDescriptor(bulkIn, 0x02, maxPacketSize),
    )

    private companion object {
        const val CARPLAY_CONFIGURATION_ID = 6
        const val CONTROL_INTERFACE_ID = 3
        const val DATA_INTERFACE_ID = 4

        // Verbatim copies of the reference messages (LegacyUsbConfiguration.java:579-839).
        const val MESSAGE_APPLE_DEVICE = "Legacy NCM requires an Apple USB device descriptor"
        const val MESSAGE_NO_USBMUX =
            "Active iPhone USB configuration has no unique USBMUX function"
        const val MESSAGE_NO_CONTROL =
            "Active iPhone USB configuration has no unique NCM control interface"
        const val MESSAGE_NO_UNION = "NCM control interface has no unique CDC Union descriptor"
        const val MESSAGE_UNION_NOT_SELF = "NCM union does not name its control interface"
        const val MESSAGE_NO_DATA = "NCM data interface is missing or ambiguous"
        const val MESSAGE_DATA_ALTERNATE = "NCM data alternate setting is ambiguous"
        const val MESSAGE_BULK_COUNT = "NCM data interface must expose exactly two bulk endpoints"
        const val MESSAGE_NO_IDLE = "NCM data interface has no idle alternate setting"
        const val MESSAGE_NO_ETHERNET = "NCM Ethernet functional descriptor is missing"
        const val MESSAGE_NO_NCM = "NCM functional descriptor is missing"
    }
}

// ---------------------------------------------------------------------- synthetic descriptors

private const val APPLE_VENDOR_ID = 0x05AC
private const val IPHONE_PRODUCT_ID = 0x12A8

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun concat(vararg arrays: ByteArray): ByteArray {
    val total = arrays.fold(0) { size, array -> size + array.size }
    val out = ByteArray(total)
    var offset = 0
    for (array in arrays) {
        array.copyInto(out, offset)
        offset += array.size
    }
    return out
}

private fun deviceDescriptor(
    vendorId: Int = APPLE_VENDOR_ID,
    productId: Int = IPHONE_PRODUCT_ID,
    length: Int = 18,
    type: Int = 0x01,
): ByteArray = bytes(
    length, type, 0x00, 0x02, 0x00, 0x00, 0x00, 64,
    vendorId and 0xFF, (vendorId shr 8) and 0xFF,
    productId and 0xFF, (productId shr 8) and 0xFF,
    0x00, 0x01, 1, 2, 3, 1,
)

private fun configuration(configurationId: Int, interfaces: List<ByteArray>): ByteArray {
    val payload = concat(*interfaces.toTypedArray())
    val totalLength = 9 + payload.size
    val header = bytes(
        9, 0x02, totalLength and 0xFF, (totalLength shr 8) and 0xFF,
        interfaces.size, configurationId, 0, 0x80, 250,
    )
    return concat(header, payload)
}

private fun interfaceDescriptor(
    id: Int,
    alternate: Int,
    endpointCount: Int,
    deviceClass: Int,
    subclass: Int,
    protocol: Int,
): ByteArray = bytes(9, 0x04, id, alternate, endpointCount, deviceClass, subclass, protocol, 0)

private fun endpointDescriptor(
    address: Int,
    attributes: Int,
    maxPacketSize: Int,
    interval: Int = 0,
): ByteArray = bytes(
    7, 0x05, address, attributes,
    maxPacketSize and 0xFF, (maxPacketSize shr 8) and 0xFF, interval,
)

/** `CS_INTERFACE` (`bDescriptorType == 0x24`) with `bLength == 3 + payload.size`. */
private fun csInterface(subtype: Int, vararg payload: Int): ByteArray =
    bytes(3 + payload.size, 0x24, subtype, *payload)

/** CDC Union functional descriptor (subtype 6): master interface + slave interface list. */
private fun cdcUnion(master: Int, slaves: List<Int>): ByteArray =
    csInterface(6, master, *slaves.toIntArray())

/** CDC Ethernet functional descriptor (subtype 15, `bLength == 13`) as the reference reads it. */
private fun ethernetFunctional(iMacAddress: Int = 7, maxSegmentSize: Int = 1514): ByteArray =
    csInterface(
        15,
        iMacAddress, 0, 0, 0, 0,
        maxSegmentSize and 0xFF, (maxSegmentSize shr 8) and 0xFF,
        0, 0,
        0,
    )

/** CDC-NCM functional descriptor (subtype 26, `bLength == 6`). */
private fun ncmFunctional(capabilities: Int = 0x00): ByteArray =
    csInterface(26, 0x00, 0x10, capabilities)
