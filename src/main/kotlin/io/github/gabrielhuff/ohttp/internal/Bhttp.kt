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
import java.nio.ByteBuffer

/**
 * Binary HTTP Messages (RFC 9292) — known-length variant only. RFC 9458 §4
 * mandates known-length for OHTTP request and response messages, so the
 * indeterminate-length variant is intentionally not implemented. Translates
 * OkHttp [Request] / [Response] straight to and from the BHTTP wire format.
 *
 * API:
 * - [encodeRequest] / [decodeResponse] — client side: [Request] → BHTTP, BHTTP → [Response]
 * - [decodeRequest] / [encodeResponse] — gateway side: BHTTP → [Request], [Response] → BHTTP
 *
 * Used by [Ohttp], which frames messages here before/after HPKE.
 */
internal object Bhttp {

    private const val FRAMING_KNOWN_LENGTH_REQUEST: Byte = 0
    private const val FRAMING_KNOWN_LENGTH_RESPONSE: Byte = 1

    // --- Request: OkHttp -> BHTTP (client side) ---

    fun encodeRequest(request: Request): ByteArray {
        val body = readBody(request)
        val method = request.method.toByteArray(Charsets.US_ASCII)
        val scheme = request.url.scheme.toByteArray(Charsets.US_ASCII)
        val authority = authorityOf(request.url).toByteArray(Charsets.US_ASCII)
        val path = pathWithQueryOf(request.url).toByteArray(Charsets.US_ASCII)
        val fieldSection = encodeFieldSection(requestFields(request, body))

        val buf = ByteBuffer.allocate(
            Varint.MAX_BYTES +                      // framing
                lengthPrefixed(method) + lengthPrefixed(scheme) +
                lengthPrefixed(authority) + lengthPrefixed(path) +
                lengthPrefixed(fieldSection) +      // field section
                lengthPrefixed(body) +              // known-length content
                Varint.MAX_BYTES,                   // trailers
        )
        Varint.write(buf, FRAMING_KNOWN_LENGTH_REQUEST.toLong())
        // Request Control Data.
        writeLengthPrefixed(buf, method)
        writeLengthPrefixed(buf, scheme)
        writeLengthPrefixed(buf, authority)
        writeLengthPrefixed(buf, path)
        writeLengthPrefixed(buf, fieldSection)
        writeLengthPrefixed(buf, body)
        Varint.write(buf, 0L) // empty trailers
        return buf.toByteArray()
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
        val headers = readFieldSectionOrEmpty(src)
        val body = readContentOrEmpty(src)
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
        val fieldSection = encodeFieldSection(
            responseFields(response, bodyObj?.contentType()?.toString(), body.size),
        )

        val buf = ByteBuffer.allocate(
            Varint.MAX_BYTES +                  // framing
                Varint.MAX_BYTES +              // status code
                lengthPrefixed(fieldSection) +  // field section
                lengthPrefixed(body) +          // known-length content
                Varint.MAX_BYTES,               // trailers
        )
        Varint.write(buf, FRAMING_KNOWN_LENGTH_RESPONSE.toLong())
        // No informational responses emitted.
        Varint.write(buf, response.code.toLong())
        writeLengthPrefixed(buf, fieldSection)
        writeLengthPrefixed(buf, body)
        Varint.write(buf, 0L) // empty trailers
        return buf.toByteArray()
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
            headers = readFieldSectionOrEmpty(src)
            if (status >= 200) break
        }
        val body = readContentOrEmpty(src)
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

    // --- BHTTP field section framing ---

    // A field section is itself length-prefixed, so we encode it to its own
    // byte array first and let the caller write the outer length prefix.
    private fun encodeFieldSection(fields: List<Pair<String, String>>): ByteArray {
        val encoded = fields.map { (name, value) ->
            name.toByteArray(Charsets.US_ASCII) to value.toByteArray(Charsets.ISO_8859_1)
        }
        val capacity = encoded.sumOf { (name, value) -> lengthPrefixed(name) + lengthPrefixed(value) }
        val buf = ByteBuffer.allocate(capacity)
        for ((name, value) in encoded) {
            writeLengthPrefixed(buf, name)
            writeLengthPrefixed(buf, value)
        }
        return buf.toByteArray()
    }

    // RFC 9292 §3.8 allows trailing zero-length sections to be omitted, so an
    // exhausted buffer here means the remaining sections are empty. (The Appendix A
    // examples in RFC 9458 rely on this: a GET ends after the path, a 200 after the
    // status code.)
    private fun readFieldSectionOrEmpty(src: ByteBuffer): List<Pair<String, String>> =
        if (src.hasRemaining()) readFieldSection(src) else emptyList()

    private fun readContentOrEmpty(src: ByteBuffer): ByteArray =
        if (src.hasRemaining()) readContent(src) else ByteArray(0)

    private fun readFieldSection(src: ByteBuffer): List<Pair<String, String>> {
        val len = Varint.read(src).toInt()
        require(src.remaining() >= len) { "BHTTP field section truncated" }
        // Slice off the section into its own view so length-prefixed reads
        // inside the section can't bleed past it.
        val inner = src.slice().limit(len)
        src.position(src.position() + len)
        val out = ArrayList<Pair<String, String>>()
        while (inner.hasRemaining()) {
            val name = readLengthPrefixed(inner)
            val value = readLengthPrefixed(inner)
            out.add(name.toString(Charsets.US_ASCII) to value.toString(Charsets.ISO_8859_1))
        }
        return out
    }

    // --- varint length-prefixed primitives ---

    /** Upper bound on the encoded size of a length-prefixed byte string. */
    private fun lengthPrefixed(bytes: ByteArray): Int = Varint.MAX_BYTES + bytes.size

    private fun writeLengthPrefixed(buf: ByteBuffer, bytes: ByteArray) {
        Varint.write(buf, bytes.size.toLong())
        buf.put(bytes)
    }

    private fun readLengthPrefixed(src: ByteBuffer): ByteArray {
        val len = Varint.read(src).toInt()
        val arr = ByteArray(len)
        src.get(arr)
        return arr
    }

    private fun readLengthPrefixedAscii(src: ByteBuffer): String =
        readLengthPrefixed(src).toString(Charsets.US_ASCII)

    private fun readContent(src: ByteBuffer): ByteArray {
        val len = Varint.read(src).toInt()
        require(src.remaining() >= len) { "BHTTP message truncated in content section" }
        val body = ByteArray(len)
        src.get(body)
        return body
    }

    private fun ByteBuffer.toByteArray(): ByteArray = array().copyOf(position())

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
