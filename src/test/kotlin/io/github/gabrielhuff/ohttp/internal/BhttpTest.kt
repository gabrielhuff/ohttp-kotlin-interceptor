@file:OptIn(ExperimentalStdlibApi::class) // String.hexToByteArray()

package io.github.gabrielhuff.ohttp.internal

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class BhttpTest {

    private fun Request.bodyUtf8(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `GET round-trips method, non-default port, path, query and headers`() {
        val request = Request.Builder()
            .url("http://example.com:8080/p?q=1&r=2")
            .header("X-App", "test")
            .header("Accept", "application/json")
            .build()

        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(request))

        assertEquals("GET", decoded.method)
        assertEquals("example.com", decoded.url.host)
        assertEquals(8080, decoded.url.port)
        assertEquals("/p", decoded.url.encodedPath)
        assertEquals("q=1&r=2", decoded.url.encodedQuery)
        assertEquals("test", decoded.header("X-App"))
        assertEquals("application/json", decoded.header("Accept"))
        assertNull(decoded.body) // body-less method
    }

    @Test
    fun `POST round-trips a body, its content type and the default https port`() {
        val request = Request.Builder()
            .url("https://api.example.com/v1/x")
            .post("payload".toRequestBody("application/json".toMediaType()))
            .header("X-App", "test")
            .build()

        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(request))

        assertEquals("POST", decoded.method)
        assertEquals(443, decoded.url.port)
        assertEquals("/v1/x", decoded.url.encodedPath)
        assertEquals("test", decoded.header("X-App"))
        assertEquals("payload", decoded.bodyUtf8())
        assertEquals("application/json; charset=utf-8", decoded.body!!.contentType().toString())
    }

    @Test
    fun `DELETE with no meaningful body decodes to a body-less request`() {
        val request = Request.Builder().url("https://api.example.com/x").delete().build()
        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(request))
        assertEquals("DELETE", decoded.method)
        assertNull(decoded.body)
    }

    @Test
    fun `response round-trips status, headers and body`() {
        val original = Request.Builder().url("https://api.example.com/v1/x").build()
        val response = Response.Builder()
            .request(original)
            .protocol(Protocol.HTTP_1_1)
            .code(201)
            .message("Created")
            .header("X-Foo", "bar")
            .header("X-Baz", "qux")
            .body("hello".toResponseBody("text/plain".toMediaType()))
            .build()

        val decoded = Bhttp.decodeResponse(Bhttp.encodeResponse(response), original)

        assertEquals(201, decoded.code)
        assertEquals("bar", decoded.header("X-Foo"))
        assertEquals("qux", decoded.header("X-Baz"))
        assertEquals("hello", decoded.body!!.string())
        assertTrue(decoded.body!!.contentType().toString().startsWith("text/plain"))
    }

    @Test
    fun `decodeRequest accepts a message that omits trailing empty sections`() {
        // RFC 9458 Appendix A request: framing + GET https example.com / — no
        // field section, content, or trailers (RFC 9292 §3.8).
        val decoded = Bhttp.decodeRequest("00034745540568747470730b6578616d706c652e636f6d012f".hexToByteArray())
        assertEquals("GET", decoded.method)
        assertEquals("https", decoded.url.scheme)
        assertEquals("example.com", decoded.url.host)
        assertEquals("/", decoded.url.encodedPath)
        assertNull(decoded.body)
    }

    @Test
    fun `decodeResponse accepts a status-only message`() {
        val original = Request.Builder().url("https://example.com/").build()
        // RFC 9458 Appendix A response: framing + 200, nothing else.
        val decoded = Bhttp.decodeResponse("0140c8".hexToByteArray(), original)
        assertEquals(200, decoded.code)
        assertEquals("", decoded.body!!.string())
    }

    @Test
    fun `decodeRequest rejects non-request framing`() {
        // 0x01 is the known-length *response* framing indicator.
        assertThrows<IllegalArgumentException> { Bhttp.decodeRequest(byteArrayOf(0x01)) }
    }

    @Test
    fun `decodeResponse rejects non-response framing`() {
        val original = Request.Builder().url("https://api.example.com/x").build()
        // 0x00 is the known-length *request* framing indicator.
        assertThrows<IllegalArgumentException> { Bhttp.decodeResponse(byteArrayOf(0x00), original) }
    }
}
