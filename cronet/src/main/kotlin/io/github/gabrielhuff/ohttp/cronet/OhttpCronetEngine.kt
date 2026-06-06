package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Wraps a [CronetEngine] to perform Oblivious HTTP exchanges for configured
 * target hosts. Requests to any host not in [configs] are routed straight
 * through the wrapped engine — that is, the wrapper is a no-op for them.
 *
 * Wiring mirrors [io.github.gabrielhuff.ohttp.OhttpInterceptor] for the OkHttp
 * stack: pass a `Map<hostname, OhttpConfig>`. Each [OhttpConfig] carries the
 * relay URL and the gateway's published OHTTP key configuration bytes.
 *
 * The underlying [delegate] is also used for the relay leg, so any HTTP/3,
 * connection migration, or proxy settings configured on the delegate apply
 * to client→relay traffic too.
 *
 * @param delegate the real Cronet engine that will dispatch network calls.
 * @param configs target host → relay/key configuration.
 * @param workExecutor optional executor used for encapsulation, body
 *   buffering, and internal relay-callback dispatch. Defaults to a small
 *   shared thread pool. **Must not** be the user-facing executor that
 *   consumers pass per-request, to avoid blocking their callbacks during
 *   encapsulation.
 */
public class OhttpCronetEngine @JvmOverloads constructor(
    private val delegate: CronetEngine,
    configs: Map<String, OhttpConfig>,
    private val workExecutor: Executor = defaultWorkExecutor(),
) {
    private val configs: Map<String, OhttpConfig> = configs.toMap()

    /**
     * Mirrors [CronetEngine.newUrlRequestBuilder]. Returns a Cronet
     * [UrlRequest.Builder] that, when built and started:
     *  - performs OHTTP if [url]'s host is in [configs];
     *  - otherwise behaves identically to a builder created directly on the
     *    wrapped [CronetEngine].
     */
    public fun newUrlRequestBuilder(
        url: String,
        callback: UrlRequest.Callback,
        executor: Executor,
    ): UrlRequest.Builder {
        val host = url.toHttpUrlOrNull()?.host
        val config = host?.let { configs[it] }
        return if (config == null) {
            delegate.newUrlRequestBuilder(url, callback, executor)
        } else {
            OhttpUrlRequest.Builder(delegate, url, callback, executor, workExecutor, config)
        }
    }

    public companion object {
        private fun defaultWorkExecutor(): Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "ohttp-cronet-work").apply { isDaemon = true }
        }
    }
}
