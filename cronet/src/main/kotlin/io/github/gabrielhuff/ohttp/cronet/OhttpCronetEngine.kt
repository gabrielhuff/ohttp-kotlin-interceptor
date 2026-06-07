package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Wraps a [CronetEngine] to perform Oblivious HTTP exchanges for configured
 * target hosts. Requests to any host not in [configs] are routed straight
 * through the wrapped engine — that is, the wrapper is a no-op for them.
 *
 * @param delegate the real Cronet engine that will dispatch network calls.
 * @param configs target host → relay/key configuration.
 * @param threading executor wiring for the internal work. See [Threading].
 *   Defaults route all internal work to a single shared cached thread pool.
 */
public class OhttpCronetEngine @JvmOverloads constructor(
    private val delegate: CronetEngine,
    configs: Map<String, OhttpConfig>,
    private val threading: Threading = Threading.shared(),
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
        val host = try { URI(url).host } catch (e: URISyntaxException) { null }
        val config = host?.let { configs[it] }
        return if (config == null) {
            delegate.newUrlRequestBuilder(url, callback, executor)
        } else {
            OhttpUrlRequest.Builder(delegate, url, callback, executor, threading, config)
        }
    }

    /**
     * Executor wiring for the OHTTP pipeline. Lets callers route distinct
     * pieces of work (HPKE / BHTTP, upload buffering, delegate-callback
     * dispatch) onto separate pools — useful when you want, for example,
     * crypto on a CPU-bound pool and I/O-driven callbacks on an event-loop
     * thread.
     *
     * Defaults wire all three to a single shared cached thread pool, which
     * is fine for most callers.
     *
     * ### Constraints
     * - [relayCallback] **must not** be the same single-threaded executor
     *   the user passes per-request as `UrlRequest.Callback`'s executor;
     *   doing so risks deadlock because the relay callback may dispatch
     *   user callbacks and the per-request executor would then be blocked
     *   on itself.
     * - [crypto] may block briefly during HPKE seal/open. Don't share it
     *   with latency-sensitive work.
     *
     * @param crypto runs HPKE encapsulation/decapsulation, BHTTP encode/
     *   decode, and the overall request orchestration.
     * @param uploadBuffering drains the user's [org.chromium.net.UploadDataProvider]
     *   into memory before BHTTP encoding (OHTTP is one-shot).
     * @param relayCallback executor handed to the delegate [CronetEngine]
     *   for the inner relay [UrlRequest]'s callbacks. The wrapper wraps
     *   this in a per-request serial dispatcher; if you pass a multi-threaded
     *   pool, ordering between `onReadCompleted` and `onSucceeded` is
     *   preserved per-request.
     */
    public class Threading(
        public val crypto: Executor,
        public val uploadBuffering: Executor,
        public val relayCallback: Executor,
    ) {
        public companion object {
            /** All three knobs share a single cached thread pool. The default. */
            @JvmStatic
            public fun shared(executor: Executor = defaultCachedPool()): Threading =
                Threading(crypto = executor, uploadBuffering = executor, relayCallback = executor)

            /** Distinct executors per role; the caller owns lifecycle. */
            @JvmStatic
            public fun split(
                crypto: Executor,
                uploadBuffering: Executor,
                relayCallback: Executor,
            ): Threading = Threading(crypto, uploadBuffering, relayCallback)

            private fun defaultCachedPool(): Executor = Executors.newCachedThreadPool { r ->
                Thread(r, "ohttp-cronet-work").apply { isDaemon = true }
            }
        }
    }
}
