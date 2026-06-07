package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.testing.InProcessRelay
import io.github.gabrielhuff.ohttp.testing.ReferenceGateway
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Interop test for the Cronet OHTTP path. Drives the production
 * [OhttpCronetEngine] / `OhttpUrlRequest` pipeline against Martin Thomson's
 * `ohttp` Rust reference gateway (the spec author's implementation), validating
 * that the cronet module's encapsulation is wire-compatible with an independent
 * decapsulator.
 *
 * The [FakeCronetEngine] supplies only the HTTP transport that native Cronet
 * would otherwise own; everything OHTTP/BHTTP/HPKE here is the real
 * cronet-module code. (A real native `CronetEngine` can't run in a host JVM —
 * it needs Android + native libraries — so this is as close to end-to-end as
 * the Cronet path gets off-device.)
 *
 * Skipped automatically when the reference gateway binary isn't built; build it
 * with `./gradlew :testing:buildReferenceGateway`.
 */
@EnabledIf("io.github.gabrielhuff.ohttp.testing.ReferenceGateway#isAvailable")
class OhttpCronetReferenceGatewayInteropTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: ReferenceGateway
    private lateinit var relay: InProcessRelay
    private lateinit var engine: OhttpCronetEngine

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gateway = ReferenceGateway(originUrl = "http://${origin.hostName}:${origin.port}")
        relay = InProcessRelay(gatewayUrl = gateway.ohttpUrl.toHttpUrl())
        engine = OhttpCronetEngine(
            delegate = FakeCronetEngine(),
            configs = mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes)),
        )
    }

    @AfterEach
    fun tearDown() {
        relay.close()
        gateway.close()
        origin.shutdown()
    }

    @Test
    fun `GET round-trip through the Cronet pipeline via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"interop":"cronet-get"}""")
        )

        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            engine.newUrlRequestBuilder("https://api.example.com/v1/ping?n=1", handler, executor)
                .setHttpMethod("GET")
                .addHeader("X-Trace", "cronet-interop")
                .build()
                .start()
            assertTrue(handler.awaitDone(10, TimeUnit.SECONDS)) { "timed out: ${handler.events}" }

            assertEquals(200, handler.info!!.httpStatusCode)
            // HTTP header names are case-insensitive; the Rust reference gateway
            // emits them lowercased, so look up accordingly.
            val contentType = handler.info!!.allHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()
            assertEquals("application/json", contentType)
            assertEquals("""{"interop":"cronet-get"}""", handler.bodyUtf8())

            val originReq = origin.takeRequest()
            assertEquals("GET", originReq.method)
            assertEquals("/v1/ping?n=1", originReq.path)
            assertEquals("cronet-interop", originReq.getHeader("X-Trace"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `POST round-trip through the Cronet pipeline via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":99}""")
        )

        val payload = """{"value":"crypto-interop"}"""
        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            engine.newUrlRequestBuilder("https://api.example.com/v1/widgets", handler, executor)
                .setHttpMethod("POST")
                .addHeader("Content-Type", "application/json")
                .setUploadDataProvider(FixedUploadProvider(payload.toByteArray()), executor)
                .build()
                .start()
            assertTrue(handler.awaitDone(10, TimeUnit.SECONDS)) { "timed out: ${handler.events}" }

            assertEquals(201, handler.info!!.httpStatusCode)
            assertEquals("""{"id":99}""", handler.bodyUtf8())

            val originReq = origin.takeRequest()
            assertEquals("POST", originReq.method)
            assertEquals("/v1/widgets", originReq.path)
            assertEquals(payload, originReq.body.readUtf8())
        } finally {
            executor.shutdownNow()
        }
    }
}
