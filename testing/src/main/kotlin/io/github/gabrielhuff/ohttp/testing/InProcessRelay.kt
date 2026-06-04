package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process OHTTP relay backed by [MockWebServer]. Behaves like a fake Fastly
 * deployment: accepts `POST /ohttp` with `Content-Type: message/ohttp-req`,
 * forwards the encapsulated body byte-for-byte to the configured gateway
 * URL, and returns the encapsulated response. It does not decrypt anything
 * (per RFC 9458 §3 — relays MUST NOT learn request contents).
 *
 * Intended for tests and local development. Use [url] to construct an
 * [io.github.gabrielhuff.ohttp.OhttpConfig].
 */
public class InProcessRelay @JvmOverloads constructor(
    public val gatewayUrl: HttpUrl,
    private val server: MockWebServer = MockWebServer(),
    private val forwardClient: OkHttpClient = OkHttpClient(),
) : Closeable {

    private val lastForwardedPayload = AtomicReference<ByteArray?>(null)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    public val url: HttpUrl
        get() = server.url("/ohttp")

    /** The most recent encapsulated request body forwarded upstream. Useful for assertions. */
    public fun lastForwardedRequestBytes(): ByteArray? = lastForwardedPayload.get()

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
                .setBody("expected ${Ohttp.REQUEST_MEDIA_TYPE}, got $contentType")
        }
        val body = request.body.readByteArray()
        lastForwardedPayload.set(body)
        val forward = Request.Builder()
            .url(gatewayUrl)
            .post(body.toRequestBody(REQUEST_MEDIA_TYPE))
            .header("Accept", Ohttp.RESPONSE_MEDIA_TYPE)
            .build()
        return try {
            forwardClient.newCall(forward).execute().use { upstream ->
                val responseBytes = upstream.body?.bytes() ?: ByteArray(0)
                MockResponse()
                    .setResponseCode(upstream.code)
                    .setHeader(
                        "Content-Type",
                        upstream.body?.contentType()?.toString() ?: Ohttp.RESPONSE_MEDIA_TYPE,
                    )
                    .setBody(okio.Buffer().write(responseBytes))
            }
        } catch (t: Throwable) {
            MockResponse().setResponseCode(502).setBody("relay forward failed: ${t.message}")
        }
    }

    private companion object {
        val REQUEST_MEDIA_TYPE = Ohttp.REQUEST_MEDIA_TYPE.toMediaType()
    }
}
