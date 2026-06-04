package io.github.gabrielhuff.ohttp.internal

import com.google.crypto.tink.subtle.Random
import io.github.gabrielhuff.ohttp.KeyConfig
import kotlin.math.max
import kotlin.experimental.xor

/**
 * RFC 9458 message-level encapsulation. This module knows nothing about HTTP —
 * it operates on opaque request/response bytes (which the caller will produce
 * via BHTTP). Both client and gateway primitives live here so the in-process
 * relay test infrastructure can share them.
 */
internal object Ohttp {

    public const val REQUEST_MEDIA_TYPE: String = "message/ohttp-req"
    public const val RESPONSE_MEDIA_TYPE: String = "message/ohttp-res"

    private const val REQUEST_INFO_LABEL = "message/bhttp request"
    private const val RESPONSE_EXPORT_LABEL = "message/bhttp response"

    // --- Client side ---

    data class EncapsulatedRequest(val ciphertext: ByteArray, val context: ClientContext)

    class ClientContext internal constructor(
        internal val hpke: HpkeContext,
        internal val suite: HpkeSuite,
        internal val enc: ByteArray,
    )

    fun encapsulateRequest(config: KeyConfig, plaintext: ByteArray): EncapsulatedRequest {
        val suite = config.pickSupportedSuite()
        val hdr = buildHeader(config.keyId, suite)
        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val sender = Hpke.setupBaseSender(suite, config.publicKey, info)
        val ct = sender.context.seal(plaintext, ByteArray(0))
        val out = hdr + sender.enc + ct
        return EncapsulatedRequest(out, ClientContext(sender.context, suite, sender.enc))
    }

    fun decapsulateResponse(context: ClientContext, encResponse: ByteArray): ByteArray {
        val (responseNonce, ct) = splitResponseNonce(context.suite, encResponse)
        val (aeadKey, aeadNonce) = deriveResponseKeys(context.hpke, context.suite, context.enc, responseNonce)
        return context.suite.aead.open(aeadKey, aeadNonce, ct, ByteArray(0))
    }

    // --- Gateway side ---

    class GatewayKey(
        val keyId: Int,
        val suite: HpkeSuite,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    class GatewayContext internal constructor(
        internal val hpke: HpkeContext,
        internal val suite: HpkeSuite,
        internal val enc: ByteArray,
    )

    data class DecapsulatedRequest(val plaintext: ByteArray, val context: GatewayContext)

    fun decapsulateRequest(gateway: GatewayKey, encRequest: ByteArray): DecapsulatedRequest {
        require(encRequest.size >= 7) { "encapsulated request too short" }
        val expectedHdr = buildHeader(gateway.keyId, gateway.suite)
        val hdr = encRequest.copyOfRange(0, 7)
        require(hdr.contentEquals(expectedHdr)) {
            "encapsulated request header does not match gateway config (got=${hdr.toHex()}, want=${expectedHdr.toHex()})"
        }
        val encLen = encSizeForKem(gateway.suite.kemId)
        require(encRequest.size >= 7 + encLen) { "encapsulated request truncated" }
        val enc = encRequest.copyOfRange(7, 7 + encLen)
        val ct = encRequest.copyOfRange(7 + encLen, encRequest.size)

        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val ctx = Hpke.setupBaseRecipient(gateway.suite, enc, gateway.privateKey, gateway.publicKey, info)
        val pt = ctx.open(ct, ByteArray(0))
        return DecapsulatedRequest(pt, GatewayContext(ctx, gateway.suite, enc))
    }

    fun encapsulateResponse(context: GatewayContext, response: ByteArray): ByteArray {
        val nonceLen = max(context.suite.nN, context.suite.nK)
        val responseNonce = Random.randBytes(nonceLen)
        val (aeadKey, aeadNonce) = deriveResponseKeys(context.hpke, context.suite, context.enc, responseNonce)
        val ct = context.suite.aead.seal(aeadKey, aeadNonce, response, ByteArray(0))
        return responseNonce + ct
    }

    // --- shared helpers ---

    private fun buildHeader(keyId: Int, suite: HpkeSuite): ByteArray {
        require(keyId in 0..0xFF) { "key id must fit in one byte" }
        return byteArrayOf(keyId.toByte()) + suite.kemId + suite.kdfId + suite.aeadId
    }

    private fun splitResponseNonce(suite: HpkeSuite, encResponse: ByteArray): Pair<ByteArray, ByteArray> {
        val nonceLen = max(suite.nN, suite.nK)
        require(encResponse.size >= nonceLen) { "encapsulated response too short for response nonce" }
        return encResponse.copyOfRange(0, nonceLen) to encResponse.copyOfRange(nonceLen, encResponse.size)
    }

    // RFC 9458 §4.5 — Response Encryption.
    private fun deriveResponseKeys(
        hpke: HpkeContext,
        suite: HpkeSuite,
        enc: ByteArray,
        responseNonce: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val secret = hpke.export(RESPONSE_EXPORT_LABEL.toByteArray(Charsets.US_ASCII), suite.nK)
        val salt = enc + responseNonce
        val hkdf = Hkdf(macAlgorithmForKdf(suite.kdfId))
        val prk = hkdf.extract(salt, secret)
        val aeadKey = hkdf.expand(prk, "key".toByteArray(Charsets.US_ASCII), suite.nK)
        val aeadNonce = hkdf.expand(prk, "nonce".toByteArray(Charsets.US_ASCII), suite.nN)
        return aeadKey to aeadNonce
    }

    private fun macAlgorithmForKdf(kdfId: ByteArray): String = when {
        kdfId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.HKDF_SHA256_KDF_ID) -> "HmacSHA256"
        kdfId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.HKDF_SHA384_KDF_ID) -> "HmacSHA384"
        kdfId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.HKDF_SHA512_KDF_ID) -> "HmacSHA512"
        else -> throw IllegalArgumentException("unsupported KDF: ${kdfId.toHex()}")
    }

    // RFC 9180 §7.1 — enc length per KEM.
    private fun encSizeForKem(kemId: ByteArray): Int = when {
        kemId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.X25519_HKDF_SHA256_KEM_ID) -> 32
        kemId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.P256_HKDF_SHA256_KEM_ID) -> 65
        kemId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.P384_HKDF_SHA384_KEM_ID) -> 97
        kemId.contentEquals(com.google.crypto.tink.hybrid.internal.HpkeUtil.P521_HKDF_SHA512_KEM_ID) -> 133
        else -> throw IllegalArgumentException("unsupported KEM: ${kemId.toHex()}")
    }
}

// kotlin doesn't have a generic XOR for ByteArrays; only used in tests right now.
internal fun ByteArray.xorWith(other: ByteArray): ByteArray {
    require(size == other.size)
    return ByteArray(size) { (this[it] xor other[it]) }
}
