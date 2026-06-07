package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.KeyConfig
import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.HpkeSuite
import io.github.gabrielhuff.ohttp.internal.Ohttp
import io.github.gabrielhuff.ohttp.okhttp.OkHttpBhttpAdapter
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * In-process OHTTP gateway, used as the upstream for [InProcessRelay] in
 * end-to-end tests. Generates the appropriate HPKE keypair for the
 * requested [Suite] on construction, exposes the corresponding key
 * configuration via [keyConfigBytes], decapsulates incoming OHTTP requests,
 * dispatches the decoded HTTP request through [originClient], and re-wraps
 * the response.
 *
 * Default [Suite] is the Fastly/Cloudflare baseline
 * (X25519+HKDF-SHA256+AES-128-GCM) but the constructor accepts any
 * combination of the IDs in RFC 9180 §7 the library supports.
 *
 * If [hostRewriter] is set, the BHTTP `:authority` is rewritten before
 * dispatch. Useful when the encapsulated request is addressed to e.g.
 * `api.example.com` but the test wants it served by a local MockWebServer.
 */
public class InProcessGateway @JvmOverloads constructor(
    public val keyId: Int = 0x01,
    public val suiteIds: Suite = Suite.X25519_HKDFSHA256_AES128GCM,
    private val originClient: OkHttpClient = OkHttpClient(),
    private val server: MockWebServer = MockWebServer(),
    private val hostRewriter: ((HttpUrl) -> HttpUrl)? = null,
) : Closeable {

    /** Stable HPKE suite identifier used for both the key config and the wire header. */
    public data class Suite(val kemId: Int, val kdfId: Int, val aeadId: Int) {
        public companion object {
            public val X25519_HKDFSHA256_AES128GCM: Suite = Suite(0x0020, 0x0001, 0x0001)
            public val X25519_HKDFSHA256_AES256GCM: Suite = Suite(0x0020, 0x0001, 0x0002)
            public val X25519_HKDFSHA256_CHACHA20POLY1305: Suite = Suite(0x0020, 0x0001, 0x0003)
            public val P256_HKDFSHA256_AES128GCM: Suite = Suite(0x0010, 0x0001, 0x0001)
            public val P384_HKDFSHA384_AES256GCM: Suite = Suite(0x0011, 0x0002, 0x0002)
            public val P521_HKDFSHA512_AES256GCM: Suite = Suite(0x0012, 0x0003, 0x0002)
        }
    }

    private val hpkeSuite: HpkeSuite = HpkeSuite(
        kemId = idToBytes(suiteIds.kemId),
        kdfId = idToBytes(suiteIds.kdfId),
        aeadId = idToBytes(suiteIds.aeadId),
    )
    private val keyPair = HpkeKeyGen.generate(suiteIds.kemId)
    private val gatewayKey = Ohttp.GatewayKey(keyId, hpkeSuite, keyPair.privateKey, keyPair.publicKey)

    public val keyConfig: KeyConfig = KeyConfig(
        keyId = keyId,
        kemId = suiteIds.kemId,
        publicKey = keyPair.publicKey,
        symmetricAlgorithms = listOf(
            KeyConfig.SymmetricAlgorithmPair(kdfId = suiteIds.kdfId, aeadId = suiteIds.aeadId),
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
        val bhttpReq = try {
            Bhttp.decodeRequest(decReq.plaintext)
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(400).setBody("BHTTP decode failed: ${t.message}")
        }

        val decodedRequest = OkHttpBhttpAdapter.fromBhttp(bhttpReq)
        val dispatched = hostRewriter?.let { rewriter ->
            decodedRequest.newBuilder().url(rewriter(decodedRequest.url)).build()
        } ?: decodedRequest

        val upstream = try {
            originClient.newCall(dispatched).execute()
        } catch (t: Throwable) {
            return MockResponse().setResponseCode(502).setBody("origin fetch failed: ${t.message}")
        }

        val bhttpRespBytes = upstream.use { Bhttp.encodeResponse(OkHttpBhttpAdapter.toBhttp(it)) }
        val encResp = Ohttp.encapsulateResponse(decReq.context, bhttpRespBytes)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", Ohttp.RESPONSE_MEDIA_TYPE)
            .setBody(okio.Buffer().write(encResp))
    }

    private companion object {
        private fun idToBytes(id: Int): ByteArray =
            byteArrayOf((id ushr 8 and 0xFF).toByte(), (id and 0xFF).toByte())
    }
}
