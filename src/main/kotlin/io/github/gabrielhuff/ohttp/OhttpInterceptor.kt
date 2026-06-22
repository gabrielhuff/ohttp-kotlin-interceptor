package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.KeyConfigManager
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * OkHttp [Interceptor] that turns regular HTTP(S) calls to a single gateway
 * host into Oblivious HTTP exchanges (RFC 9458). Requests whose host does not
 * match [gatewayUrl] are passed through unchanged. To proxy more than one
 * gateway, install one interceptor per gateway.
 *
 * For each matching request, the interceptor serializes it to BHTTP (RFC 9292),
 * encapsulates it with HPKE using the gateway's public key, POSTs the result to
 * [relayUrl] via `chain.proceed` (so the relay leg reuses the same client), and
 * decapsulates the response. The BHTTP/HPKE work lives in
 * [io.github.gabrielhuff.ohttp.internal.Ohttp]; this class is just the chain
 * wiring and the key-rotation retry.
 *
 * Install it as an **application** interceptor so the original request's URL
 * host is observable. The relay request flows through `chain.proceed`, which
 * descends to later interceptors and the network — it never re-enters this
 * interceptor, so there is no risk of recursively encapsulating it.
 *
 * **Key management.** The gateway's key configuration is fetched from
 * [keyConfigUrl] (defaulting to the RFC 9540 §4.1 well-known endpoint derived
 * from [gatewayUrl]) using [keyConfigClient], and cached in memory. If
 * [defaultKeyConfigBytes] is supplied and parseable it seeds that cache so the
 * first request needs no round trip. When the gateway rotates keys, the first
 * affected request is rejected, the interceptor refreshes the key, and retries
 * once. Callers may also call [refreshKey] proactively (e.g. on app foreground).
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

        return try {
            interceptOhttp(chain)
        } catch (e: OhttpKeyMismatchException) {
            // The key was outdated/unregistered: refresh and retry exactly once.
            // A second mismatch propagates as the terminal failure.
            keys.refresh()
            interceptOhttp(chain)
        }
    }

    private fun interceptOhttp(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val (relayRequest, context) = Ohttp.encapsulateRequest(original, keys.get(), relayUrl)
        return Ohttp.decapsulateResponse(chain.proceed(relayRequest), context, original)
    }

    private companion object {
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
