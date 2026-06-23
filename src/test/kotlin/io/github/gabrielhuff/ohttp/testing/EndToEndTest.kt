package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpInterceptor
import io.github.gabrielhuff.ohttp.OhttpKeyFetchException
import io.github.gabrielhuff.ohttp.OhttpKeyMismatchException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndToEndTest {

    private lateinit var origin: MockWebServer
    private lateinit var infra: InProcessOhttpInfra

    @BeforeEach
    fun setUp() {
        // The intercepted request is addressed to api.example.com (the public
        // target), but in tests the gateway redirects it to the local origin.
        origin = MockWebServer().apply { start() }
        infra = InProcessOhttpInfra(originUrl = origin.url("/"))
    }

    @AfterEach
    fun tearDown() {
        infra.close()
        origin.shutdown()
    }

    private val gatewayUrl = "https://api.example.com".toHttpUrl()

    private fun makeClient(interceptor: OhttpInterceptor = makeInterceptor()): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    // Seeded with the gateway's current key and the *default* (unreachable)
    // well-known key URL — so if a fetch were wrongly triggered it would fail,
    // proving the seed alone serves these tests.
    private fun makeInterceptor(): OhttpInterceptor =
        OhttpInterceptor(
            gatewayUrl = gatewayUrl,
            relayUrl = infra.relay.url,
            defaultKeyConfigBytes = infra.gateway.keyConfigBytes,
        )

    @Test
    fun `GET request is end-to-end encapsulated through the relay`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )

        val client = makeClient()
        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/status")
                .header("X-App", "ohttp-test")
                .build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("""{"ok":true}""", response.body!!.string())

        // The origin saw the inner request decoded from BHTTP, including custom headers.
        val originReq = origin.takeRequest()
        assertEquals("/v1/status", originReq.path)
        assertEquals("GET", originReq.method)
        assertEquals("ohttp-test", originReq.getHeader("X-App"))

        // The relay only ever saw opaque encapsulated bytes.
        val seen = infra.relay.lastForwardedRequestBytes()
        assertNotNull(seen)
        assertFalse(seen!!.decodeToString().contains("X-App"))
        assertFalse(seen.decodeToString().contains("v1/status"))
    }

    @Test
    fun `POST with JSON body round-trips through OHTTP`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":42}""")
        )

        val client = makeClient()
        val body = """{"name":"widget"}"""
        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/widgets")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

        assertEquals(201, response.code)
        assertEquals("""{"id":42}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("POST", originReq.method)
        assertEquals("/v1/widgets", originReq.path)
        assertEquals(body, originReq.body.readUtf8())
        // The origin sees a real application/json content type even though OkHttp
        // tracks it on the body rather than in the headers map.
        assertEquals("application/json; charset=utf-8", originReq.getHeader("Content-Type"))
    }

    @Test
    fun `unconfigured hosts are not intercepted`() {
        // No relay involved — request goes straight to the origin like a normal call.
        origin.enqueue(MockResponse().setResponseCode(204))
        val client = makeClient()
        val response = client.newCall(
            Request.Builder()
                .url(origin.url("/passthrough"))
                .build()
        ).execute()
        assertEquals(204, response.code)
        val req = origin.takeRequest()
        assertEquals("/passthrough", req.path)
    }

    @Test
    fun `key config is fetched on first use when no default is provided`() {
        origin.enqueue(MockResponse().setResponseCode(200).setBody("fetched"))

        // No seed: the interceptor must pull the key from the distributor's
        // published well-known endpoint before it can encapsulate.
        val client = makeClient(
            OhttpInterceptor(
                gatewayUrl = gatewayUrl,
                relayUrl = infra.relay.url,
                keyConfigUrl = infra.keyDistributor.keyConfigUrl,
            )
        )

        val response = client.newCall(
            Request.Builder().url("https://api.example.com/v1/status").build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("fetched", response.body!!.string())
    }

    @Test
    fun `key rotation is recovered by an automatic refresh and retry`() {
        origin.enqueue(MockResponse().setResponseCode(200).setBody("before"))
        origin.enqueue(MockResponse().setResponseCode(200).setBody("after"))

        // Seeded with the current key, and pointed at the reachable key endpoint
        // so the post-rotation refresh can succeed.
        val client = makeClient(
            OhttpInterceptor(
                gatewayUrl = gatewayUrl,
                relayUrl = infra.relay.url,
                keyConfigUrl = infra.keyDistributor.keyConfigUrl,
                defaultKeyConfigBytes = infra.gateway.keyConfigBytes,
            )
        )

        val first = client.newCall(
            Request.Builder().url("https://api.example.com/v1/status").build()
        ).execute()
        assertEquals("before", first.body!!.string())

        // Gateway rotates: the seeded key is now stale and will be rejected.
        infra.gateway.rotateKey()

        val second = client.newCall(
            Request.Builder().url("https://api.example.com/v1/status").build()
        ).execute()
        assertEquals(200, second.code)
        assertEquals("after", second.body!!.string())
    }

    @Test
    fun `a key the gateway never accepts surfaces as OhttpKeyMismatchException`() {
        // An unrelated keypair's config, published by its own distributor. We feed
        // it to the interceptor (as seed and via its key URL) but route traffic
        // through the real gateway, which can never decapsulate it — even after
        // the refresh re-fetches the same wrong key.
        val wrongKeyConfigBytes = InProcessGateway().use { it.keyConfigBytes }
        val wrongDistributor = InProcessKeyDistributor(keyConfigBytes = { wrongKeyConfigBytes })
        try {
            val client = makeClient(
                OhttpInterceptor(
                    gatewayUrl = gatewayUrl,
                    relayUrl = infra.relay.url,
                    keyConfigUrl = wrongDistributor.keyConfigUrl,
                    defaultKeyConfigBytes = wrongKeyConfigBytes,
                )
            )

            val failure = assertThrows(OhttpKeyMismatchException::class.java) {
                client.newCall(
                    Request.Builder().url("https://api.example.com/v1/status").build()
                ).execute()
            }
            assertEquals(400, failure.code)
        } finally {
            wrongDistributor.close()
        }
    }

    @Test
    fun `a failing key endpoint surfaces as OhttpKeyFetchException`() {
        val client = makeClient(
            OhttpInterceptor(
                gatewayUrl = gatewayUrl,
                relayUrl = infra.relay.url,
                // Reachable host, but no key configuration published there.
                keyConfigUrl = origin.url("/.well-known/ohttp-gateway"),
            )
        )
        origin.enqueue(MockResponse().setResponseCode(404))

        assertThrows(OhttpKeyFetchException::class.java) {
            client.newCall(
                Request.Builder().url("https://api.example.com/v1/status").build()
            ).execute()
        }
    }

    @Test
    fun `removing the interceptor restores plain HTTP behavior`() {
        // Same target host as the encapsulated case, but no OhttpInterceptor and
        // a direct URL. Demonstrates that the interceptor is the only thing
        // standing between "OHTTP" and "regular HTTP".
        origin.enqueue(MockResponse().setResponseCode(200).setBody("plain"))
        val plainClient = OkHttpClient()
        val response = plainClient.newCall(
            Request.Builder().url(origin.url("/plain")).build()
        ).execute()
        assertEquals("plain", response.body!!.string())
    }
}
