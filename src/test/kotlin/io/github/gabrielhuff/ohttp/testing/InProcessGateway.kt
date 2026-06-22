package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.HpkeSuite
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * In-process OHTTP gateway, used as the upstream for [InProcessRelay] in
 * end-to-end tests. Generates an HPKE keypair on construction (via
 * BouncyCastle), exposes the corresponding key configuration via
 * [keyConfigBytes], decapsulates incoming OHTTP requests, dispatches the
 * decoded HTTP request through [originClient], and re-wraps the response.
 *
 * It also publishes its current key configuration over HTTP at
 * [keyConfigUrl] (`application/ohttp-keys`), so the interceptor's key-fetch
 * path can be exercised. [rotateKey] generates a fresh keypair, which makes
 * any request encapsulated against the old config fail to decapsulate — the
 * mechanism used to test rotation/refresh.
 *
 * Defaults to the Fastly/Cloudflare baseline suite
 * (X25519 + HKDF-SHA256 + AES-128-GCM).
 *
 * If [hostRewriter] is set, the decoded request URL is rewritten before
 * dispatch. Useful when the encapsulated request is addressed to e.g.
 * `api.example.com` but the test wants it served by a local MockWebServer.
 */
internal class InProcessGateway(
    val keyId: Int = 0x01,
    private val kemId: Int = 0x0020,
    private val kdfId: Int = 0x0001,
    private val aeadId: Int = 0x0001,
    private val originClient: OkHttpClient = OkHttpClient(),
    private val server: MockWebServer = MockWebServer(),
    private val hostRewriter: ((HttpUrl) -> HttpUrl)? = null,
) : Closeable {

    private val suite = HpkeSuite(kemId.toShort(), kdfId.toShort(), aeadId.toShort())

    private class KeyState(
        val gatewayKey: Ohttp.GatewayKey,
        val keyConfig: Ohttp.KeyConfig,
        val keyConfigBytes: ByteArray,
    )

    @Volatile
    private var state: KeyState = generateKeyState()

    /** Generates a fresh keypair, invalidating any config previously published. */
    fun rotateKey() {
        state = generateKeyState()
    }

    private fun generateKeyState(): KeyState {
        val keyPair = suite.hpke.generatePrivateKey()
        val publicKey = suite.hpke.serializePublicKey(keyPair.public)
        val privateKey = suite.hpke.serializePrivateKey(keyPair.private)
        val keyConfig = Ohttp.KeyConfig(
            keyId = keyId,
            kemId = kemId,
            publicKey = publicKey,
            symmetricAlgorithms = listOf(Ohttp.KeyConfig.SymmetricAlgorithmPair(kdfId = kdfId, aeadId = aeadId)),
        )
        return KeyState(
            gatewayKey = Ohttp.GatewayKey(keyId, suite, privateKey, publicKey),
            keyConfig = keyConfig,
            keyConfigBytes = Ohttp.KeyConfig.serialize(keyConfig),
        )
    }

    val keyConfig: Ohttp.KeyConfig get() = state.keyConfig
    val keyConfigBytes: ByteArray get() = state.keyConfigBytes

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    val url: HttpUrl
        get() = server.url("/ohttp")

    /** Where the current key configuration is published (RFC 9540 §4.1 media type). */
    val keyConfigUrl: HttpUrl
        get() = server.url("/.well-known/ohttp-gateway")

    override fun close() {
        server.shutdown()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.method == "GET" && request.path == "/.well-known/ohttp-gateway") {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/ohttp-keys")
                .setBody(okio.Buffer().write(state.keyConfigBytes))
        }
        if (request.method != "POST" || request.path != "/ohttp") {
            return MockResponse().setResponseCode(404)
        }
        val contentType = request.getHeader("Content-Type")
        if (contentType == null || !contentType.startsWith(Ohttp.REQUEST_MEDIA_TYPE)) {
            return MockResponse().setResponseCode(415)
        }

        val encReq = request.body.readByteArray()
        val decReq = try {
            Ohttp.decapsulateRequest(state.gatewayKey, encReq)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("decapsulation failed: ${t.message}")
        }
        val decodedRequest = try {
            Bhttp.decodeRequest(decReq.plaintext)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("BHTTP decode failed: ${t.message}")
        }

        val dispatched = hostRewriter?.let { rewriter ->
            decodedRequest.newBuilder().url(rewriter(decodedRequest.url)).build()
        } ?: decodedRequest

        val upstream = try {
            originClient.newCall(dispatched).execute()
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(502).setBody("origin fetch failed: ${t.message}")
        }

        val bhttpRespBytes = upstream.use { Bhttp.encodeResponse(it) }
        val encResp = Ohttp.encapsulateResponse(decReq.context, bhttpRespBytes)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", Ohttp.RESPONSE_MEDIA_TYPE)
            .setBody(okio.Buffer().write(encResp))
    }
}
