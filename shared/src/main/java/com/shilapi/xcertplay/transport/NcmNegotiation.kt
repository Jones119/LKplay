package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection

/**
 * The CDC-NCM class requests the API-19 userspace bridge needs before it can move NTB16 blocks.
 *
 * Our API-21+ path never needed them, because the kernel's `cdc_ncm` driver performs the same
 * negotiation - and that driver is what currently carries the wired link on this car, so its request
 * codes are known to work against this iPhone. The decompiled legacy build uses the same sequence
 * and values but its literal codes are unusable: jadx rendered them as TLS cipher-suite constants
 * (`control(connection, TLS_RSA_WITH_CAMELLIA_256_CBC_SHA, 0, …)`), which is why the codes below are
 * the specification's (`GET_NTB_PARAMETERS 0x80`, `SET_NTB_FORMAT 0x1B`, `SET_CRC_MODE 0x1E`,
 * `SET_NTB_INPUT_SIZE 0x1F`, `SET_ETHERNET_PACKET_FILTER 0x43`) while the order, the value logic and
 * the validation follow the legacy build (docs/_digest/58 §5 item 5). That build's car log shows
 * `parameters input=16384 output=16384 divisor=4 remainder=0 alignment=4`, `format16`, `crcOff`,
 * `inputSize bytes=16384`, `packetFilter flags=14`.
 */
internal object NcmNegotiation {
    /** One bulk transfer carries at most this much, whatever the iPhone advertises. */
    const val LEGACY_TRANSFER_BYTES = 16_384

    const val NTB_PARAMETERS_BYTES = 28

    /** Directed | broadcast | multicast | all-multicast, as the legacy build asks for. */
    const val PACKET_FILTER_FLAGS = 14

    private const val CLASS_INTERFACE_DEVICE_TO_HOST = 0xA1
    private const val CLASS_INTERFACE_HOST_TO_DEVICE = 0x21
    private const val GET_NTB_PARAMETERS = 0x80
    private const val SET_NTB_FORMAT = 0x1B
    private const val SET_CRC_MODE = 0x1E
    private const val SET_NTB_INPUT_SIZE = 0x1F
    private const val SET_ETHERNET_PACKET_FILTER = 0x43
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
    private const val NTB16_FORMAT = 0
    private const val CRC_MODE_OFF = 0
    private const val NTB16_BIT = 1
    private const val NTB32_BIT = 2
    private const val CAPABILITY_PACKET_FILTER = 1
    private const val CAPABILITY_CRC = 1 shl 4
    private const val MINIMUM_NTB_LIMIT = 2_048

    /** Everything the TX layout needs; [encodeFrameOffset] derives the datagram offset from it. */
    internal data class Parameters(
        val formats: Int,
        val inputSize: Int,
        val outputSize: Int,
        val outputDivisor: Int,
        val outputRemainder: Int,
        val outputAlignment: Int,
    )

    /**
     * Validates and decodes a `GET_NTB_PARAMETERS` response. Pure, so the rules are unit-tested
     * against the values the legacy build's log shows.
     */
    fun parse(bytes: ByteArray, length: Int): Parameters {
        if (length < NTB_PARAMETERS_BYTES || length > bytes.size) {
            throw IphoneUsbException.Protocol("NCM returned incomplete NTB parameters")
        }
        val declared = readU16(bytes, 0)
        if (declared < NTB_PARAMETERS_BYTES || declared > length) {
            throw IphoneUsbException.Protocol("NCM returned incomplete NTB parameters")
        }
        val formats = readU16(bytes, 2)
        if (formats and NTB16_BIT == 0) {
            throw IphoneUsbException.Protocol("iPhone NCM does not support NTB16")
        }
        val input = readU32(bytes, 4)
        val output = readU32(bytes, 16)
        if (input < MINIMUM_NTB_LIMIT || output < MINIMUM_NTB_LIMIT) {
            throw IphoneUsbException.Protocol("NCM NTB limits are too small")
        }
        val boundedOutput = output.coerceAtMost(LEGACY_TRANSFER_BYTES.toLong()).toInt()
        val divisor = readU16(bytes, 20)
        val remainder = readU16(bytes, 22)
        val alignment = readU16(bytes, 24)
        if (!validAlignment(divisor, boundedOutput) || !validAlignment(alignment, boundedOutput) ||
            remainder >= divisor
        ) {
            throw IphoneUsbException.Protocol("NCM NTB alignment parameters are invalid")
        }
        return Parameters(
            formats = formats,
            inputSize = input.coerceAtMost(LEGACY_TRANSFER_BYTES.toLong()).toInt(),
            outputSize = boundedOutput,
            outputDivisor = divisor,
            outputRemainder = remainder,
            outputAlignment = alignment,
        )
    }

    /**
     * Where a datagram starts inside an NTB16 block: the NDP16 header sits at the first
     * [Parameters.outputAlignment]-aligned offset, and the datagram itself is aligned so that
     * `offset + 14` (the Ethernet header) keeps the negotiated output remainder.
     */
    fun encodeFrameOffset(parameters: Parameters): Int {
        val ndpOffset = align(NDP16_OFFSET_LOWER_BOUND, parameters.outputAlignment, 0)
        val ethernetRemainder = (parameters.outputRemainder - ETHERNET_HEADER_BYTES) and (parameters.outputDivisor - 1)
        return align(ndpOffset + NDP16_BYTES, parameters.outputDivisor, ethernetRemainder)
    }

