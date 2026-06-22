package io.github.gabrielhuff.ohttp.internal

import io.github.gabrielhuff.ohttp.KeyConfig
import org.bouncycastle.crypto.hpke.AEAD
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContext
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import java.security.SecureRandom
import kotlin.math.max

/**
 * RFC 9180 HPKE Base-mode suite, backed by BouncyCastle's `org.bouncycastle.crypto.hpke`.
 *
 * BouncyCastle's [HPKEContext] exposes Seal/Open, Export, and raw HKDF
 * Extract/Expand as public methods, so — unlike Tink — we don't have to
 * re-implement the HPKE key schedule or reach into any library-internal API.
 *
 * Identifiers are the RFC 9180 §7 registry values, encoded big-endian on the
 * OHTTP wire header. [HPKE]'s `kem_*` / `kdf_*` / `aead_*` constants carry the
 * same numeric values.
 */
internal class HpkeSuite(
    val kemId: Short,
    val kdfId: Short,
    val aeadId: Short,
) {
    val hpke: HPKE = HPKE(HPKE.mode_base, kemId, kdfId, aeadId)

    /** Nk — AEAD key length. */
    val nK: Int = when (aeadId) {
        HPKE.aead_AES_GCM128 -> 16
        HPKE.aead_AES_GCM256 -> 32
        HPKE.aead_CHACHA20_POLY1305 -> 32
        else -> throw IllegalArgumentException("unsupported AEAD: 0x${"%04x".format(aeadId.toInt() and 0xFFFF)}")
    }

    /** Nn — AEAD nonce length. All three OHTTP AEADs use a 12-byte nonce. */
    val nN: Int = 12

    /** Nenc — length of the encapsulated KEM key (`enc`) on the wire. */
    val encLength: Int get() = hpke.encSize

    /** OHTTP request header: keyId(1) || kemId(2) || kdfId(2) || aeadId(2). */
    fun header(keyId: Int): ByteArray {
        require(keyId in 0..0xFF) { "key id must fit in one byte" }
        return byteArrayOf(keyId.toByte()) + u16(kemId) + u16(kdfId) + u16(aeadId)
    }

    companion object {
        val X25519_SHA256_AES128GCM = HpkeSuite(
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_AES_GCM128,
        )

        private fun u16(v: Short): ByteArray {
            val i = v.toInt() and 0xFFFF
            return byteArrayOf((i ushr 8).toByte(), i.toByte())
        }
    }
}

/**
 * RFC 9458 message-level encapsulation. This object knows nothing about HTTP —
 * it operates on opaque request/response bytes (which the caller produces via
 * BHTTP). Both client and gateway primitives live here so the in-process relay
 * test infrastructure can share them.
 */
internal object Ohttp {

    const val REQUEST_MEDIA_TYPE: String = "message/ohttp-req"
    const val RESPONSE_MEDIA_TYPE: String = "message/ohttp-res"

    private const val REQUEST_INFO_LABEL = "message/bhttp request"
    private const val RESPONSE_EXPORT_LABEL = "message/bhttp response"

    private const val HEADER_LEN = 7

    private val secureRandom = SecureRandom()

    // --- Client side ---

    data class EncapsulatedRequest(val ciphertext: ByteArray, val context: ClientContext)

    class ClientContext internal constructor(
        internal val suite: HpkeSuite,
        internal val hpke: HPKEContext,
        internal val enc: ByteArray,
    )

    fun encapsulateRequest(config: KeyConfig, plaintext: ByteArray): EncapsulatedRequest {
        val suite = config.pickSupportedSuite()
        val hdr = suite.header(config.keyId)
        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val pkR: AsymmetricKeyParameter = suite.hpke.deserializePublicKey(config.publicKey)
        val ctx: HPKEContextWithEncapsulation = suite.hpke.setupBaseS(pkR, info)
        val ct = ctx.seal(EMPTY_AAD, plaintext)
        val out = hdr + ctx.encapsulation + ct
        return EncapsulatedRequest(out, ClientContext(suite, ctx, ctx.encapsulation))
    }

