package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.KeyConfigManager
import io.github.gabrielhuff.ohttp.internal.Ohttp
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * OkHttp [Interceptor] that turns regular HTTP(S) calls to a single target
 * host into Oblivious HTTP exchanges (RFC 9458). Requests whose host does not
 * match [targetUrl] are passed through unchanged. To proxy more than one
 * target, install one interceptor per target.
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
 * [keyConfigUrl] (defaulting to the well-known endpoint on [targetUrl]'s host —
 * RFC 9540 §5 places the Oblivious Gateway Resource there) using
 * [keyConfigClient], and cached in memory. If
 * [defaultKeyConfigBytes] is supplied and parseable it seeds that cache so the
 * first request needs no round trip. When the gateway rotates keys, the first
 * affected request is rejected, the interceptor refreshes the key, and retries
 * once. Callers may also call [refreshKey] proactively (e.g. on app foreground).
 *
 * @param targetUrl the target resource whose requests are encapsulated
 *   (matched by host); callers address this, not the relay or gateway.
 * @param relayUrl where the encapsulated request is POSTed.
 * @param keyConfigUrl where the gateway's key configuration is fetched from.
 *   Defaults to `https://{target-host}/.well-known/ohttp-gateway` — RFC 9540 §5
 *   defines that Oblivious Gateway Resource on the target's host. Set it
 *   explicitly for deployments that publish the key configuration elsewhere or
 *   distribute it out of band.
 * @param keyConfigClient the client used to fetch the key configuration. The
 *   default is a fresh, cache-less [OkHttpClient]; supply one backed by an
 *   [okhttp3.Cache] (e.g. on Android, with `context.cacheDir`) for persistence,
 *   or one routed through the relay for stronger metadata privacy.
 * @param defaultKeyConfigBytes optional initial key configuration to seed the
 *   cache, in the "application/ohttp-keys" collection format (RFC 9458 §3.2) —
 *   the same bytes the key endpoint serves. Unparseable bytes are ignored, and
 *   the first request fetches instead.
 */
public class OhttpInterceptor @JvmOverloads constructor(
    private val targetUrl: HttpUrl,
    private val relayUrl: HttpUrl,
    keyConfigUrl: HttpUrl = wellKnownKeyConfigUrl(targetUrl),
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
        if (original.url.host != targetUrl.host) return chain.proceed(original)

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
        // RFC 9540 §5 locates the Oblivious Gateway Resource at this well-known
        // URI on the target's host; its key configuration is fetched there (§6).
        fun wellKnownKeyConfigUrl(targetUrl: HttpUrl): HttpUrl =
            targetUrl.newBuilder()
                .encodedPath("/.well-known/ohttp-gateway")
                .query(null)
                .fragment(null)
                .build()
    }
}
