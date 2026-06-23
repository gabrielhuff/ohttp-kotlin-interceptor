package io.github.gabrielhuff.ohttp.testing

import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * In-process key-configuration endpoint, the third leg of the OHTTP test
 * topology alongside [InProcessRelay] and [InProcessGateway]. It publishes a
 * gateway's current key configuration at the RFC 9540 §4.1 well-known URI
 * (`GET /.well-known/ohttp-gateway`, `application/ohttp-keys`).
 *
 * [keyConfigBytes] is a supplier rather than a fixed value so a rotating gateway
 * is reflected automatically — wire it to `{ gateway.keyConfigBytes }`.
 */
internal class InProcessKeyDistributor(
    private val keyConfigBytes: () -> ByteArray,
    private val server: MockWebServer = MockWebServer(),
) : Closeable {

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    val keyConfigUrl: HttpUrl
        get() = server.url("/.well-known/ohttp-gateway")

    override fun close() {
        server.shutdown()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.method != "GET" || request.path != "/.well-known/ohttp-gateway") {
            return MockResponse().setResponseCode(404)
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/ohttp-keys")
            .setBody(okio.Buffer().write(keyConfigBytes()))
    }
}
