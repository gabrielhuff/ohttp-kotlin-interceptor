package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpDecapsulationException
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import io.github.gabrielhuff.ohttp.OhttpKeyFetchException
import io.github.gabrielhuff.ohttp.OhttpKeyParseException
import io.github.gabrielhuff.ohttp.OhttpRequestEncodingException
import io.github.gabrielhuff.ohttp.OhttpUnexpectedResponseException
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Interceptor error-path coverage that the happy-path e2e suite doesn't reach.
 * A bare [MockWebServer] stands in for the relay so we can return crafted
 * responses; encapsulation uses a real (locally generated) gateway public key,
 * and decapsulation is never expected to succeed.
 */
class InterceptorErrorTest {

    private val gatewayUrl = "https://api.example.com".toHttpUrl()
    private lateinit var fakeRelay: MockWebServer

    @BeforeEach
    fun setUp() {
        fakeRelay = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        fakeRelay.shutdown()
    }

    // A parseable key config backed by a real X25519 public key, so sealing
    // succeeds and we get to exercise the response/relay error paths.
    private fun gatewayKeyConfigBytes(): ByteArray {
        val suite = Ohttp.HpkeSuite(0x0020, 0x0001, 0x0001)
        val keyPair = suite.hpke.generatePrivateKey()
        val publicKey = suite.hpke.serializePublicKey(keyPair.public)
        return Ohttp.KeyConfig.serialize(
            Ohttp.KeyConfig(0x01, 0x0020, publicKey, listOf(Ohttp.KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001))),
        )
    }

    private fun seededClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(OhttpInterceptor(gatewayUrl, fakeRelay.url("/"), defaultKeyConfigBytes = gatewayKeyConfigBytes()))
            .build()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url("https://api.example.com/v1/x").build()).execute()

    @Test
    fun `relay 5xx surfaces as OhttpUnexpectedResponseException and is not retried`() {
        fakeRelay.enqueue(MockResponse().setResponseCode(503))

        val failure = assertThrows<OhttpUnexpectedResponseException> { get(seededClient()) }

        assertEquals(503, failure.code)
        assertEquals(1, fakeRelay.requestCount) // a 5xx is not a key problem — no refresh/retry
    }

    @Test
    fun `relay 2xx with a non-ohttp content type surfaces as OhttpUnexpectedResponseException`() {
        fakeRelay.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("not encapsulated"),
        )

        val failure = assertThrows<OhttpUnexpectedResponseException> { get(seededClient()) }

        assertEquals(200, failure.code)
    }

    @Test
    fun `an ohttp-res body that won't decrypt surfaces as OhttpDecapsulationException`() {
        fakeRelay.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", Ohttp.RESPONSE_MEDIA_TYPE)
                .setBody("not a valid encapsulated response"),
        )

        assertThrows<OhttpDecapsulationException> { get(seededClient()) }
    }

    @Test
    fun `a non-bufferable request body surfaces as OhttpRequestEncodingException`() {
        val streamingBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) = throw IOException("simulated non-bufferable body")
        }

        assertThrows<OhttpRequestEncodingException> {
            seededClient().newCall(
                Request.Builder().url("https://api.example.com/v1/x").post(streamingBody).build(),
            ).execute()
        }
    }

    @Test
    fun `unparseable fetched key config surfaces as OhttpKeyParseException`() {
        val keyServer = MockWebServer().apply { start() }
        try {
            keyServer.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/ohttp-keys").setBody("not a key config"),
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(OhttpInterceptor(gatewayUrl, fakeRelay.url("/"), keyConfigUrl = keyServer.url("/keys")))
                .build()

            assertThrows<OhttpKeyParseException> { get(client) }
        } finally {
            keyServer.shutdown()
        }
    }

    @Test
    fun `refreshKey throws OhttpKeyFetchException when the key endpoint fails`() {
        val keyServer = MockWebServer().apply { start() }
        try {
            keyServer.enqueue(MockResponse().setResponseCode(404))
            val interceptor = OhttpInterceptor(gatewayUrl, fakeRelay.url("/"), keyConfigUrl = keyServer.url("/keys"))

            assertThrows<OhttpKeyFetchException> { interceptor.refreshKey() }
        } finally {
            keyServer.shutdown()
        }
    }
}
