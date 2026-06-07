package io.github.gabrielhuff.ohttp.internal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

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
        val baos = ByteArrayOutputStream(estimatedSize(request))
        val out = DataOutputStream(baos)
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

        return baos.toByteArray()
    }

    fun decodeRequest(bytes: ByteArray): BhttpRequest {
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
        if (src.hasRemaining()) {
            val trailersLen = Varint.read(src).toInt()
            if (trailersLen > 0 && src.remaining() >= trailersLen) src.position(src.position() + trailersLen)
            // Any padding is discarded; we ignore the rest of the buffer.
        }
        return BhttpRequest(method, scheme, authority, path, headers, body)
    }

    // --- Response ---

    fun encodeResponse(response: BhttpResponse): ByteArray {
        val baos = ByteArrayOutputStream(64 + response.body.size)
        val out = DataOutputStream(baos)
        Varint.write(out, FRAMING_KNOWN_LENGTH_RESPONSE.toLong())
        // No informational responses emitted.
        Varint.write(out, response.statusCode.toLong())
        val fields = response.headers.filterNot { it.first.startsWith(":") }
        writeFieldSection(out, fields)
        Varint.write(out, response.body.size.toLong())
        out.write(response.body)
        // Trailers: empty.
        Varint.write(out, 0L)
        return baos.toByteArray()
    }

    fun decodeResponse(bytes: ByteArray): BhttpResponse {
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
        if (src.hasRemaining()) {
            val trailersLen = Varint.read(src).toInt()
            if (trailersLen > 0 && src.remaining() >= trailersLen) src.position(src.position() + trailersLen)
        }
        return BhttpResponse(status, headers, body)
    }

    // --- helpers ---

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

    private fun estimatedSize(request: BhttpRequest): Int {
        // Rough upper bound to avoid most reallocations during encoding.
        var n = 16 + request.method.length + request.scheme.length + request.authority.length + request.pathWithQuery.length
        for ((name, value) in request.headers) n += 4 + name.length + value.length
        n += request.body.size + 8
        return n
    }
}
