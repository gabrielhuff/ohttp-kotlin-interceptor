package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
 * **Key management.** The gateway's key configuration is fetched from
 * [keyConfigUrl] (defaulting to the RFC 9540 §4.1 well-known endpoint derived
 * from [gatewayUrl]) using [keyConfigClient], and cached in memory. If
 * [defaultKeyConfigBytes] is supplied and parseable it seeds that cache so the
 * first request needs no round trip. When the gateway rotates keys, the first
 * affected request fails, the interceptor calls [refreshKey], and retries once.
 * Callers may also call [refreshKey] proactively (e.g. on app foreground).
 *
 * @param gatewayUrl identifies which requests to encapsulate (matched by host).
 * @param relayUrl where the encapsulated request is POSTed.
 * @param keyConfigUrl where the gateway's key configuration is fetched from.
 *   Defaults to `https://{gateway-host}/.well-known/ohttp-gateway`.
 * @param keyConfigClient the client used to fetch the key configuration. The
 *   default is a fresh, cache-less [OkHttpClient]; supply one backed by an
 *   [okhttp3.Cache] (e.g. on Android, with `context.cacheDir`) for persistence,
 *   or one routed through the relay for stronger metadata privacy.
 * @param defaultKeyConfigBytes optional initial key configuration (RFC 9458
 *   §3.1) to seed the cache. Unparseable bytes are ignored — the first request
 *   fetches instead.
 */
public class OhttpInterceptor @JvmOverloads constructor(
    private val gatewayUrl: HttpUrl,
    private val relayUrl: HttpUrl,
    keyConfigUrl: HttpUrl = wellKnownKeyConfigUrl(gatewayUrl),
    keyConfigClient: OkHttpClient = OkHttpClient(),
    defaultKeyConfigBytes: ByteArray? = null,
) : Interceptor {

    private val keys = KeyConfigManager(keyConfigUrl, keyConfigClient, defaultKeyConfigBytes)

    /**
     * Forces a refetch of the gateway's key configuration from `keyConfigUrl`.
     * Blocks on network I/O, so call it off the main thread. Throws
     * [OhttpKeyFetchException] if the fetch fails or [OhttpKeyParseException] if
     * the downloaded bytes are not a valid key configuration.
     */
    public fun refreshKey() {
        keys.refresh()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != gatewayUrl.host) return chain.proceed(original)

        val bhttpBytes = try {
            Bhttp.encodeRequest(original)
        } catch (e: Exception) {
            throw OhttpRequestEncodingException("failed to encode request as BHTTP", e)
        }

        var refreshed = false
        while (true) {
            val keyConfig = keys.get()
            val encapsulated = try {
                Ohttp.encapsulateRequest(keyConfig, bhttpBytes)
            } catch (e: Exception) {
                throw OhttpRequestEncodingException("failed to encapsulate request for gateway $gatewayUrl", e)
            }

            val relayRequest = Request.Builder()
                .url(relayUrl)
                .post(encapsulated.ciphertext.toRequestBody(REQUEST_CONTENT_TYPE))
                .header("Accept", Ohttp.RESPONSE_MEDIA_TYPE)
                .build()

            val relayResponse = chain.proceed(relayRequest)

            // A successful, correctly-typed response is the only thing we decode.
            // Origin-level errors (404, 500, …) arrive *inside* a valid ohttp-res
            // and flow through here normally — they are not relay/key failures.
            if (relayResponse.isSuccessful && isOhttpResponse(relayResponse)) {
                return relayResponse.use { decodeOhttpResponse(it, encapsulated.context, original) }
            }

            val code = relayResponse.code
            // A 4xx from the relay/gateway is read as a probable key rejection
            // (the gateway couldn't decapsulate). Anything else — 5xx, a wrong
            // content type, an empty body — is an unexpected transport response
            // that a key refresh would not fix.
            val likelyKeyRejection = !relayResponse.isSuccessful && code in 400..499
            relayResponse.close()

            if (likelyKeyRejection && !refreshed) {
                refreshed = true
                keys.refresh()
                continue
            }
            throw if (likelyKeyRejection) {
                OhttpKeyMismatchException(
                    "gateway $gatewayUrl rejected the encapsulated request (HTTP $code) even after a key refresh",
                    code,
                )
            } else {
                OhttpUnexpectedResponseException("OHTTP relay returned an unexpected response (HTTP $code)", code)
            }
        }
    }

    private fun decodeOhttpResponse(
        relayResponse: Response,
        context: Ohttp.ClientContext,
        original: Request,
    ): Response {
        val encapsulatedResponse = relayResponse.body?.bytes()
            ?: throw OhttpUnexpectedResponseException("OHTTP relay returned an empty body", relayResponse.code)
        val bhttpResponseBytes = try {
            Ohttp.decapsulateResponse(context, encapsulatedResponse)
        } catch (e: Exception) {
            throw OhttpDecapsulationException("failed to decapsulate OHTTP response", e)
        }
        return try {
            Bhttp.decodeResponse(bhttpResponseBytes, original)
        } catch (e: Exception) {
            throw OhttpDecapsulationException("failed to decode BHTTP response", e)
        }
    }

    private fun isOhttpResponse(response: Response): Boolean {
        val contentType = response.body?.contentType()?.toString() ?: return false
        return contentType.startsWith(Ohttp.RESPONSE_MEDIA_TYPE)
    }

    private companion object {
        val REQUEST_CONTENT_TYPE = Ohttp.REQUEST_MEDIA_TYPE.toMediaType()

        // RFC 9540 §4.1 — the gateway publishes its key configuration at the
        // well-known URI on its own origin.
        fun wellKnownKeyConfigUrl(gatewayUrl: HttpUrl): HttpUrl =
            gatewayUrl.newBuilder()
                .encodedPath("/.well-known/ohttp-gateway")
                .query(null)
                .fragment(null)
                .build()
    }
}
