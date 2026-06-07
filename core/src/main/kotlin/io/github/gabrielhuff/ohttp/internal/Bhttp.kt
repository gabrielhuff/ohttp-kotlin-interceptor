package io.github.gabrielhuff.ohttp.internal

import okio.Buffer

/**
 * Binary HTTP Messages, RFC 9292 — known-length variant only. RFC 9458 §4
 * mandates known-length for OHTTP request and response messages, so we
 * intentionally don't implement the indeterminate-length variant.
 *
 * Operates on the neutral [BhttpRequest] / [BhttpResponse] types so this
 * module stays free of any specific HTTP client API. Adapters in
 * `:interceptor` and `:cronet` translate to/from their native types.
 */
internal object Bhttp {

    private const val FRAMING_KNOWN_LENGTH_REQUEST: Byte = 0
    private const val FRAMING_KNOWN_LENGTH_RESPONSE: Byte = 1

    // --- Request ---

    fun encodeRequest(request: BhttpRequest): ByteArray {
        val out = Buffer()
        Varint.write(out, FRAMING_KNOWN_LENGTH_REQUEST.toLong())

        // Request Control Data
        writeLengthPrefixed(out, request.method.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.scheme.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.authority.toByteArray(Charsets.US_ASCII))
        writeLengthPrefixed(out, request.pathWithQuery.toByteArray(Charsets.US_ASCII))

        // Field section (filter out pseudo-headers and Host — both belong in control data).
        val fields = request.headers.filterNot { it.first.startsWith(":") || it.first.equals("Host", ignoreCase = true) }
        writeFieldSection(out, fields)

        // Known-length content.
        Varint.write(out, request.body.size.toLong())
        out.write(request.body)

        // Trailers: empty.
        Varint.write(out, 0L)

        return out.readByteArray()
    }

    fun decodeRequest(bytes: ByteArray): BhttpRequest {
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
        if (!src.exhausted()) {
            val trailersLen = Varint.read(src)
            if (trailersLen > 0) src.skip(trailersLen)
            if (!src.exhausted()) src.clear()
        }
        return BhttpRequest(method, scheme, authority, path, headers, body)
    }

    // --- Response ---

    fun encodeResponse(response: BhttpResponse): ByteArray {
        val out = Buffer()
        Varint.write(out, FRAMING_KNOWN_LENGTH_RESPONSE.toLong())
        // No informational responses emitted.
        Varint.write(out, response.statusCode.toLong())
        val fields = response.headers.filterNot { it.first.startsWith(":") }
        writeFieldSection(out, fields)
        Varint.write(out, response.body.size.toLong())
        out.write(response.body)
        // Trailers: empty.
        Varint.write(out, 0L)
        return out.readByteArray()
    }

    fun decodeResponse(bytes: ByteArray): BhttpResponse {
        val src = Buffer().write(bytes)
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
        require(src.size >= contentLen) { "BHTTP response truncated in content section" }
        val body = src.readByteArray(contentLen.toLong())
        if (!src.exhausted()) {
            val trailersLen = Varint.read(src)
            if (trailersLen > 0 && src.size >= trailersLen) src.skip(trailersLen)
            if (!src.exhausted()) src.clear()
        }
        return BhttpResponse(status, headers, body)
    }

    // --- helpers ---

    private fun writeLengthPrefixed(out: Buffer, bytes: ByteArray) {
        Varint.write(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun readLengthPrefixedString(src: Buffer): String {
        val len = Varint.read(src).toInt()
        return src.readByteArray(len.toLong()).toString(Charsets.US_ASCII)
    }

    private fun writeFieldSection(out: Buffer, fields: List<Pair<String, String>>) {
        val inner = Buffer()
        for ((name, value) in fields) {
            writeLengthPrefixed(inner, name.toByteArray(Charsets.US_ASCII))
            writeLengthPrefixed(inner, value.toByteArray(Charsets.ISO_8859_1))
        }
        Varint.write(out, inner.size)
        out.writeAll(inner)
    }

    private fun readFieldSection(src: Buffer): List<Pair<String, String>> {
        val len = Varint.read(src)
        require(src.size >= len) { "BHTTP field section truncated" }
        val inner = Buffer().write(src.readByteArray(len))
        val out = ArrayList<Pair<String, String>>()
        while (!inner.exhausted()) {
            val name = readLengthPrefixedString(inner)
            val value = inner.readByteArray(Varint.read(inner)).toString(Charsets.ISO_8859_1)
            out.add(name to value)
        }
        return out
    }
}