    fun decapsulateResponse(context: ClientContext, encResponse: ByteArray): ByteArray {
        val (responseNonce, ct) = splitResponseNonce(context.suite, encResponse)
        val aead = deriveResponseAead(context.hpke, context.suite, context.enc, responseNonce)
        return aead.open(EMPTY_AAD, ct)
    }

    // --- Gateway side ---

    class GatewayKey(
        val keyId: Int,
        val suite: HpkeSuite,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    class GatewayContext internal constructor(
        internal val suite: HpkeSuite,
        internal val hpke: HPKEContext,
        internal val enc: ByteArray,
    )

    data class DecapsulatedRequest(val plaintext: ByteArray, val context: GatewayContext)

    fun decapsulateRequest(gateway: GatewayKey, encRequest: ByteArray): DecapsulatedRequest {
        require(encRequest.size >= HEADER_LEN) { "encapsulated request too short" }
        val expectedHdr = gateway.suite.header(gateway.keyId)
        val hdr = encRequest.copyOfRange(0, HEADER_LEN)
        require(hdr.contentEquals(expectedHdr)) {
            "encapsulated request header does not match gateway config (got=${hdr.toHex()}, want=${expectedHdr.toHex()})"
        }
        val encLen = gateway.suite.encLength
        require(encRequest.size >= HEADER_LEN + encLen) { "encapsulated request truncated" }
        val enc = encRequest.copyOfRange(HEADER_LEN, HEADER_LEN + encLen)
        val ct = encRequest.copyOfRange(HEADER_LEN + encLen, encRequest.size)

        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val skR = gateway.suite.hpke.deserializePrivateKey(gateway.privateKey, gateway.publicKey)
        val ctx = gateway.suite.hpke.setupBaseR(enc, skR, info)
        val pt = ctx.open(EMPTY_AAD, ct)
        return DecapsulatedRequest(pt, GatewayContext(gateway.suite, ctx, enc))
    }

    fun encapsulateResponse(context: GatewayContext, response: ByteArray): ByteArray {
        val nonceLen = max(context.suite.nN, context.suite.nK)
        val responseNonce = ByteArray(nonceLen).also(secureRandom::nextBytes)
        val aead = deriveResponseAead(context.hpke, context.suite, context.enc, responseNonce)
        val ct = aead.seal(EMPTY_AAD, response)
        return responseNonce + ct
    }

    // --- shared helpers ---

    private fun splitResponseNonce(suite: HpkeSuite, encResponse: ByteArray): Pair<ByteArray, ByteArray> {
        val nonceLen = max(suite.nN, suite.nK)
        require(encResponse.size >= nonceLen) { "encapsulated response too short for response nonce" }
        return encResponse.copyOfRange(0, nonceLen) to encResponse.copyOfRange(nonceLen, encResponse.size)
    }

    // RFC 9458 §4.5 — Response Encryption. Uses the context's exporter secret
    // plus plain (unlabeled) HKDF, both provided directly by BouncyCastle's
    // HPKEContext, to derive a fresh AEAD key/nonce.
    private fun deriveResponseAead(
        hpke: HPKEContext,
        suite: HpkeSuite,
        enc: ByteArray,
        responseNonce: ByteArray,
    ): AEAD {
        val secret = hpke.export(RESPONSE_EXPORT_LABEL.toByteArray(Charsets.US_ASCII), suite.nK)
        val salt = enc + responseNonce
        val prk = hpke.extract(salt, secret)
        val aeadKey = hpke.expand(prk, "key".toByteArray(Charsets.US_ASCII), suite.nK)
        val aeadNonce = hpke.expand(prk, "nonce".toByteArray(Charsets.US_ASCII), suite.nN)
        return AEAD(suite.aeadId, aeadKey, aeadNonce)
    }

    private val EMPTY_AAD = ByteArray(0)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
