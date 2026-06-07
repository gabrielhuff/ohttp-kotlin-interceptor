package io.github.gabrielhuff.ohttp.okhttp

import io.github.gabrielhuff.ohttp.internal.BhttpRequest
import io.github.gabrielhuff.ohttp.internal.BhttpResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Adapters between OkHttp's [Request] / [Response] and the neutral BHTTP types in `:core`. */
internal object OkHttpBhttpAdapter {

    fun toBhttp(request: Request): BhttpRequest {
        // OkHttp tracks Content-Type / Content-Length on the body itself rather
        // than in `request.headers`, so synthesize them where missing.
        val bodyBytes = readBody(request)
        val syntheticHeaders = buildList {
            val body = request.body
            if (body != null) {
                val ct = body.contentType()
                if (ct != null && request.header("Content-Type") == null) add("Content-Type" to ct.toString())
                if (request.header("Content-Length") == null) add("Content-Length" to bodyBytes.size.toString())
            }
        }
        val headers = request.headers
            .let { h -> (0 until h.size).map { h.name(it) to h.value(it) } } + syntheticHeaders
        return BhttpRequest(
            method = request.method,
            scheme = request.url.scheme,
            authority = authorityOf(request.url),
            pathWithQuery = pathWithQueryOf(request.url),
            headers = headers,
            body = bodyBytes,
        )
    }

    fun toBhttp(response: Response): BhttpResponse {
        val bodyObj = response.body
        val body = bodyObj?.bytes() ?: ByteArray(0)
        val syntheticHeaders = buildList {
            if (bodyObj != null) {
                val ct = bodyObj.contentType()
                if (ct != null && response.header("Content-Type") == null) add("Content-Type" to ct.toString())
                if (response.header("Content-Length") == null) add("Content-Length" to body.size.toString())
            }
        }
        val headers = response.headers
            .let { h -> (0 until h.size).map { h.name(it) to h.value(it) } } + syntheticHeaders
        return BhttpResponse(response.code, headers, body)
    }

    /** Build an OkHttp [Request] from a decoded BHTTP request. Used on the gateway side. */
    fun fromBhttp(req: BhttpRequest): Request {
        val url = "${req.scheme}://${req.authority}${req.pathWithQuery}".toHttpUrl()
        val contentType = req.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second?.toMediaTypeOrNull()
        val requestBody = if (req.body.isNotEmpty() || methodAllowsBody(req.method)) {
            req.body.toRequestBody(contentType)
        } else null
        val builder = Request.Builder().url(url).method(req.method, requestBody)
        for ((name, value) in req.headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue // OkHttp sets this itself
            builder.addHeader(name, value)
        }
        if (req.headers.none { it.first.equals("Host", ignoreCase = true) }) {
            builder.header("Host", req.authority)
        }
        return builder.build()
    }

    /** Build an OkHttp [Response] from a decoded BHTTP response, anchored to [originalRequest]. */
    fun fromBhttp(
        originalRequest: Request,
        resp: BhttpResponse,
        protocol: Protocol = Protocol.HTTP_1_1,
    ): Response {
        val contentType = resp.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second?.toMediaTypeOrNull()
        val headersBuilder = okhttp3.Headers.Builder()
        for ((name, value) in resp.headers) headersBuilder.add(name, value)
        return Response.Builder()
            .request(originalRequest)
            .protocol(protocol)
            .code(resp.statusCode)
            .message(reasonPhrase(resp.statusCode))
            .headers(headersBuilder.build())
            .body(resp.body.toResponseBody(contentType))
            .build()
    }

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
