package io.github.gabrielhuff.ohttp.internal

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Binary HTTP Messages, RFC 9292 — known-length variant only. RFC 9458 §4
 * mandates known-length for OHTTP request and response messages, so we
 * intentionally don't implement the indeterminate-length variant.
 *
 * This object translates OkHttp's [Request] / [Response] straight to and from
 * the BHTTP wire format. The client path (`encodeRequest` / `decodeResponse`)
 * runs in [io.github.gabrielhuff.ohttp.OhttpInterceptor]; the gateway path
 * (`decodeRequest` / `encodeResponse`) is exercised by the in-process test
 * gateway.
 */
internal object Bhttp {

    private const val FRAMING_KNOWN_LENGTH_REQUEST: Byte = 0
    private const val FRAMING_KNOWN_LENGTH_RESPONSE: Byte = 1

    // --- Request: OkHttp -> BHTTP (client side) ---

    fun encodeRequest(request: Request): ByteArray {
        val body = readBody(request)
        val baos = ByteArrayOutputStream(estimatedSize(request, body))
        val out = DataOutputStream(baos)
        Varint.write(out, FRAMING_KNOWN_LENGTH_REQUEST.toLong())

        // Request Control Data.
        writeLengthPrefixed(out, request.method.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.url.scheme.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, authorityOf(request.url).toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, pathWithQueryOf(request.url).toByteArray(Charsets.US_ASCII))

        writeFieldSection(out, requestFields(request, body))

        // Known-length content.
        Varint.write(out, body.size.toLong())
        out.write(body)

        // Trailers: empty.
        Varint.write(out, 0L)

        return baos.toByteArray()
    }

    // --- Request: BHTTP -> OkHttp (gateway side) ---

    fun decodeRequest(bytes: ByteArray): Request {
        val src = ByteBuffer.wrap(bytes)
        val framing = Varint.read(src)
        require(framing == FRAMING_KNOWN_LENGTH_REQUEST.toLong()) {
            "expected known-length request framing, got $framing"
        }
        val method = readLengthPrefixedAscii(src)
        val scheme = readLengthPrefixedAscii(src)
        val authority = readLengthPrefixedAscii(src)
        val path = readLengthPrefixedAscii(src)
        val headers = readFieldSection(src)
        val contentLen = Varint.read(src).toInt()
        require(src.remaining() >= contentLen) { "BHTTP request truncated in content section" }
        val body = ByteArray(contentLen)
        src.get(body)
        // Trailers and any padding are ignored.

        val url = "$scheme://$authority$path".toHttpUrl()
        val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second?.toMediaTypeOrNull()
        val requestBody = if (body.isNotEmpty() || methodAllowsBody(method)) {
            body.toRequestBody(contentType)
        } else null
        val builder = Request.Builder().url(url).method(method, requestBody)
        for ((name, value) in headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue // OkHttp sets this itself
            builder.addHeader(name, value)
        }
        if (headers.none { it.first.equals("Host", ignoreCase = true) }) {
            builder.header("Host", authority)
        }
        return builder.build()
    }

    // --- Response: OkHttp -> BHTTP (gateway side) ---

    fun encodeResponse(response: Response): ByteArray {
        val bodyObj = response.body
        val body = bodyObj?.bytes() ?: ByteArray(0)
        val baos = ByteArrayOutputStream(64 + body.size)
        val out = DataOutputStream(baos)
        Varint.write(out, FRAMING_KNOWN_LENGTH_RESPONSE.toLong())
        // No informational responses emitted.
        Varint.write(out, response.code.toLong())
        writeFieldSection(out, responseFields(response, bodyObj?.contentType()?.toString(), body.size))
        Varint.write(out, body.size.toLong())
        out.write(body)
        // Trailers: empty.
        Varint.write(out, 0L)
        return baos.toByteArray()
    }

    // --- Response: BHTTP -> OkHttp (client side) ---

    fun decodeResponse(
        bytes: ByteArray,
        originalRequest: Request,
        protocol: Protocol = Protocol.HTTP_1_1,
    ): Response {
        val src = ByteBuffer.wrap(bytes)
        val framing = Varint.read(src)
        require(framing == FRAMING_KNOWN_LENGTH_RESPONSE.toLong()) {
            "expected known-length response framing, got $framing"
        }
        var status: Int
        var headers: List<Pair<String, String>>
        // Skip any informational (1xx) responses.
        while (true) {
            status = Varint.read(src).toInt()
            headers = readFieldSection(src)
            if (status >= 200) break
        }
        val contentLen = Varint.read(src).toInt()
        require(src.remaining() >= contentLen) { "BHTTP response truncated in content section" }
        val body = ByteArray(contentLen)
        src.get(body)
        // Trailers and any padding are ignored.

        val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second?.toMediaTypeOrNull()
        val headersBuilder = Headers.Builder()
        for ((name, value) in headers) headersBuilder.add(name, value)
        return Response.Builder()
            .request(originalRequest)
            .protocol(protocol)
            .code(status)
            .message(reasonPhrase(status))
            .headers(headersBuilder.build())
            .body(body.toResponseBody(contentType))
            .build()
    }

