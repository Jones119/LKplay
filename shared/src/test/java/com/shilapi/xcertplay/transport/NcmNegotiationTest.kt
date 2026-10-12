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

    @Test
    fun theDatagramOffsetKeepsTheNegotiatedEthernetRemainder() {
        val parameters = NcmNegotiation.parse(response(), 28)
        val offset = NcmNegotiation.encodeFrameOffset(parameters)
        // The legacy build sends the datagram at 30, not at the 28 our fixed-index encoder uses, so
        // that (offset + 14) % divisor == remainder. This test pins that rule.
        assertEquals(30, offset)
        assertEquals(parameters.outputRemainder, (offset + 14) % parameters.outputDivisor)
    }

    @Test
    fun aMalformedNtbHeaderIsRejected() {
        val block = ntbBlock()
        assertProtocol { NcmNegotiation.validateReceived(block, 27) }
        assertProtocol { NcmNegotiation.validateReceived(block, block.size + 1) }
        val wrongHeaderLength = ntbBlock()
        putU16(wrongHeaderLength, 4, 8)
        assertProtocol { NcmNegotiation.validateReceived(wrongHeaderLength, wrongHeaderLength.size) }
    }

    @Test
    fun theNdpChainIsWalkedAndACycleIsRejected() {
        val cyclic = ntbBlock()
        putU16(cyclic, 12 + 6, 12) // next NDP16 points back at itself
        assertProtocol { NcmNegotiation.validateReceived(cyclic, cyclic.size) }

        val badSignature = ntbBlock()
        putU32(badSignature, 12, 0)
        assertProtocol { NcmNegotiation.validateReceived(badSignature, badSignature.size) }

        val badTableLength = ntbBlock()
        putU16(badTableLength, 12 + 4, 15)
        assertProtocol { NcmNegotiation.validateReceived(badTableLength, badTableLength.size) }
    }

    private fun assertProtocol(what: String = "", body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected IphoneUsbException.Protocol: $what")
        } catch (expected: IphoneUsbException.Protocol) {
            assertTrue("the message must not be empty: $what", expected.message.orEmpty().isNotEmpty())
        }
    }

    /** One NTB16 block: a 12-byte NTH16 whose NDP16 sits at 12 and ends the chain. */
    private fun ntbBlock(): ByteArray {
        val block = ByteArray(28)
        putU32(block, 0, Ntb16Codec.NTH16_SIG)
        putU16(block, 4, 12)
        putU16(block, 6, 0)
        putU16(block, 8, 28)
        putU16(block, 10, 12)
        putU32(block, 12, Ntb16Codec.NDP16_SIG)
        putU16(block, 12 + 4, 16)
        putU16(block, 12 + 6, 0)
        return block
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
