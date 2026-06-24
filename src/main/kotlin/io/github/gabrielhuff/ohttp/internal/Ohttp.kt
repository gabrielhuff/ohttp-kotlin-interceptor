@file:OptIn(ExperimentalStdlibApi::class) // ByteArray.toHexString()

package io.github.gabrielhuff.ohttp.internal

import io.github.gabrielhuff.ohttp.OhttpDecapsulationException
import io.github.gabrielhuff.ohttp.OhttpKeyMismatchException
import io.github.gabrielhuff.ohttp.OhttpRequestEncodingException
import io.github.gabrielhuff.ohttp.OhttpUnexpectedResponseException
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.bouncycastle.crypto.hpke.AEAD
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContext
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.math.max

/**
 * Oblivious HTTP message-level encapsulation (RFC 9458), expressed over OkHttp
 * [Request] / [Response]. Composes BHTTP framing ([Bhttp]) with HPKE ([HpkeSuite],
 * via the byte-level seal/open primitives at the bottom of this file).
 *
 * API:
 * - [encapsulateRequest] / [decapsulateResponse] — client side: seal a request
 *   into a relay `POST`, then decode the relay's response (RFC 9458 §4.3, §4.5).
 * - [decapsulateRequest] / [encapsulateResponse] — gateway side: the exact mirror
 *   (RFC 9458 §4.4, §4.5).
 * - [KeyConfig] — parse/serialize a key configuration (RFC 9458 §3.1).
 * - [HpkeSuite] — an RFC 9180 HPKE suite specialized for OHTTP.
 *
 * The client side runs in [io.github.gabrielhuff.ohttp.OhttpInterceptor]; the
 * gateway side is exercised by the in-process test gateway. Byte-level HPKE
 * stays private so both roles share the same crypto.
 */
internal object Ohttp {

    const val REQUEST_MEDIA_TYPE: String = "message/ohttp-req"
    const val RESPONSE_MEDIA_TYPE: String = "message/ohttp-res"

    private val REQUEST_CONTENT_TYPE: MediaType = REQUEST_MEDIA_TYPE.toMediaType()
    private val RESPONSE_CONTENT_TYPE: MediaType = RESPONSE_MEDIA_TYPE.toMediaType()

    private const val REQUEST_INFO_LABEL = "message/bhttp request"
    private const val RESPONSE_EXPORT_LABEL = "message/bhttp response"

    private const val HEADER_LEN = 7

    private val EMPTY_AAD = ByteArray(0)
    private val secureRandom = SecureRandom()

    // ========================================================================================
    // CLIENT SIDE
    // ========================================================================================

    /** An [request] addressed to the relay, carrying the encapsulated payload, plus its [context]. */
    data class EncapsulatedRequest(val request: Request, val context: EncapsulationContext)

    /**
     * Encapsulates [request] for [config]'s gateway and wraps it as a `POST` to
     * [relayUrl]. Throws [OhttpRequestEncodingException] if the request can't be
     * BHTTP-encoded (e.g. a streaming/duplex body) or HPKE-sealed.
     */
    fun encapsulateRequest(request: Request, config: KeyConfig, relayUrl: HttpUrl): EncapsulatedRequest {
        val bhttp = try {
            Bhttp.encodeRequest(request)
        } catch (e: Exception) {
            throw OhttpRequestEncodingException("failed to encode request as BHTTP", e)
        }
        val (ciphertext, context) = try {
            seal(config, bhttp)
        } catch (e: Exception) {
            throw OhttpRequestEncodingException("failed to encapsulate request", e)
        }
        val relayRequest = Request.Builder()
            .url(relayUrl)
            .post(ciphertext.toRequestBody(REQUEST_CONTENT_TYPE))
            .header("Accept", RESPONSE_MEDIA_TYPE)
            .build()
        return EncapsulatedRequest(relayRequest, context)
    }

