package io.github.gabrielhuff.ohttp.internal

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BhttpTest {

    @Test
    fun `GET request round-trips`() {
        val request = Request.Builder()
            .url("https://api.example.com/v1/things?id=42")
            .header("Accept", "application/json")
            .header("X-Trace", "abc-123")
            .build()
        val encoded = Bhttp.encodeRequest(request)
        val decoded = Bhttp.decodeRequest(encoded)
        assertEquals(request.method, decoded.method)
        assertEquals(request.url, decoded.url)
        assertEquals("application/json", decoded.header("Accept"))
        assertEquals("abc-123", decoded.header("X-Trace"))
    }

    @Test
    fun `POST with body round-trips`() {
        val payload = """{"hello":"world"}"""
        val request = Request.Builder()
            .url("https://api.example.com/v1/widgets")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val encoded = Bhttp.encodeRequest(request)
        val decoded = Bhttp.decodeRequest(encoded)
        assertEquals("POST", decoded.method)
        val buf = Buffer()
        decoded.body!!.writeTo(buf)
        assertEquals(payload, buf.readUtf8())
        assertEquals("application/json", decoded.body!!.contentType()!!.let { "${it.type}/${it.subtype}" })
    }

    @Test
    fun `non-default port preserved in authority`() {
        val request = Request.Builder().url("https://api.example.com:8443/foo").build()
        val encoded = Bhttp.encodeRequest(request)
        val decoded = Bhttp.decodeRequest(encoded)
        assertEquals("api.example.com", decoded.url.host)
        assertEquals(8443, decoded.url.port)
    }

    @Test
    fun `pseudo-headers in input are dropped`() {
        val request = Request.Builder()
            .url("https://x.example/y")
            .header(":authority", "should-not-be-here")
            .header("X-Real", "kept")
            .build()
        val encoded = Bhttp.encodeRequest(request)
        val decoded = Bhttp.decodeRequest(encoded)
        assertEquals(null, decoded.header(":authority"))
        assertEquals("kept", decoded.header("X-Real"))
    }

    @Test
    fun `response with body round-trips`() {
        val original = Request.Builder().url("https://x.example/y").build()
        val response = Response.Builder()
            .request(original)
            .protocol(Protocol.HTTP_1_1)
            .code(201)
            .message("Created")
            .header("Location", "/things/1")
            .body("hello".toResponseBody("text/plain".toMediaType()))
            .build()
        val encoded = Bhttp.encodeResponse(response)
        val decoded = Bhttp.decodeResponse(original, encoded)
        assertEquals(201, decoded.code)
        assertEquals("/things/1", decoded.header("Location"))
        assertEquals("hello", decoded.body!!.string())
        assertEquals("text/plain", decoded.body!!.contentType()!!.let { "${it.type}/${it.subtype}" })
    }

    @Test
    fun `framing indicator mismatch is rejected`() {
        // Build a known-length response and try to decode as request.
        val resp = Response.Builder()
            .request(Request.Builder().url("https://x.example/y").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(Headers.headersOf())
            .body("".toResponseBody(null))
            .build()
        val encoded = Bhttp.encodeResponse(resp)
        val ex = runCatching { Bhttp.decodeRequest(encoded) }.exceptionOrNull()
        assert(ex is IllegalArgumentException) { "expected IllegalArgumentException, got $ex" }
    }

    @Test
    fun `binary body bytes survive round-trip`() {
        // Cover the ISO-8859-1 byte path used for header / body bytes.
        val bytes = ByteArray(256) { it.toByte() }
        val request = Request.Builder()
            .url("https://x.example/raw")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val encoded = Bhttp.encodeRequest(request)
        val decoded = Bhttp.decodeRequest(encoded)
        val buf = Buffer()
        decoded.body!!.writeTo(buf)
        assertArrayEquals(bytes, buf.readByteArray())
    }
}
