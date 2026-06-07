package io.github.gabrielhuff.ohttp.internal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BhttpTest {

    @Test
    fun `GET request round-trips`() {
        val req = BhttpRequest(
            method = "GET",
            scheme = "https",
            authority = "api.example.com",
            pathWithQuery = "/v1/things?id=42",
            headers = listOf("Accept" to "application/json", "X-Trace" to "abc-123"),
            body = ByteArray(0),
        )
        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(req))
        assertEquals(req.method, decoded.method)
        assertEquals(req.scheme, decoded.scheme)
        assertEquals(req.authority, decoded.authority)
        assertEquals(req.pathWithQuery, decoded.pathWithQuery)
        assertEquals(req.headers, decoded.headers)
        assertArrayEquals(req.body, decoded.body)
    }

    @Test
    fun `POST with body round-trips`() {
        val payload = """{"hello":"world"}""".toByteArray()
        val req = BhttpRequest(
            method = "POST",
            scheme = "https",
            authority = "api.example.com",
            pathWithQuery = "/v1/widgets",
            headers = listOf("Content-Type" to "application/json"),
            body = payload,
        )
        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(req))
        assertArrayEquals(payload, decoded.body)
        assertEquals("application/json", decoded.headers.first { it.first == "Content-Type" }.second)
    }

    @Test
    fun `pseudo-headers and Host are dropped from request field section`() {
        val req = BhttpRequest(
            method = "GET",
            scheme = "https",
            authority = "x.example",
            pathWithQuery = "/y",
            headers = listOf(":authority" to "should-not-be-here", "Host" to "also-dropped", "X-Real" to "kept"),
            body = ByteArray(0),
        )
        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(req))
        assertTrue(decoded.headers.none { it.first == ":authority" })
        assertTrue(decoded.headers.none { it.first.equals("Host", ignoreCase = true) })
        assertEquals("kept", decoded.headers.first { it.first == "X-Real" }.second)
    }

    @Test
    fun `response round-trips`() {
        val resp = BhttpResponse(
            statusCode = 201,
            headers = listOf("Location" to "/things/1", "Content-Type" to "text/plain"),
            body = "hello".toByteArray(),
        )
        val decoded = Bhttp.decodeResponse(Bhttp.encodeResponse(resp))
        assertEquals(201, decoded.statusCode)
        assertEquals("/things/1", decoded.headers.first { it.first == "Location" }.second)
        assertArrayEquals("hello".toByteArray(), decoded.body)
    }

    @Test
    fun `framing indicator mismatch is rejected`() {
        val resp = BhttpResponse(200, emptyList(), ByteArray(0))
        val encoded = Bhttp.encodeResponse(resp)
        val ex = runCatching { Bhttp.decodeRequest(encoded) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException) { "expected IllegalArgumentException, got $ex" }
    }

    @Test
    fun `binary body bytes survive round-trip`() {
        val bytes = ByteArray(256) { it.toByte() }
        val req = BhttpRequest(
            method = "POST",
            scheme = "https",
            authority = "x.example",
            pathWithQuery = "/raw",
            headers = listOf("Content-Type" to "application/octet-stream"),
            body = bytes,
        )
        val decoded = Bhttp.decodeRequest(Bhttp.encodeRequest(req))
        assertArrayEquals(bytes, decoded.body)
    }
}