    /**
     * Decodes the relay's [response] back into the origin [Response], anchored to
     * [original]. Closes [response]. An origin-level error (404, 500, …) arrives
     * *inside* a valid `message/ohttp-res` and is returned normally. Otherwise it
     * throws: a 4xx → [OhttpKeyMismatchException], any other unexpected HTTP
     * outcome → [OhttpUnexpectedResponseException], and an ohttp-res that won't
     * decrypt/decode → [OhttpDecapsulationException].
     */
    fun decapsulateResponse(response: Response, context: EncapsulationContext, original: Request): Response {
        response.use { resp ->
            if (resp.isSuccessful && isOhttpResponse(resp)) {
                val encResponse = resp.body?.bytes()
                    ?: throw OhttpUnexpectedResponseException("OHTTP relay returned an empty body", resp.code)
                val bhttp = try {
                    open(context, encResponse)
                } catch (e: Exception) {
                    throw OhttpDecapsulationException("failed to decapsulate OHTTP response", e)
                }
                return try {
                    Bhttp.decodeResponse(bhttp, original)
                } catch (e: Exception) {
                    throw OhttpDecapsulationException("failed to decode BHTTP response", e)
                }
            }

            val code = resp.code
            // A 4xx from the relay/gateway means the gateway could not decapsulate
            // our request — read as a probable key rejection. Anything else (5xx, a
            // wrong content type, an empty body) a key refresh would not fix.
            if (!resp.isSuccessful && code in 400..499) {
                throw OhttpKeyMismatchException(
                    "gateway rejected the encapsulated request (HTTP $code); key configuration may be outdated",
                    code,
                )
            }
            throw OhttpUnexpectedResponseException("OHTTP relay returned an unexpected response (HTTP $code)", code)
        }
    }

    // ========================================================================================
    // GATEWAY SIDE
    // ========================================================================================

