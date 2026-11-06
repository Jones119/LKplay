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
 * the codes the reference binary actually sends, recovered from the TLS names jadx rendered them as
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
    private const val SET_NTB_FORMAT = 0x84
    private const val SET_CRC_MODE = 0x8A
    private const val SET_NTB_INPUT_SIZE = 0x86
    private const val SET_ETHERNET_PACKET_FILTER = 0x43
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
    // CDC-NCM `SET_NTB_FORMAT` encodes the requested format in wValue: 0 selects NTB-32 and 1 selects NTB-16.
    // This was 0, so the switch asked the device to stay on (or move to) NTB-32 and the iPhone stalled the
    // request - measured on the car as "NCM class request 0x1b (request=27 value=0 index=3 ...) returned -1"
    // while the reference build's log reads format16.
    private const val NTB16_FORMAT = 0
    private const val CRC_MODE_OFF = 0
    private const val NTB16_BIT = 1
    private const val NTB32_BIT = 2
    private const val CAPABILITY_PACKET_FILTER = 1
    private const val CAPABILITY_CRC = 1 shl 4
    private const val MINIMUM_NTB_LIMIT = 2_048

    /** Everything the TX layout needs. */
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
        val declared = readU16Le(bytes, 0)
        if (declared < NTB_PARAMETERS_BYTES || declared > length) {
            throw IphoneUsbException.Protocol("NCM returned incomplete NTB parameters")
        }
        val formats = readU16Le(bytes, 2)
        if (formats and NTB16_BIT == 0) {
            throw IphoneUsbException.Protocol("iPhone NCM does not support NTB16")
        }
        val input = readU32LeLong(bytes, 4)
        val output = readU32LeLong(bytes, 16)
        if (input < MINIMUM_NTB_LIMIT || output < MINIMUM_NTB_LIMIT) {
            throw IphoneUsbException.Protocol("NCM NTB limits are too small")
        }
        val boundedOutput = output.coerceAtMost(LEGACY_TRANSFER_BYTES.toLong()).toInt()
        val divisor = readU16Le(bytes, 20)
        val remainder = readU16Le(bytes, 22)
        val alignment = readU16Le(bytes, 24)
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
            // Best effort, measured: this iPhone stalls SET_NTB_FORMAT for both wValue 0 and 1, before and
            // after its data interface is activated, while the kernel's cdc_ncm driver carries the same
            // phone without switching anything. A stall here therefore says the device is already in the
            // format it wants - and letting it fail kept the whole userspace bridge unusable.
            try {
                control(connection, SET_NTB_FORMAT, NTB16_FORMAT, controlId)
                diagnostic("NCM legacy stage=format16")
            } catch (stall: IphoneUsbException.Protocol) {
                diagnostic("NCM legacy stage=formatSwitchStalled detail=${stall.message}")
            }
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
            // Same measured pattern: this OUT class request is stalled as well (returned -1), on top of the
            // zero-length ones. The kernel's cdc_ncm driver configured this function before the bridge
            // detached it, so the value probably stands already - and the data path is what decides whether
            // any of these stalls mattered, which is why this no longer fails the bring-up.
            diagnostic(
                "NCM legacy stage=inputSizeStalled value=${parameters.inputSize} bytes=${size.size} " +
                    "index=$controlId type=0x${CLASS_INTERFACE_HOST_TO_DEVICE.toString(16)} " +
                    "request=0x${SET_NTB_INPUT_SIZE.toString(16)} payload=${size.joinToString("") { "%02x".format(it) }} " +
                    "returned=$written",
            )
            return parameters
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

    private fun validAlignment(value: Int, limit: Int): Boolean =
        value >= MINIMUM_ALIGNMENT && value < limit && (value and (value - 1)) == 0

    /**
     * One class request to the control interface.
     *
     * Android's `controlTransfer` answers -1 on failure and exposes no errno, so the request's whole shape
     * travels in the message: with it the car log separates "the device rejected these parameters" from
     * "the request never reached the device", which is what the first `SET_NTB_FORMAT` failure needed
     * (measured: request 27 = 0x1B, value 1, index 3).
     */
    private fun control(connection: UsbDeviceConnection, request: Int, value: Int, index: Int) {
        // Measured on the car: every OUT class request stalled (SET_NTB_FORMAT 0x1b, SET_CRC_MODE 0x1e)
        // while GET_NTB_PARAMETERS succeeded - and the only difference is that this call passed a null
        // buffer. Android's controlTransfer wants a non-null buffer even when wLength is 0 on this ROM, so
        // an empty array takes its place.
        val result = connection.controlTransfer(
            CLASS_INTERFACE_HOST_TO_DEVICE, request, value, index,
            EMPTY_PAYLOAD, 0, CONTROL_TIMEOUT_MILLIS,
        )
        if (result < 0) {
            // Best effort, measured: this iPhone answers GET_NTB_PARAMETERS but stalls every zero-length OUT
            // class request (SET_NTB_FORMAT 0x1b and SET_CRC_MODE 0x1e, for wValue 0 and 1, before and after
            // the data interface is activated, with a null and with an empty buffer). The kernel's cdc_ncm
            // driver configured the same function before this bridge detached it, so the device is most
            // likely already in the state these requests ask for - which makes the data path, not this
            // request, the thing that decides whether it mattered. The stall is recorded for the caller.
            lastStall = "request=0x${request.toString(16)} value=$value index=$index result=$result"
            return
        }
    }

    /** Sent as the buffer of the zero-length class requests; null stalls on this ROM (measured). */
    private val EMPTY_PAYLOAD = ByteArray(0)

    /**
     * The last zero-length class request the device stalled, or null when none did.
     *
     * Kept as state because [control] has no diagnostic channel of its own and the bring-up is a single
     * sequence; the caller reports it right after negotiating (`NCM legacy stage=classRequestStalled`).
     */
    var lastStall: String? = null
        private set

    private const val MINIMUM_ALIGNMENT = 4
}
