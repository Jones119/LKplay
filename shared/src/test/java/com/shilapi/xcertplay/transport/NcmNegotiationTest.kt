package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CDC-NCM negotiation rules the userspace bridge depends on. The valid case is the value set the
 * legacy build's car log shows (`input=16384 output=16384 divisor=4 remainder=0 alignment=4`), so the
 * field offsets this parser uses stay pinned to something observed on the car rather than to a guess
 * (docs/_digest/58 §5 item 5).
 */
class NcmNegotiationTest {
    @Test
    fun theParameterSetTheCarLogShowsIsAccepted() {
        val parameters = NcmNegotiation.parse(response(), NcmNegotiation.NTB_PARAMETERS_BYTES)
        assertEquals(3, parameters.formats)
        assertEquals(16_384, parameters.inputSize)
        assertEquals(16_384, parameters.outputSize)
        assertEquals(4, parameters.outputDivisor)
        assertEquals(0, parameters.outputRemainder)
        assertEquals(4, parameters.outputAlignment)
    }

    @Test
    fun bothDirectionsAreBoundedByTheLegacyTransferBytes() {
        val parameters = NcmNegotiation.parse(response(input = 32_768, output = 65_536), response().size)
        assertEquals(NcmNegotiation.LEGACY_TRANSFER_BYTES, parameters.inputSize)
        assertEquals(NcmNegotiation.LEGACY_TRANSFER_BYTES, parameters.outputSize)
    }

    @Test
    fun aDeviceWithoutNtb16IsRejected() {
        assertProtocol { NcmNegotiation.parse(response(formats = 2), 28) }
    }

    @Test
    fun limitsBelowTwoKilobytesAreRejected() {
        assertProtocol { NcmNegotiation.parse(response(input = 1_024), 28) }
        assertProtocol { NcmNegotiation.parse(response(output = 1_024), 28) }
    }

    @Test
    fun alignmentMustBeAPowerOfTwoAtLeastFourAndBelowTheOutputSize() {
        for (bad in listOf(0, 1, 3, 5, 6, 16_384)) {
            assertProtocol("divisor=$bad") { NcmNegotiation.parse(response(divisor = bad), 28) }
            assertProtocol("alignment=$bad") { NcmNegotiation.parse(response(alignment = bad), 28) }
        }
    }

    @Test
    fun aRemainderAtOrAboveTheDivisorIsRejected() {
        assertProtocol { NcmNegotiation.parse(response(divisor = 4, remainder = 4), 28) }
        assertProtocol { NcmNegotiation.parse(response(divisor = 8, remainder = 9), 28) }
    }

    @Test
    fun aShortOrMislabelledResponseIsRejected() {
        assertProtocol { NcmNegotiation.parse(response(), 27) }
        assertProtocol { NcmNegotiation.parse(response(declared = 20), 28) }
        assertProtocol { NcmNegotiation.parse(response(declared = 40), 28) }
    }

    private fun assertProtocol(what: String = "", body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected IphoneUsbException.Protocol: $what")
        } catch (expected: IphoneUsbException.Protocol) {
            assertTrue("the message must not be empty: $what", expected.message.orEmpty().isNotEmpty())
        }
    }

    /**
     * A `GET_NTB_PARAMETERS` response in the layout this iPhone answers with: output size at 16,
     * output divisor/remainder/alignment at 20/22/24.
     */
    private fun response(
        formats: Int = 3,
        input: Int = 16_384,
        output: Int = 16_384,
        divisor: Int = 4,
        remainder: Int = 0,
        alignment: Int = 4,
        declared: Int = 28,
    ): ByteArray {
        val bytes = ByteArray(28)
        putU16(bytes, 0, declared)
        putU16(bytes, 2, formats)
        putU32(bytes, 4, input)
        putU16(bytes, 8, divisor)
        putU16(bytes, 10, remainder)
        putU32(bytes, 16, output)
        putU16(bytes, 20, divisor)
        putU16(bytes, 22, remainder)
        putU16(bytes, 24, alignment)
        return bytes
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
