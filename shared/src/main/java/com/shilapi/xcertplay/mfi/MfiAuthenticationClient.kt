package com.shilapi.xcertplay.mfi

enum class MfiCertificateType {
    MFI,
    BAA,
}

class BaaCertificatePair(leaf: ByteArray, intermediate: ByteArray) {
    val leaf: ByteArray = leaf.copyOf()
    val intermediate: ByteArray = intermediate.copyOf()

    init {
        require(this.leaf.isNotEmpty()) { "BAA leaf certificate must not be empty" }
        require(this.intermediate.isNotEmpty()) { "BAA intermediate certificate must not be empty" }
    }
}

/** Common certificate/signing contract implemented by the local identity and remote services. */
interface MfiAuthenticator {
    val certificateType: MfiCertificateType
        get() = MfiCertificateType.MFI

    fun protocolMajor(): Int

    fun readCertificate(
        maximumOutputLength: Int = MfiAuthenticator.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
    ): ByteArray

    fun signChallenge(challenge: ByteArray): ByteArray

    fun baaCertificates(): BaaCertificatePair =
        throw MfiInvalidDataException("Authenticator does not provide BAA certificates")

    companion object {
        /** Largest certificate payload the MFi layer will read from any authenticator. */
        const val DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH = 65_525
    }
}

/** MFi-layer failures that are independent of the transport carrying the request. */
sealed class MfiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MfiInvalidDataException(message: String, cause: Throwable? = null) : MfiException(message, cause)
