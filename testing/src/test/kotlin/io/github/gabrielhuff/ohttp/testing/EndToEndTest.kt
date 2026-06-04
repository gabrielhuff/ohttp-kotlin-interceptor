package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndToEndTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: InProcessGateway
    private lateinit var relay: InProcessRelay

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gateway = InProcessGateway(
            // The intercepted request is addressed to api.example.com (the public
            // target), but in tests we redirect it to the local origin server.
            hostRewriter = { url ->
                url.newBuilder()
                    .host(origin.hostName)
                    .port(origin.port)
                    .scheme("http")
                    .build()
            }
        )
        relay = InProcessRelay(gatewayUrl = gateway.url)
    }

    @AfterEach
    fun tearDown() {
        relay.close()
        gateway.close()
        origin.shutdown()
    }

    private fun makeClient(): OkHttpClient {
        val configs = mapOf(
            "api.example.com" to OhttpConfig(relay.url, gateway.keyConfigBytes),
        )
        return OkHttpClient.Builder()
            .addInterceptor(OhttpInterceptor(configs))
            .build()
    }

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
        val seen = relay.lastForwardedRequestBytes()
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
