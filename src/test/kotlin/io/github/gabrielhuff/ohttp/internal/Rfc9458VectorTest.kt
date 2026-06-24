@file:OptIn(ExperimentalStdlibApi::class) // String.hexToByteArray()

package io.github.gabrielhuff.ohttp.internal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Known-answer test against the worked example in RFC 9458 Appendix A. Because
 * Seal() uses a random ephemeral we can't reproduce the client's enc_request,
 * but the deterministic directions — key-config parsing, request decapsulation
 * with the published secret key, and response decapsulation — are pinned here.
 * This is the only check that our crypto is wire-compatible with the spec rather
 * than merely self-consistent with our own gateway.
 */
class Rfc9458VectorTest {

    private val secretKey = "3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a".hexToByteArray()
    private val publicKey = "31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155".hexToByteArray()
    private val keyConfig =
        "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e79815500080001000100010003".hexToByteArray()
    private val encRequest = (
        "010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472" +
            "6374e469135906992e1268c594d2a10c695d858c40a026e7965e7d86b83dd440b2c0185204b4d63525"
        ).hexToByteArray()
    private val encResponse =
        "c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f857fbd".hexToByteArray()

    @Test
    fun `key configuration parses to the documented fields`() {
        val config = Ohttp.KeyConfig.parse(keyConfig)
        assertEquals(0x01, config.keyId)
        assertEquals(0x0020, config.kemId)
        assertArrayEquals(publicKey, config.publicKey)
        assertEquals(
            listOf(
                Ohttp.KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001),
                Ohttp.KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0003),
            ),
            config.symmetricAlgorithms,
        )
    }

    @Test
    fun `gateway decapsulates the request and client decapsulates the response`() {
        val suite = Ohttp.HpkeSuite(0x0020.toShort(), 0x0001.toShort(), 0x0001.toShort())
        val gatewayKey = Ohttp.GatewayKey(keyId = 0x01, suite = suite, privateKey = secretKey, publicKey = publicKey)

        // Request direction: HPKE open + BHTTP decode of the documented enc_request.
        val encapsulatedRequest = Request.Builder()
            .url("https://relay.example/")
            .post(encRequest.toRequestBody(Ohttp.REQUEST_MEDIA_TYPE.toMediaType()))
            .build()
        val decapsulated = Ohttp.decapsulateRequest(encapsulatedRequest, gatewayKey)
        val inner = decapsulated.request
        assertEquals("GET", inner.method)
        assertEquals("https", inner.url.scheme)
        assertEquals("example.com", inner.url.host)
        assertEquals("/", inner.url.encodedPath)

        // Response direction: the gateway (receiver) context shares the exporter
        // secret with the client's (sender) context, so it derives the same
        // response AEAD and decrypts the documented enc_response to a 200.
        val encapsulatedResponse = Response.Builder()
            .request(inner)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", Ohttp.RESPONSE_MEDIA_TYPE)
            .body(encResponse.toResponseBody(Ohttp.RESPONSE_MEDIA_TYPE.toMediaType()))
            .build()
        val response = Ohttp.decapsulateResponse(encapsulatedResponse, decapsulated.context, inner)
        assertEquals(200, response.code)
    }
}
