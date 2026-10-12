package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The position mapping the API-19 userspace bridge depends on.
 *
 * `UsbDevice.getInterface(i)` enumerates the **active** configuration, alternate settings included,
 * while the parser walks every configuration in the raw descriptors. Getting this off by one claims the
 * wrong interface - which on the car shows up as a failed probe rather than as a wrong answer - so the
 * rule is pinned here instead of being debugged during an ignition window (docs/_digest/58 §5 item 1).
 */
class LegacyNcmSelectionTest {
    @Test
    fun theOrderHoldsOnlyTheRequestedConfigurationAlternatesIncluded() {
        val raw = descriptors(
            configuration(value = 1, 0 to 0),
            configuration(
                value = 6,
                1 to 0, // USBMUX
                3 to 0, // NCM control
                4 to 0, // NCM data, idle alternate
                4 to 1, // NCM data, active alternate
            ),
        )
        assertEquals(listOf(1 to 0, 3 to 0, 4 to 0, 4 to 1), LegacyNcmSelection.interfaceOrder(raw, 6))
        assertEquals(listOf(0 to 0), LegacyNcmSelection.interfaceOrder(raw, 1))
    }

    @Test
    fun aTruncatedDescriptorStopsTheWalkInsteadOfReadingPastTheEnd() {
        // The second interface is cut short: the complete one before it is still reported, and the
        // malformed tail ends the walk rather than being read past the end of the array.
        val raw = descriptors(configuration(value = 6, 1 to 0, 3 to 0))
        val truncated = raw.copyOf(raw.size - 3)
        assertEquals(listOf(1 to 0), LegacyNcmSelection.interfaceOrder(truncated, 6))
        assertEquals(emptyList<Pair<Int, Int>>(), LegacyNcmSelection.interfaceOrder(byteArrayOf(9), 6))
    }

    @Test
    fun theActiveAlternateDecidesTheIndexNotTheInterfaceNumber() {
        // The platform lists alt 0 before alt 1, so the data interface sits at index 3 even though its
        // number is 4; a layout that used the number as an index would claim the wrong one.
        val raw = descriptors(
            configuration(
                value = 6,
                1 to 0,
                3 to 0,
                4 to 0,
                4 to 1,
            ),
        )
        val order = LegacyNcmSelection.interfaceOrder(raw, 6)
        val indices = LegacyNcmSelection.locate(order, layout(controlId = 3, dataId = 4, dataAlternate = 1))
        assertEquals(LegacyNcmSelection.Indices(control = 1, data = 3), indices)
    }

    @Test
    fun aLayoutWhoseDataAlternateIsMissingLocatesNothing() {
        val raw = descriptors(configuration(value = 6, 3 to 0, 4 to 0))
        val order = LegacyNcmSelection.interfaceOrder(raw, 6)
        assertNull(LegacyNcmSelection.locate(order, layout(controlId = 3, dataId = 4, dataAlternate = 1)))
        assertNull(
            "a configuration the descriptors never mention cannot be located either",
            LegacyNcmSelection.locate(order, layout(controlId = 9, dataId = 4, dataAlternate = 0)),
        )
    }

    private fun layout(controlId: Int, dataId: Int, dataAlternate: Int): LegacyNcmLayout = LegacyNcmLayout(
        control = ncmInterface(controlId, 0),
        data = ncmInterface(dataId, dataAlternate),
        idle = ncmInterface(dataId, 0),
        iMacAddressIndex = 7,
        maxSegmentSize = 1514,
        capabilities = 0,
    )

    private fun ncmInterface(id: Int, alternate: Int) = LegacyNcmInterface(
        id = id,
        alternate = alternate,
        deviceClass = if (id == 3) 0x02 else 0x0A,
        subclass = if (id == 3) 0x0D else 0x00,
        protocol = 0,
        endpoints = emptyList(),
        classDescriptors = emptyList(),
    )

    /** Concatenates configurations of nine-byte interfaces: only the fields this rule reads are set. */
    private fun descriptors(vararg configurations: ByteArray): ByteArray {
        val out = ByteArray(18) // a device descriptor, so the blob starts like the real one
        out[0] = 18
        out[1] = 1
        return configurations.fold(out) { acc, next -> acc + next }
    }

    private fun configuration(value: Int, vararg interfaces: Pair<Int, Int>): ByteArray {
        val total = 9 + interfaces.size * 9
        val config = ByteArray(9)
        config[0] = 9
        config[1] = 0x02
        config[2] = (total and 0xff).toByte()
        config[3] = ((total ushr 8) and 0xff).toByte()
        config[4] = interfaces.size.toByte()
        config[5] = value.toByte()
        return interfaces.fold(config) { acc, (number, alternate) ->
            val descriptor = ByteArray(9)
            descriptor[0] = 9
            descriptor[1] = 0x04
            descriptor[2] = number.toByte()
            descriptor[3] = alternate.toByte()
            acc + descriptor
        }
    }
}
