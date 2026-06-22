package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * In-process OHTTP gateway, used as the upstream for [InProcessRelay] in
 * end-to-end tests. Generates an HPKE keypair on construction (via
 * BouncyCastle), exposes the corresponding key configuration via
 * [keyConfigBytes] (served to clients by [InProcessKeyDistributor]),
 * decapsulates incoming OHTTP requests, dispatches the decoded HTTP request
 * through [originClient], and re-wraps the response.
 *
 * It mirrors the client exactly through the symmetric [Ohttp] methods, bridging
 * MockWebServer's [RecordedRequest]/[MockResponse] to OkHttp `Request`/`Response`
 * with two small adapters.
 *
 * Defaults to the Fastly/Cloudflare baseline suite
 * (X25519 + HKDF-SHA256 + AES-128-GCM).
 *
 * If [originUrl] is set, the decoded request's scheme/host/port are rewritten to
 * it before dispatch. Useful when the encapsulated request is addressed to e.g.
 * `api.example.com` but the test wants it served by a local MockWebServer.
 * [rotateKey] generates a fresh keypair, making any request encapsulated against
 * the previously published config fail to decapsulate — the rotation mechanism.
 */
internal class InProcessGateway(
    val keyId: Int = 0x01,
    private val kemId: Int = 0x0020,
    private val kdfId: Int = 0x0001,
    private val aeadId: Int = 0x0001,
    private val originUrl: HttpUrl? = null,
    private val originClient: OkHttpClient = OkHttpClient(),
    private val server: MockWebServer = MockWebServer(),
) : Closeable {

    private val suite = Ohttp.HpkeSuite(kemId.toShort(), kdfId.toShort(), aeadId.toShort())

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

    override fun close() {
        server.shutdown()
    }

    private fun handle(recorded: RecordedRequest): MockResponse {
        if (recorded.method != "POST" || recorded.path != "/ohttp") {
            return MockResponse().setResponseCode(404)
        }
        val contentType = recorded.getHeader("Content-Type")
        if (contentType == null || !contentType.startsWith(Ohttp.REQUEST_MEDIA_TYPE)) {
            return MockResponse().setResponseCode(415)
        }

        val decapsulated = try {
            Ohttp.decapsulateRequest(recorded.toOkHttpRequest(), state.gatewayKey)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("decapsulation failed: ${t.message}")
        }

        val inner = decapsulated.request.let { req ->
            originUrl?.let { origin ->
                req.newBuilder()
                    .url(req.url.newBuilder().scheme(origin.scheme).host(origin.host).port(origin.port).build())
                    .build()
            } ?: req
        }

        val outer = try {
            originClient.newCall(inner).execute().use { upstream ->
                Ohttp.encapsulateResponse(upstream, decapsulated.context)
            }
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(502).setBody("origin fetch failed: ${t.message}")
        }

        return outer.toMockResponse()
    }

    /** Wraps the received POST body as an OkHttp [Request] so [Ohttp.decapsulateRequest] can read it. */
    private fun RecordedRequest.toOkHttpRequest(): Request =
        Request.Builder()
            .url(url)
            .post(body.readByteArray().toRequestBody(Ohttp.REQUEST_MEDIA_TYPE.toMediaType()))
            .build()

    /** Unwraps the encapsulated [Response] back into a MockWebServer [MockResponse]. */
    private fun Response.toMockResponse(): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", body?.contentType()?.toString() ?: Ohttp.RESPONSE_MEDIA_TYPE)
            .setBody(okio.Buffer().write(body?.bytes() ?: ByteArray(0)))
}
