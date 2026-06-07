package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

/**
 * Interop test: drives the Kotlin OHTTP client against
 * Martin Thomson's `ohttp` Rust crate — the spec author's reference
 * implementation, with `rust-hpke` as the HPKE backend. Validates wire-level
 * compatibility with an independent implementation by the RFC's author.
 *
 * Skipped automatically when the reference gateway binary isn't built; build it
 * with `./gradlew :testing:buildReferenceGateway` (or `cargo build --release`
 * directly in `interop/reference-gateway`).
 */
@EnabledIf("io.github.gabrielhuff.ohttp.testing.ReferenceGateway#isAvailable")
class ReferenceGatewayInteropTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: ReferenceGateway
    private lateinit var gatewayKeyConfigBytes: ByteArray
    private lateinit var relay: InProcessRelay

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gateway = ReferenceGateway(originUrl = "http://${origin.hostName}:${origin.port}")
        gatewayKeyConfigBytes = gateway.keyConfigBytes
        relay = InProcessRelay(gatewayUrl = gateway.ohttpUrl.toHttpUrl())
    }

    @AfterEach
    fun tearDown() {
        relay.close()
        gateway.close()
        origin.shutdown()
    }

    @Test
    fun `GET round-trip via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"interop":"ok"}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(
                OhttpInterceptor(
                    mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gatewayKeyConfigBytes))
                )
            )
            .build()

        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/ping?n=1")
                .header("X-Trace", "interop-42")
                .build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("""{"interop":"ok"}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("GET", originReq.method)
        assertEquals("/v1/ping?n=1", originReq.path)
        assertEquals("interop-42", originReq.getHeader("X-Trace"))
    }

    @Test
    fun `POST round-trip via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":99}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(
                OhttpInterceptor(
                    mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gatewayKeyConfigBytes))
                )
            )
            .build()

        val payload = """{"value":"crypto-interop"}"""
        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/widgets")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

        assertEquals(201, response.code)
        assertEquals("""{"id":99}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("POST", originReq.method)
        assertEquals(payload, originReq.body.readUtf8())
    }
}
