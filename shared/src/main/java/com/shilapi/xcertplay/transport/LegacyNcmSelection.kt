package com.shilapi.xcertplay.transport

/**
 * Which of the platform's interfaces the parsed NCM layout refers to.
 *
 * On API 19 the platform hands out `UsbDevice.getInterface(i)` for the **active** configuration only,
 * while [LegacyNcmDescriptors] parses every configuration in `getRawDescriptors()`. The two lists are
 * in the same descriptor order, and alternate settings appear as separate entries in both, so the
 * mapping is by position - which is exactly the step that silently claims the wrong interface if it is
 * off by one. Kept pure and outside the bridge so this rule is unit-tested (docs/_digest/58 §5 item 1).
 */
internal object LegacyNcmSelection {
    private const val DESCRIPTOR_TYPE_CONFIGURATION = 0x02
    private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
    private const val CONFIGURATION_VALUE_OFFSET = 5
    private const val INTERFACE_NUMBER_OFFSET = 2
    private const val INTERFACE_ALTERNATE_OFFSET = 3
    private const val MINIMUM_DESCRIPTOR_BYTES = 9

    /** Interface (number, alternate) pairs of [configurationId], in descriptor order. */
    fun interfaceOrder(raw: ByteArray, configurationId: Int): List<Pair<Int, Int>> {
        val order = ArrayList<Pair<Int, Int>>(4)
        var offset = 0
        var inConfiguration = false
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            when {
                type == DESCRIPTOR_TYPE_CONFIGURATION && length >= MINIMUM_DESCRIPTOR_BYTES ->
                    inConfiguration =
                        (raw[offset + CONFIGURATION_VALUE_OFFSET].toInt() and 0xff) == configurationId
                inConfiguration && type == DESCRIPTOR_TYPE_INTERFACE && length >= MINIMUM_DESCRIPTOR_BYTES ->
                    order.add(
                        (raw[offset + INTERFACE_NUMBER_OFFSET].toInt() and 0xff) to
                            (raw[offset + INTERFACE_ALTERNATE_OFFSET].toInt() and 0xff),
                    )
            }
            offset += length
        }
        return order
    }

    /**
     * Positions in [order] of the layout's control and data interfaces; null when either is absent.
     *
     * The idle alternate needs no position: it is selected by interface *number* through the native
     * ioctl, and [LegacyNcmDescriptors.ncm] has already refused a layout without it.
     */
    fun locate(order: List<Pair<Int, Int>>, layout: LegacyNcmLayout): Indices? {
        val control = order.indexOf(layout.control.id to layout.control.alternate)
        val data = order.indexOf(layout.data.id to layout.data.alternate)
        if (control < 0 || data < 0) return null
        return Indices(control = control, data = data)
    }

    data class Indices(val control: Int, val data: Int)

    /**
     * How many interface entries the platform's list holds before [configurationId]'s own.
     *
     * Measured on the car: `UsbDevice.getInterface(i)` enumerates **every** configuration's interfaces (31
     * entries for an iPhone that reports six configurations) while [interfaceOrder] covers only the one
     * configuration it is asked about (11 entries for configuration 6). Indexing the platform list with a
     * position from that order therefore claimed another configuration's interfaces: the interface the
     * kernel's cdc_ncm driver held was never claimed, and parking it answered EBUSY (errno 16) from both the
     * ioctl and the control-transfer fallback. Adding this offset is what makes the two lists line up.
     */
    fun platformOffset(raw: ByteArray, configurationId: Int): Int {
        var count = 0
        var current = -1
        var reached = false
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            when {
                type == DESCRIPTOR_TYPE_CONFIGURATION && length >= MINIMUM_DESCRIPTOR_BYTES -> {
                    current = raw[offset + CONFIGURATION_VALUE_OFFSET].toInt() and 0xff
                    if (current == configurationId) reached = true
                }
                !reached && current != -1 &&
                    type == DESCRIPTOR_TYPE_INTERFACE && length >= MINIMUM_DESCRIPTOR_BYTES -> count++
            }
            offset += length
        }
        return count
    }
}
