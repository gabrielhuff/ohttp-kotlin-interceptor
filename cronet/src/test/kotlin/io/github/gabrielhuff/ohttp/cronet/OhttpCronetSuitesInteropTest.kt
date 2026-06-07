package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.testing.InProcessGateway
import io.github.gabrielhuff.ohttp.testing.InProcessRelay
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives the production [OhttpCronetEngine] / `OhttpUrlRequest` pipeline through
 * a full OHTTP round-trip (encapsulate → relay → decapsulate → origin →
 * re-encapsulate → decapsulate response → Cronet callbacks) for every HPKE
 * suite the library supports.
 *
 * This is the Cronet-path counterpart to `NonFastlySuitesTest` (which covers the
 * OkHttp interceptor) and extends `OhttpUrlRequestTest` (default suite only)
 * across all suites. It uses [InProcessGateway], so unlike the reference-gateway
 * interop test it always runs — no `cargo` required — with [FakeCronetEngine]
 * standing in for native Cronet's transport.
 */
class OhttpCronetSuitesInteropTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("suites")
    fun `OHTTP round-trip through the Cronet pipeline succeeds for each supported suite`(
        @Suppress("UNUSED_PARAMETER") name: String,
        suite: InProcessGateway.Suite,
    ) {
        val origin = MockWebServer().apply { start() }
        val gateway = InProcessGateway(
            suiteIds = suite,
            hostRewriter = { url ->
                url.newBuilder().host(origin.hostName).port(origin.port).scheme("http").build()
            },
        )
        val relay = InProcessRelay(gatewayUrl = gateway.url)
        val engine = OhttpCronetEngine(
            delegate = FakeCronetEngine(),
            configs = mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes)),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"suite":"$name"}""")
            )

            val payload = """{"k":"v"}"""
            val handler = CollectingCallback()
            engine.newUrlRequestBuilder("https://api.example.com/v1/things", handler, executor)
                .setHttpMethod("POST")
                .addHeader("Content-Type", "application/json")
                .setUploadDataProvider(FixedUploadProvider(payload.toByteArray()), executor)
                .build()
                .start()
            assertTrue(handler.awaitDone(10, TimeUnit.SECONDS)) { "timed out for $name: ${handler.events}" }

            assertEquals(200, handler.info!!.httpStatusCode)
            assertEquals("""{"suite":"$name"}""", handler.bodyUtf8())

            val originReq = origin.takeRequest()
            assertEquals("POST", originReq.method)
            assertEquals("/v1/things", originReq.path)
            assertEquals(payload, originReq.body.readUtf8())
        } finally {
            executor.shutdownNow()
            relay.close()
            gateway.close()
            origin.shutdown()
        }
    }

    companion object {
        @JvmStatic
        fun suites(): List<Arguments> = listOf(
            Arguments.of("X25519+SHA256+AES128GCM", InProcessGateway.Suite.X25519_HKDFSHA256_AES128GCM),
            Arguments.of("X25519+SHA256+AES256GCM", InProcessGateway.Suite.X25519_HKDFSHA256_AES256GCM),
            Arguments.of("X25519+SHA256+ChaCha20Poly1305", InProcessGateway.Suite.X25519_HKDFSHA256_CHACHA20POLY1305),
            Arguments.of("P256+SHA256+AES128GCM", InProcessGateway.Suite.P256_HKDFSHA256_AES128GCM),
            Arguments.of("P384+SHA384+AES256GCM", InProcessGateway.Suite.P384_HKDFSHA384_AES256GCM),
            Arguments.of("P521+SHA512+AES256GCM", InProcessGateway.Suite.P521_HKDFSHA512_AES256GCM),
        )
    }
}