    /**
     * Rejects an NTB16 block whose header or NDP16 chain is malformed, including the CRC-appended
     * shape the legacy build treats as invalid. Pure: the RX path calls it before trusting offsets.
     */
    fun validateReceived(block: ByteArray, length: Int) {
        if (length < NDP16_OFFSET_LOWER_BOUND + NDP16_BYTES || length > block.size || readU16(block, 4) != 12) {
            throw IphoneUsbException.Protocol("NCM returned an invalid NTB16 header")
        }
        var ndp = readU16(block, 10)
        val visited = HashSet<Int>()
        while (ndp != 0) {
            if (!visited.add(ndp) || ndp < 12 || ndp > length - NDP16_BYTES ||
                readU32(block, ndp) != Ntb16Codec.NDP16_SIG.toLong()
            ) {
                throw IphoneUsbException.Protocol("NCM returned an invalid or CRC-appended NDP16")
            }
            val tableLength = readU16(block, ndp + 4)
            if (tableLength < NDP16_BYTES || tableLength % 4 != 0 || tableLength > length - ndp) {
                throw IphoneUsbException.Protocol("NCM returned an invalid NDP16 length")
            }
            ndp = readU16(block, ndp + 6)
        }
    }

    /**
     * Runs the request sequence: read the parameters, select NTB16 when the device offers NTB32,
     * turn CRC off when it supports CRC, and pin the input size. Android-only; every decision it
     * makes comes from [parse] so the rules stay unit-tested.
     */
    fun configure(
        connection: UsbDeviceConnection,
        controlId: Int,
        capabilities: Int,
        diagnostic: (String) -> Unit,
    ): Parameters {
        val buffer = ByteArray(NTB_PARAMETERS_BYTES)
        val count = connection.controlTransfer(
            CLASS_INTERFACE_DEVICE_TO_HOST, GET_NTB_PARAMETERS, 0, controlId,
            buffer, buffer.size, CONTROL_TIMEOUT_MILLIS,
        )
        val parameters = parse(buffer, count)
        diagnostic(
            "NCM legacy stage=parameters input=${parameters.inputSize} output=${parameters.outputSize} " +
                "divisor=${parameters.outputDivisor} remainder=${parameters.outputRemainder} " +
                "alignment=${parameters.outputAlignment}",
        )
        if (parameters.formats and NTB32_BIT != 0) {
            control(connection, SET_NTB_FORMAT, NTB16_FORMAT, controlId)
            diagnostic("NCM legacy stage=format16")
        }
        if (capabilities and CAPABILITY_CRC != 0) {
            control(connection, SET_CRC_MODE, CRC_MODE_OFF, controlId)
            diagnostic("NCM legacy stage=crcOff")
        }
        val size = ByteArray(4)
        size[0] = (parameters.inputSize and 0xff).toByte()
        size[1] = ((parameters.inputSize ushr 8) and 0xff).toByte()
        size[2] = ((parameters.inputSize ushr 16) and 0xff).toByte()
        size[3] = ((parameters.inputSize ushr 24) and 0xff).toByte()
        val written = connection.controlTransfer(
            CLASS_INTERFACE_HOST_TO_DEVICE, SET_NTB_INPUT_SIZE, 0, controlId,
            size, size.size, CONTROL_TIMEOUT_MILLIS,
        )
        if (written != size.size) {
            throw IphoneUsbException.Protocol("NCM SET_NTB_INPUT_SIZE failed")
        }
        diagnostic("NCM legacy stage=inputSize bytes=${parameters.inputSize}")
        return parameters
    }

    /** Only a device that advertises the packet-filter capability needs the request. */
    fun enablePacketFilter(
        connection: UsbDeviceConnection,
        controlId: Int,
        capabilities: Int,
        diagnostic: (String) -> Unit,
    ) {
        if (capabilities and CAPABILITY_PACKET_FILTER == 0) {
            diagnostic("NCM legacy stage=packetFilterFixed")
        } else {
            control(connection, SET_ETHERNET_PACKET_FILTER, PACKET_FILTER_FLAGS, controlId)
            diagnostic("NCM legacy stage=packetFilter flags=$PACKET_FILTER_FLAGS")
        }
    }

    /**
     * The smallest offset at or above [value] that keeps `offset % divisor == remainder`.
     *
     * `Math.floorMod` is API 24 and the module's `ForbiddenLibraryApiTest` scans for exactly that
     * kind of `java.*` API, so the non-negative modulo is spelled out.
     */
    private fun align(value: Int, divisor: Int, remainder: Int): Int =
        value + (((remainder - value) % divisor) + divisor) % divisor

    private fun validAlignment(value: Int, limit: Int): Boolean =
        value >= MINIMUM_ALIGNMENT && value < limit && (value and (value - 1)) == 0

    private fun control(connection: UsbDeviceConnection, request: Int, value: Int, index: Int) {
        val result = connection.controlTransfer(
            CLASS_INTERFACE_HOST_TO_DEVICE, request, value, index, null, 0, CONTROL_TIMEOUT_MILLIS,
        )
        if (result < 0) throw IphoneUsbException.Protocol("NCM class request $request failed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Long =
        (source[offset].toLong() and 0xff) or
            ((source[offset + 1].toLong() and 0xff) shl 8) or
            ((source[offset + 2].toLong() and 0xff) shl 16) or
            ((source[offset + 3].toLong() and 0xff) shl 24)

    private const val MINIMUM_ALIGNMENT = 4
    private const val ETHERNET_HEADER_BYTES = 14
    private const val NDP16_OFFSET_LOWER_BOUND = 12
    private const val NDP16_BYTES = 16
}