    // --- OkHttp <-> field section glue ---

    // OkHttp tracks Content-Type / Content-Length on the body rather than in
    // `request.headers`, so synthesize them where missing. Pseudo-headers and
    // Host belong in control data (RFC 9292 §4.2), not the field section.
    private fun requestFields(request: Request, body: ByteArray): List<Pair<String, String>> {
        val explicit = request.headers.toPairs()
        val synthetic = buildList {
            val b = request.body
            if (b != null) {
                val ct = b.contentType()
                if (ct != null && request.header("Content-Type") == null) add("Content-Type" to ct.toString())
                if (request.header("Content-Length") == null) add("Content-Length" to body.size.toString())
            }
        }
        return (explicit + synthetic).filterNot {
            it.first.startsWith(":") || it.first.equals("Host", ignoreCase = true)
        }
    }

    private fun responseFields(response: Response, bodyContentType: String?, bodySize: Int): List<Pair<String, String>> {
        val explicit = response.headers.toPairs()
        val synthetic = buildList {
            if (bodyContentType != null && response.header("Content-Type") == null) {
                add("Content-Type" to bodyContentType)
            }
            if (response.header("Content-Length") == null) add("Content-Length" to bodySize.toString())
        }
        return (explicit + synthetic).filterNot { it.first.startsWith(":") }
    }

    private fun Headers.toPairs(): List<Pair<String, String>> =
        (0 until size).map { name(it) to value(it) }

    // --- BHTTP framing helpers ---

    private fun writeLengthPrefixed(out: DataOutputStream, bytes: ByteArray) {
        Varint.write(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun readLengthPrefixedAscii(src: ByteBuffer): String {
        val len = Varint.read(src).toInt()
        val arr = ByteArray(len)
        src.get(arr)
        return arr.toString(Charsets.US_ASCII)
    }

    private fun writeFieldSection(out: DataOutputStream, fields: List<Pair<String, String>>) {
        // Serialize to an inner buffer first so we can prefix the section
        // with its total length (a varint).
        val inner = ByteArrayOutputStream(64)
        val innerOut = DataOutputStream(inner)
        for ((name, value) in fields) {
            writeLengthPrefixed(innerOut, name.toByteArray(Charsets.US_ASCII))
            writeLengthPrefixed(innerOut, value.toByteArray(Charsets.ISO_8859_1))
        }
        Varint.write(out, inner.size().toLong())
        inner.writeTo(out)
    }

    private fun readFieldSection(src: ByteBuffer): List<Pair<String, String>> {
        val len = Varint.read(src).toInt()
        require(src.remaining() >= len) { "BHTTP field section truncated" }
        // Slice off the section into its own view so length-prefixed reads
        // inside the section can't bleed past it.
        val inner = src.slice().limit(len)
        src.position(src.position() + len)
        val out = ArrayList<Pair<String, String>>()
        while (inner.hasRemaining()) {
            val nameLen = Varint.read(inner).toInt()
            val nameBytes = ByteArray(nameLen)
            inner.get(nameBytes)
            val valueLen = Varint.read(inner).toInt()
            val valueBytes = ByteArray(valueLen)
            inner.get(valueBytes)
            out.add(nameBytes.toString(Charsets.US_ASCII) to valueBytes.toString(Charsets.ISO_8859_1))
        }
        return out
    }

    // --- OkHttp URL / body helpers ---

    private fun authorityOf(url: HttpUrl): String {
        val defaultPort = (url.scheme == "https" && url.port == 443) ||
            (url.scheme == "http" && url.port == 80)
        return if (defaultPort) url.host else "${url.host}:${url.port}"
    }

    private fun pathWithQueryOf(url: HttpUrl): String {
        val q = url.encodedQuery
        return if (q.isNullOrEmpty()) url.encodedPath else "${url.encodedPath}?$q"
    }

    private fun readBody(request: Request): ByteArray {
        val body = request.body ?: return ByteArray(0)
        val buf = Buffer()
        body.writeTo(buf)
        return buf.readByteArray()
    }

    private fun methodAllowsBody(method: String): Boolean = method !in setOf("GET", "HEAD", "DELETE")

    private fun estimatedSize(request: Request, body: ByteArray): Int {
        var n = 16 + request.method.length + request.url.scheme.length +
            request.url.host.length + request.url.encodedPath.length
        for (i in 0 until request.headers.size) n += 4 + request.headers.name(i).length + request.headers.value(i).length
        n += body.size + 8
        return n
    }

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
