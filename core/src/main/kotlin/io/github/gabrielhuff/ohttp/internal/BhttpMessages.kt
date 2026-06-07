package io.github.gabrielhuff.ohttp.internal

/**
 * Neutral (HTTP-stack-agnostic) representation of a BHTTP request as defined
 * in RFC 9292. Adapters in higher-level modules convert to/from their native
 * HTTP types (OkHttp [okhttp3.Request], Cronet `UrlRequest` builder state, …).
 *
 * Notes:
 *  - [authority] follows the request-target form used by control data
 *    (host, optionally with `:port`). Adapters strip default ports.
 *  - Pseudo-headers (names starting with `:`) MUST NOT appear in [headers];
 *    RFC 9292 §4.2 prohibits them in field sections.
 *  - [body] is the full known-length content. Streaming bodies must be
 *    buffered by the caller (OHTTP is one-shot).
 */
internal data class BhttpRequest(
    val method: String,
    val scheme: String,
    val authority: String,
    val pathWithQuery: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

/** Neutral representation of a BHTTP response (RFC 9292). */
internal data class BhttpResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)
