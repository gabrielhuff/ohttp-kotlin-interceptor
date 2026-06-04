package io.github.gabrielhuff.ohttp.internal

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

// Binary HTTP Messages, RFC 9292 — known-length variant. We implement only the
// known-length framing (RFC 9458 §4 mandates known-length for OHTTP request
// and response messages).
internal object Bhttp {

    private const val FRAMING_KNOWN_LENGTH_REQUEST: Byte = 0
    private const val FRAMING_KNOWN_LENGTH_RESPONSE: Byte = 1

    // --- Request ---

    fun encodeRequest(request: Request): ByteArray {
        val out = Buffer()
        Varint.write(out, FRAMING_KNOWN_LENGTH_REQUEST.toLong())

        // Request Control Data
        val url = request.url
        writeLengthPrefixed(out, request.method.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, url.scheme.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, authorityOf(url).toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, pathWithQuery(url).toByteArray(Charsets.US_ASCII))

        // Field section (no pseudo-headers, no Host — those are in control data).
        // OkHttp tracks Content-Type / Content-Length on the body itself rather
        // than in `request.headers`, so synthesize them when present.
        val bodyBytes = readBody(request.body)
        val body = request.body
        val syntheticHeaders = buildList {
            if (body != null) {
                val ct = body.contentType()
                if (ct != null && request.header("Content-Type") == null) add("Content-Type" to ct.toString())
                if (request.header("Content-Length") == null) add("Content-Length" to bodyBytes.size.toString())
            }
        }
        writeFieldSection(out, request.headers.filterForBhttp(includeHost = false) + syntheticHeaders)

        // Known-length content.
        Varint.write(out, bodyBytes.size.toLong())
        out.write(bodyBytes)

        // Trailers: empty.
        Varint.write(out, 0L)

        return out.readByteArray()
    }

    fun decodeRequest(bytes: ByteArray, fallbackProtocol: Protocol = Protocol.HTTP_1_1): Request {
        val src = Buffer().write(bytes)
        val framing = Varint.read(src)
        require(framing == FRAMING_KNOWN_LENGTH_REQUEST.toLong()) {
            "expected known-length request framing, got $framing"
        }
        val method = readLengthPrefixedString(src)
        val scheme = readLengthPrefixedString(src)
        val authority = readLengthPrefixedString(src)
        val path = readLengthPrefixedString(src)
        val headers = readFieldSection(src)
        val contentLen = Varint.read(src).toInt()
        require(src.size >= contentLen) { "BHTTP request truncated in content section" }
        val body = src.readByteArray(contentLen.toLong())
        // Trailers
        if (!src.exhausted()) {
            val trailersLen = Varint.read(src)
            if (trailersLen > 0) src.skip(trailersLen)
            // Any padding is discarded.
            if (!src.exhausted()) src.clear()
        }

        val url = "$scheme://$authority$path".toHttpUrl()
        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        val builder = Request.Builder().url(url)
            .method(method, if (methodAllowsBody(method) || body.isNotEmpty()) body.toRequestBody(contentType) else null)
        for ((name, value) in headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue // OkHttp sets this itself
            builder.addHeader(name, value)
        }
        // Re-add Host since it's part of OkHttp's outgoing wire-format expectations.
        if (headers["Host"] == null) builder.header("Host", authority)
        return builder.build()
    }

    // --- Response ---

    fun encodeResponse(response: Response): ByteArray {
        val out = Buffer()
        Varint.write(out, FRAMING_KNOWN_LENGTH_RESPONSE.toLong())
        // No informational responses emitted.
        Varint.write(out, response.code.toLong())
        val bodyObj = response.body
        val body = bodyObj?.bytes() ?: ByteArray(0)
        val syntheticHeaders = buildList {
            if (bodyObj != null) {
                val ct = bodyObj.contentType()
                if (ct != null && response.header("Content-Type") == null) add("Content-Type" to ct.toString())
                if (response.header("Content-Length") == null) add("Content-Length" to body.size.toString())
            }
        }
        writeFieldSection(out, response.headers.filterForBhttp(includeHost = true) + syntheticHeaders)
        Varint.write(out, body.size.toLong())
        out.write(body)
        // Trailers (OkHttp Response.trailers() not synchronously available without I/O — skip)
        Varint.write(out, 0L)
        return out.readByteArray()
    }

