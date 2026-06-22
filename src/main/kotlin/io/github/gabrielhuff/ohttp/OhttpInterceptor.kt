package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp [Interceptor] that turns regular HTTP(S) calls to a single gateway
 * host into Oblivious HTTP exchanges (RFC 9458). Requests whose host does not
 * match [gatewayUrl] are passed through unchanged. To proxy more than one
 * gateway, install one interceptor per gateway.
 *
 * For each matching request, the interceptor:
 *  1. Serializes the [Request] to a BHTTP message (RFC 9292).
 *  2. Encapsulates the BHTTP bytes with HPKE using the gateway's public key.
 *  3. POSTs the encapsulated payload to [relayUrl] via `chain.proceed`, so the
 *     relay leg reuses the same client (connection pool, timeouts, proxy).
 *  4. Decapsulates the response and reconstructs an OkHttp [Response].
 *
 * Install it as an **application** interceptor so the original [Request]'s URL
 * host is observable. The relay request flows through `chain.proceed`, which
 * descends to later interceptors and the network — it never re-enters this
 * interceptor, so there is no risk of recursively encapsulating it.
 *
 * @param gatewayUrl identifies which requests to encapsulate (matched by host).
 * @param relayUrl where the encapsulated request is POSTed.
 * @param keyConfigBytes the gateway's published key configuration (RFC 9458
 *   §3.1), as opaque bytes. Parsed on first use; if `null` (or unparseable) the
 *   interceptor throws when it first needs to encapsulate a request.
 */
public class OhttpInterceptor @JvmOverloads constructor(
    private val gatewayUrl: HttpUrl,
    private val relayUrl: HttpUrl,
    keyConfigBytes: ByteArray? = null,
) : Interceptor {

    // Parsed lazily so a future key-config discovery path can do I/O on the
    // first intercepted request. For now a missing config is treated like an
    // unparseable one — it throws the first time the gateway is used.
    private val keyConfig: Ohttp.KeyConfig by lazy {
        val bytes = keyConfigBytes
            ?: throw IllegalArgumentException("no key configuration provided for gateway $gatewayUrl")
        Ohttp.KeyConfig.parse(bytes)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != gatewayUrl.host) return chain.proceed(original)

        val bhttpBytes = Bhttp.encodeRequest(original)
        val encapsulated = Ohttp.encapsulateRequest(keyConfig, bhttpBytes)

        val relayRequest = Request.Builder()
            .url(relayUrl)
            .post(encapsulated.ciphertext.toRequestBody(REQUEST_CONTENT_TYPE))
            .header("Accept", Ohttp.RESPONSE_MEDIA_TYPE)
            .build()

        chain.proceed(relayRequest).use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("OHTTP relay returned HTTP ${resp.code} ${resp.message}")
            }
            val responseContentType = resp.body?.contentType()?.toString()
            if (responseContentType != null && !responseContentType.startsWith(Ohttp.RESPONSE_MEDIA_TYPE)) {
                throw IOException("OHTTP relay returned wrong content type: $responseContentType")
            }
            val encapsulatedResponse = resp.body?.bytes()
                ?: throw IOException("OHTTP relay returned empty body")
            val bhttpResponseBytes = try {
                Ohttp.decapsulateResponse(encapsulated.context, encapsulatedResponse)
            } catch (e: Exception) {
                throw IOException("failed to decapsulate OHTTP response", e)
            }
            return Bhttp.decodeResponse(bhttpResponseBytes, original)
        }
    }

    private companion object {
        val REQUEST_CONTENT_TYPE = Ohttp.REQUEST_MEDIA_TYPE.toMediaType()
    }
}
