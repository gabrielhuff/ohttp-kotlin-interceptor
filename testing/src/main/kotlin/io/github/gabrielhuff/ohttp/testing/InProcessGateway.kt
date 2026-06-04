package io.github.gabrielhuff.ohttp.testing

import com.google.crypto.tink.subtle.X25519
import io.github.gabrielhuff.ohttp.KeyConfig
import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.HpkeSuite
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * In-process OHTTP gateway, used as the upstream for [InProcessRelay] in
 * end-to-end tests. It generates an X25519 keypair on construction (or
 * accepts an externally supplied one), exposes the corresponding key
 * configuration via [keyConfigBytes], decapsulates incoming OHTTP requests,
 * dispatches the decoded HTTP request through [originClient], and re-wraps
 * the response.
 *
 * If [hostRewriter] is set, the BHTTP `:authority` is rewritten before
 * dispatch. Useful when the encapsulated request is addressed to e.g.
 * `api.example.com` but the test wants it served by a local MockWebServer.
 */
public class InProcessGateway @JvmOverloads constructor(
    public val keyId: Int = 0x01,
    private val originClient: OkHttpClient = OkHttpClient(),
    private val server: MockWebServer = MockWebServer(),
    private val hostRewriter: ((HttpUrl) -> HttpUrl)? = null,
) : Closeable {

    private val suite: HpkeSuite = HpkeSuite.X25519_SHA256_AES128GCM
    private val privateKey: ByteArray = X25519.generatePrivateKey()
    private val publicKey: ByteArray = X25519.publicFromPrivate(privateKey)
    private val gatewayKey = Ohttp.GatewayKey(keyId, suite, privateKey, publicKey)

    public val keyConfig: KeyConfig = KeyConfig(
        keyId = keyId,
        kemId = 0x0020, // X25519
        publicKey = publicKey,
        symmetricAlgorithms = listOf(
            KeyConfig.SymmetricAlgorithmPair(kdfId = 0x0001, aeadId = 0x0001), // HKDF-SHA256, AES-128-GCM
        ),
    )

    public val keyConfigBytes: ByteArray = KeyConfig.serialize(keyConfig)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    public val url: HttpUrl
        get() = server.url("/ohttp")

    override fun close() {
        server.shutdown()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.method != "POST" || request.path != "/ohttp") {
            return MockResponse().setResponseCode(404)
        }
        val contentType = request.getHeader("Content-Type")
        if (contentType == null || !contentType.startsWith(Ohttp.REQUEST_MEDIA_TYPE)) {
            return MockResponse().setResponseCode(415)
        }

        val encReq = request.body.readByteArray()
        val decReq = try {
            Ohttp.decapsulateRequest(gatewayKey, encReq)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("decapsulation failed: ${t.message}")
        }
        val decoded = try {
            Bhttp.decodeRequest(decReq.plaintext)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("BHTTP decode failed: ${t.message}")
        }

        val dispatched = hostRewriter?.let { rewriter ->
            decoded.newBuilder().url(rewriter(decoded.url)).build()
        } ?: decoded

        val upstream = try {
            originClient.newCall(dispatched).execute()
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(502).setBody("origin fetch failed: ${t.message}")
        }

        val bhttpResp = upstream.use { Bhttp.encodeResponse(it) }
        val encResp = Ohttp.encapsulateResponse(decReq.context, bhttpResp)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", Ohttp.RESPONSE_MEDIA_TYPE)
            .setBody(okio.Buffer().write(encResp))
    }
}
