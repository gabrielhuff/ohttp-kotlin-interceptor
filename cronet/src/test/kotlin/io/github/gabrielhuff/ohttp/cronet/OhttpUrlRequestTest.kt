package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.testing.InProcessGateway
import io.github.gabrielhuff.ohttp.testing.InProcessRelay
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OhttpUrlRequestTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: InProcessGateway
    private lateinit var relay: InProcessRelay
    private lateinit var engine: OhttpCronetEngine

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gateway = InProcessGateway(
            hostRewriter = { url ->
                url.newBuilder().host(origin.hostName).port(origin.port).scheme("http").build()
            }
        )
        relay = InProcessRelay(gatewayUrl = gateway.url)
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
    fun `GET round-trip surfaces decapsulated response through Cronet callbacks`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ohttp":"yes"}""")
        )

        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = engine.newUrlRequestBuilder("https://api.example.com/v1/things", handler, executor)
                .setHttpMethod("GET")
                .addHeader("X-Probe", "abc")
                .build()
            request.start()
            assertTrue(handler.awaitDone(5, TimeUnit.SECONDS)) { "timed out waiting for callbacks: ${handler.events}" }

            assertEquals(200, handler.info!!.httpStatusCode)
            assertEquals("application/json", handler.info!!.allHeaders["Content-Type"]?.firstOrNull())
            assertEquals("""{"ohttp":"yes"}""", handler.bodyUtf8())

            val originReq = origin.takeRequest()
            assertEquals("GET", originReq.method)
            assertEquals("/v1/things", originReq.path)
            assertEquals("abc", originReq.getHeader("X-Probe"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `POST with upload provider buffers body and round-trips`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":7}""")
        )

        val payload = """{"name":"cronet"}"""
        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = engine.newUrlRequestBuilder("https://api.example.com/v1/widgets", handler, executor)
                .setHttpMethod("POST")
                .addHeader("Content-Type", "application/json")
                .setUploadDataProvider(FixedUploadProvider(payload.toByteArray()), executor)
                .build()
            request.start()
            assertTrue(handler.awaitDone(5, TimeUnit.SECONDS)) { "events=${handler.events}" }

            assertEquals(201, handler.info!!.httpStatusCode)
            assertEquals("""{"id":7}""", handler.bodyUtf8())

            val originReq = origin.takeRequest()
            assertEquals("POST", originReq.method)
            assertEquals(payload, originReq.body.readUtf8())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unconfigured host returns a builder that bypasses OHTTP entirely`() {
        // Drive a request to a host *not* in the configs through the same engine
        // and confirm the relay never saw anything (delegate handled it directly).
        origin.enqueue(MockResponse().setResponseCode(204))
        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val directUrl = origin.url("/plain").toString()
            val request = engine.newUrlRequestBuilder(directUrl, handler, executor)
                .setHttpMethod("GET")
                .build()
            request.start()
            assertTrue(handler.awaitDone(5, TimeUnit.SECONDS))
            assertEquals(204, handler.info!!.httpStatusCode)
            // No OHTTP forwarding happened.
            assertNull(relay.lastForwardedRequestBytes())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancellation before completion delivers onCanceled`() {
        // Stall the origin so the request stays pending until we cancel.
        origin.enqueue(MockResponse().setResponseCode(200).setBodyDelay(2, TimeUnit.SECONDS).setBody("late"))
        val handler = CollectingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = engine.newUrlRequestBuilder("https://api.example.com/slow", handler, executor)
                .setHttpMethod("GET")
                .build()
            request.start()
            // Give the encapsulation a moment to dispatch the relay leg, then cancel.
            Thread.sleep(200)
            request.cancel()
            assertTrue(handler.awaitDone(3, TimeUnit.SECONDS)) { "events=${handler.events}" }
            assertTrue(handler.canceled || handler.failed) { "expected canceled/failed, got: ${handler.events}" }
        } finally {
            executor.shutdownNow()
        }
    }
}
