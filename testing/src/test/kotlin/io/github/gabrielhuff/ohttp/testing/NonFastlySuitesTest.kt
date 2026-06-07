package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Round-trips a real OHTTP exchange (encapsulate → relay → decapsulate →
 * origin → re-encapsulate → decapsulate response) for every HPKE suite the
 * library claims to handle. Each case is independent — fresh keypair, fresh
 * relay/gateway, fresh origin — so we catch any per-suite encoding or
 * key-size bug.
 */
class NonFastlySuitesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("suites")
    fun `end-to-end OHTTP round-trip succeeds for each supported suite`(
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
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"suite":"${'$'}{name}"}""".replace("\${name}", suite.toString()))
            )

            val client = OkHttpClient.Builder()
                .addInterceptor(
                    OhttpInterceptor(
                        mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes))
                    )
                )
                .build()

            val resp = client.newCall(
                Request.Builder()
                    .url("https://api.example.com/v1/things")
                    .post("""{"k":"v"}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            assertEquals(200, resp.code)
            val body = resp.body!!.string()
            assert(body.contains("suite")) { "unexpected body: $body" }

            val originReq = origin.takeRequest()
            assertEquals("POST", originReq.method)
            assertEquals("/v1/things", originReq.path)
            assertEquals("""{"k":"v"}""", originReq.body.readUtf8())
        } finally {
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
