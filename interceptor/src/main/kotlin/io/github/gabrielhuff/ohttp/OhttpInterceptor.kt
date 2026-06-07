package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.Ohttp
import io.github.gabrielhuff.ohttp.okhttp.OkHttpBhttpAdapter
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp [Interceptor] that turns regular HTTP(S) calls to configured target
 * hosts into Oblivious HTTP exchanges (RFC 9458). Requests to hosts that are
 * not in [configs] are passed through unchanged.
 *
 * For each match, the interceptor:
 *  1. Translates the [Request] to a neutral BHTTP message (`:core`'s
 *     [io.github.gabrielhuff.ohttp.internal.BhttpRequest]) and serializes it
 *     per RFC 9292.
 *  2. Encapsulates the BHTTP bytes with HPKE using the gateway's public key.
 *  3. POSTs the encapsulated payload to the relay URL configured for that host.
 *  4. Decapsulates the response and reconstructs an OkHttp [Response].
 *
 * Register it as an **application** interceptor so the original [Request]'s
 * URL host is observable. Do not register the same interceptor on the client
 * passed as [relayClient], or the relay request would be re-intercepted.
 */
public class OhttpInterceptor @JvmOverloads constructor(
    configs: Map<String, OhttpConfig>,
    private val relayClient: OkHttpClient = OkHttpClient(),
) : Interceptor {

    private val configs: Map<String, OhttpConfig> = configs.toMap()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val config = configs[original.url.host] ?: return chain.proceed(original)

        val bhttpRequest = OkHttpBhttpAdapter.toBhttp(original)
        val bhttpBytes = Bhttp.encodeRequest(bhttpRequest)
        val encapsulated = Ohttp.encapsulateRequest(config.keyConfig, bhttpBytes)

        val relayRequest = Request.Builder()
            .url(config.relayUrl)
            .post(encapsulated.ciphertext.toRequestBody(REQUEST_CONTENT_TYPE))
            .header("Accept", Ohttp.RESPONSE_MEDIA_TYPE)
            .build()

        val relayResponse = relayClient.newCall(relayRequest).execute()

        relayResponse.use { resp ->
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
            val decoded = Bhttp.decodeResponse(bhttpResponseBytes)
            return OkHttpBhttpAdapter.fromBhttp(original, decoded)
        }
    }

    private companion object {
        val REQUEST_CONTENT_TYPE = Ohttp.REQUEST_MEDIA_TYPE.toMediaType()
    }
}