    fun decodeResponse(originalRequest: Request, bytes: ByteArray, protocol: Protocol = Protocol.HTTP_1_1): Response {
        val src = Buffer().write(bytes)
        val framing = Varint.read(src)
        require(framing == FRAMING_KNOWN_LENGTH_RESPONSE.toLong()) {
            "expected known-length response framing, got $framing"
        }
        // Skip any informational (1xx) responses.
        var status: Int
        var headers: Headers
        while (true) {
            status = Varint.read(src).toInt()
            headers = readFieldSection(src)
            if (status >= 200) break
        }
        val contentLen = Varint.read(src).toInt()
        require(src.size >= contentLen) { "BHTTP response truncated in content section" }
        val body = src.readByteArray(contentLen.toLong())
        // Trailers / padding — best effort.
        if (!src.exhausted()) {
            val trailersLen = Varint.read(src)
            if (trailersLen > 0 && src.size >= trailersLen) src.skip(trailersLen)
            if (!src.exhausted()) src.clear()
        }

        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        return Response.Builder()
            .request(originalRequest)
            .protocol(protocol)
            .code(status)
            .message(reasonPhrase(status))
            .headers(headers)
            .body(body.toResponseBody(contentType))
            .build()
    }

    // --- helpers ---

    private fun authorityOf(url: okhttp3.HttpUrl): String {
        val defaultPort = (url.scheme == "https" && url.port == 443) ||
            (url.scheme == "http" && url.port == 80)
        return if (defaultPort) url.host else "${url.host}:${url.port}"
    }

    private fun pathWithQuery(url: okhttp3.HttpUrl): String {
        val q = url.encodedQuery
        return if (q.isNullOrEmpty()) url.encodedPath else "${url.encodedPath}?$q"
    }

    private fun writeLengthPrefixed(out: Buffer, bytes: ByteArray) {
        Varint.write(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun readLengthPrefixedString(src: Buffer): String {
        val len = Varint.read(src).toInt()
        return src.readByteArray(len.toLong()).toString(Charsets.US_ASCII)
    }

    private fun writeFieldSection(out: Buffer, fields: List<Pair<String, String>>) {
        // Serialize fields to a tmp buffer first to know the section length.
        val inner = Buffer()
        for ((name, value) in fields) {
            writeLengthPrefixed(inner, name.toByteArray(Charsets.US_ASCII))
            writeLengthPrefixed(inner, value.toByteArray(Charsets.ISO_8859_1))
        }
        Varint.write(out, inner.size)
        out.writeAll(inner)
    }

    private fun readFieldSection(src: Buffer): Headers {
        val len = Varint.read(src)
        require(src.size >= len) { "BHTTP field section truncated" }
        val inner = Buffer().write(src.readByteArray(len))
        val builder = Headers.Builder()
        while (!inner.exhausted()) {
            val name = readLengthPrefixedString(inner)
            val value = inner.readByteArray(Varint.read(inner)).toString(Charsets.ISO_8859_1)
            builder.add(name, value)
        }
        return builder.build()
    }

    private fun Headers.filterForBhttp(includeHost: Boolean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(size)
        for (i in 0 until size) {
            val name = name(i)
            // RFC 9292 §4.2 prohibits pseudo-headers in field sections.
            if (name.startsWith(":")) continue
            if (!includeHost && name.equals("Host", ignoreCase = true)) continue
            out.add(name to value(i))
        }
        return out
    }

    private fun readBody(body: RequestBody?): ByteArray {
        if (body == null) return ByteArray(0)
        val buf = Buffer()
        body.writeTo(buf)
        return buf.readByteArray()
    }

    private fun methodAllowsBody(method: String): Boolean =
        method !in setOf("GET", "HEAD", "DELETE")

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }
}