    /** Gateway's HPKE keypair plus the suite/keyId it is published under. */
    class GatewayKey(
        val keyId: Int,
        val suite: HpkeSuite,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    /** The inner [request] recovered from an encapsulated payload, plus the gateway-side [context]. */
    data class DecapsulatedRequest(val request: Request, val context: EncapsulationContext)

    /** Recovers the inner [Request] from the encapsulated payload carried by [request]'s body. */
    fun decapsulateRequest(request: Request, gateway: GatewayKey): DecapsulatedRequest {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val (plaintext, context) = open(gateway, buffer.readByteArray())
        return DecapsulatedRequest(Bhttp.decodeRequest(plaintext), context)
    }

    /** Encapsulates the origin's [response] into a `message/ohttp-res` [Response]. */
    fun encapsulateResponse(response: Response, context: EncapsulationContext): Response {
        val ciphertext = seal(context, Bhttp.encodeResponse(response))
        return Response.Builder()
            .request(response.request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", RESPONSE_MEDIA_TYPE)
            .body(ciphertext.toResponseBody(RESPONSE_CONTENT_TYPE))
            .build()
    }

    // ========================================================================================
    // KEY CONFIG
    // ========================================================================================

    /**
     * Parsed OHTTP key configuration (RFC 9458 §3.1). This is an implementation
     * detail of encapsulation — the public API
     * ([io.github.gabrielhuff.ohttp.OhttpInterceptor]) only ever deals in the
     * opaque published bytes.
     */
    class KeyConfig(
        val keyId: Int,
        val kemId: Int,
        val publicKey: ByteArray,
        val symmetricAlgorithms: List<SymmetricAlgorithmPair>,
    ) {
        data class SymmetricAlgorithmPair(val kdfId: Int, val aeadId: Int)

        /** The first (KDF, AEAD) pair we can instantiate for this config's KEM, or null if none. */
        fun supportedSuiteOrNull(): HpkeSuite? {
            if (kemId !in SUPPORTED_KEM_IDS) return null
            for (pair in symmetricAlgorithms) {
                if (pair.kdfId in SUPPORTED_KDF_IDS && pair.aeadId in SUPPORTED_AEAD_IDS) {
                    return HpkeSuite(kemId.toShort(), pair.kdfId.toShort(), pair.aeadId.toShort())
                }
            }
            return null
        }

        fun pickSupportedSuite(): HpkeSuite =
            supportedSuiteOrNull() ?: error("no supported KEM/KDF/AEAD in key config: $this")

        override fun toString(): String =
            "KeyConfig(keyId=$keyId, kemId=0x${"%04x".format(kemId)}, pk=${publicKey.toHexString()}, " +
                "symmetric=$symmetricAlgorithms)"

        companion object {
            // HPKE primitives we accept from a published key configuration. Values
            // are the RFC 9180 §7 registry identifiers; BouncyCastle implements all
            // of these.
            private val SUPPORTED_KEM_IDS = setOf(0x0010, 0x0011, 0x0012, 0x0020)
            private val SUPPORTED_KDF_IDS = setOf(0x0001, 0x0002, 0x0003)
            private val SUPPORTED_AEAD_IDS = setOf(0x0001, 0x0002, 0x0003)

            fun parse(bytes: ByteArray): KeyConfig {
                val src = ByteBuffer.wrap(bytes)
                val keyId = src.get().toInt() and 0xFF
                val kemId = src.short.toInt() and 0xFFFF
                val npk = publicKeySizeForKem(kemId)
                require(src.remaining() >= npk) { "key config truncated: missing public key" }
                val publicKey = ByteArray(npk)
                src.get(publicKey)
                require(src.remaining() >= 2) { "key config truncated: missing symmetric algorithms length" }
                val symLen = src.short.toInt() and 0xFFFF
                // RFC 9458 §3.1: HPKE Symmetric Algorithms Length is 4..65532 and,
                // being whole (KDF, AEAD) pairs, a multiple of 4.
                require(symLen in 4..65532) { "symmetric algorithms length out of range: $symLen" }
                require(symLen % 4 == 0) { "symmetric algorithms section must be a multiple of 4 bytes" }
                require(src.remaining() >= symLen) { "key config truncated: missing symmetric algorithms" }
                val syms = buildList {
                    val end = src.position() + symLen
                    while (src.position() < end) {
                        val kdf = src.short.toInt() and 0xFFFF
                        val aead = src.short.toInt() and 0xFFFF
                        add(SymmetricAlgorithmPair(kdf, aead))
                    }
                }
                require(!src.hasRemaining()) { "trailing bytes in key config" }
                return KeyConfig(keyId, kemId, publicKey, syms)
            }

            fun serialize(config: KeyConfig): ByteArray {
                val buf = ByteBuffer.allocate(5 + config.publicKey.size + config.symmetricAlgorithms.size * 4)
                buf.put(config.keyId.toByte())
                buf.putShort(config.kemId.toShort())
                buf.put(config.publicKey)
                buf.putShort((config.symmetricAlgorithms.size * 4).toShort())
                for (pair in config.symmetricAlgorithms) {
                    buf.putShort(pair.kdfId.toShort())
                    buf.putShort(pair.aeadId.toShort())
                }
                return buf.array()
            }

            /**
             * Parses an "application/ohttp-keys" collection (RFC 9458 §3.2): one or
             * more [parse]-able configs, each prefixed with a 2-byte length. Per §3.2
             * an incorrectly encoded collection is rejected wholesale (this throws),
             * to avoid recovery differences that could be used to segregate clients.
             */
            fun parseKeys(bytes: ByteArray): List<KeyConfig> {
                val src = ByteBuffer.wrap(bytes)
                val configs = buildList {
                    while (src.hasRemaining()) {
                        require(src.remaining() >= 2) { "ohttp-keys collection truncated: missing length prefix" }
                        val len = src.short.toInt() and 0xFFFF
                        require(len in 1..src.remaining()) { "ohttp-keys collection has an invalid config length: $len" }
                        val configBytes = ByteArray(len)
                        src.get(configBytes)
                        add(parse(configBytes)) // parse() requires the slice to be consumed exactly
                    }
                }
                require(configs.isNotEmpty()) { "ohttp-keys collection is empty" }
                return configs
            }

            /** Serializes configs into an "application/ohttp-keys" collection (RFC 9458 §3.2). */
            fun serializeKeys(configs: List<KeyConfig>): ByteArray {
                val encoded = configs.map { serialize(it) }
                val buf = ByteBuffer.allocate(encoded.sumOf { 2 + it.size })
                for (config in encoded) {
                    buf.putShort(config.size.toShort())
                    buf.put(config)
                }
                return buf.array()
            }

            // RFC 9180 §7.1 — Npk for each KEM.
            private fun publicKeySizeForKem(kemId: Int): Int = when (kemId) {
                0x0010 -> 65   // P-256 uncompressed
                0x0011 -> 97   // P-384
                0x0012 -> 133  // P-521
                0x0020 -> 32   // X25519
                0x0021 -> 56   // X448
                else -> throw IllegalArgumentException("unknown KEM id 0x${"%04x".format(kemId)}")
            }
        }
    }

    // ========================================================================================
    // SUITES
    // ========================================================================================

    /**
     * An RFC 9180 HPKE Base-mode suite specialized for OHTTP, backed by
     * BouncyCastle's `org.bouncycastle.crypto.hpke`.
     *
     * BouncyCastle's [HPKEContext] exposes Seal/Open, Export, and raw HKDF
     * Extract/Expand as public methods, so — unlike Tink — we don't have to
     * re-implement the HPKE key schedule or reach into any library-internal API.
     *
     * Identifiers are the RFC 9180 §7 registry values, encoded big-endian on the
     * OHTTP wire header. [HPKE]'s `kem_*` / `kdf_*` / `aead_*` constants carry the
     * same numeric values.
     */
    class HpkeSuite(
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

        private fun u16(v: Short): ByteArray {
            val i = v.toInt() and 0xFFFF
            return byteArrayOf((i ushr 8).toByte(), i.toByte())
        }
    }

    // ========================================================================================
    // BYTE LEVEL PRIMITIVES
    // ========================================================================================

    /**
     * HPKE context shared by both peers of one exchange (the receiver context on
     * the gateway, the sender context on the client) plus the encapsulated KEM
     * key. Carries everything needed to derive the response AEAD (RFC 9458 §4.5).
     */
    class EncapsulationContext internal constructor(
        internal val suite: HpkeSuite,
        internal val hpke: HPKEContext,
        internal val enc: ByteArray,
    )

    private fun seal(config: KeyConfig, plaintext: ByteArray): Pair<ByteArray, EncapsulationContext> {
        val suite = config.pickSupportedSuite()
        val hdr = suite.header(config.keyId)
        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val pkR: AsymmetricKeyParameter = suite.hpke.deserializePublicKey(config.publicKey)
        val ctx: HPKEContextWithEncapsulation = suite.hpke.setupBaseS(pkR, info)
        val ct = ctx.seal(EMPTY_AAD, plaintext)
        return (hdr + ctx.encapsulation + ct) to EncapsulationContext(suite, ctx, ctx.encapsulation)
    }

    private fun open(context: EncapsulationContext, encResponse: ByteArray): ByteArray {
        val (responseNonce, ct) = splitResponseNonce(context.suite, encResponse)
        val aead = deriveResponseAead(context.hpke, context.suite, context.enc, responseNonce)
        return aead.open(EMPTY_AAD, ct)
    }

    private fun open(gateway: GatewayKey, encRequest: ByteArray): Pair<ByteArray, EncapsulationContext> {
        require(encRequest.size >= HEADER_LEN) { "encapsulated request too short" }
        val expectedHdr = gateway.suite.header(gateway.keyId)
        val hdr = encRequest.copyOfRange(0, HEADER_LEN)
        require(hdr.contentEquals(expectedHdr)) {
            "encapsulated request header does not match gateway config (got=${hdr.toHexString()}, want=${expectedHdr.toHexString()})"
        }
        val encLen = gateway.suite.encLength
        require(encRequest.size >= HEADER_LEN + encLen) { "encapsulated request truncated" }
        val enc = encRequest.copyOfRange(HEADER_LEN, HEADER_LEN + encLen)
        val ct = encRequest.copyOfRange(HEADER_LEN + encLen, encRequest.size)

        val info = REQUEST_INFO_LABEL.toByteArray(Charsets.US_ASCII) + 0x00.toByte() + hdr
        val skR = gateway.suite.hpke.deserializePrivateKey(gateway.privateKey, gateway.publicKey)
        val ctx = gateway.suite.hpke.setupBaseR(enc, skR, info)
        val pt = ctx.open(EMPTY_AAD, ct)
        return pt to EncapsulationContext(gateway.suite, ctx, enc)
    }

    private fun seal(context: EncapsulationContext, response: ByteArray): ByteArray {
        val nonceLen = max(context.suite.nN, context.suite.nK)
        val responseNonce = ByteArray(nonceLen).also(secureRandom::nextBytes)
        val aead = deriveResponseAead(context.hpke, context.suite, context.enc, responseNonce)
        val ct = aead.seal(EMPTY_AAD, response)
        return responseNonce + ct
    }

    // ========================================================================================
    // HELPERS
    // ========================================================================================

    private fun isOhttpResponse(response: Response): Boolean {
        val contentType = response.body?.contentType()?.toString() ?: return false
        return contentType.startsWith(RESPONSE_MEDIA_TYPE)
    }

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
}
