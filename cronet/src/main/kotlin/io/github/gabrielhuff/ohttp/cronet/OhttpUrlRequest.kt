package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.cronet.internal.ByteArrayUploadDataProvider
import io.github.gabrielhuff.ohttp.cronet.internal.OhttpCronetException
import io.github.gabrielhuff.ohttp.cronet.internal.SerialExecutor
import io.github.gabrielhuff.ohttp.cronet.internal.SynthesizedUrlResponseInfo
import io.github.gabrielhuff.ohttp.cronet.internal.UploadBuffering
import io.github.gabrielhuff.ohttp.internal.Bhttp
import io.github.gabrielhuff.ohttp.internal.BhttpRequest
import io.github.gabrielhuff.ohttp.internal.Ohttp
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `org.chromium.net.UrlRequest` implementation that performs the OHTTP
 * dance under the hood for a single configured target. Built via
 * [OhttpCronetEngine.newUrlRequestBuilder].
 *
 * Flow:
 *  1. [start] buffers the user's upload (via [UploadDataProvider]) on
 *     [workExecutor], BHTTP-encodes the request, OHTTP-encapsulates it, and
 *     issues a relay `UrlRequest` through the delegate [CronetEngine].
 *  2. The relay response is buffered, decapsulated, decoded back to a
 *     [io.github.gabrielhuff.ohttp.internal.BhttpResponse], and surfaced to
 *     the user via the standard `onResponseStarted` / `onReadCompleted` /
 *     `onSucceeded` callback sequence.
 *
 * Known limitations vs. native Cronet:
 *  - Streaming is buffered (OHTTP is one-shot). Large request/response bodies
 *    sit in memory.
 *  - [followRedirect] is unsupported; non-2xx responses with `Location` are
 *    surfaced as the final response. The caller must follow them explicitly
 *    if desired.
 *  - [getStatus] is best-effort (we don't expose engine internals).
 */
public class OhttpUrlRequest internal constructor(
    private val delegate: CronetEngine,
    private val targetUrl: String,
    private val userCallback: UrlRequest.Callback,
    private val userExecutor: Executor,
    private val workExecutor: Executor,
    private val config: OhttpConfig,
    private val httpMethod: String,
    private val requestHeaders: List<Pair<String, String>>,
    private val uploadDataProvider: UploadDataProvider?,
    private val uploadDataExecutor: Executor?,
) : UrlRequest() {

    private enum class State { INIT, STARTED, AWAITING_RELAY, DELIVERING, SUCCEEDED, FAILED, CANCELED }

    private val lock = Any()
    private var state: State = State.INIT
    private var relayRequest: UrlRequest? = null
    private var responseBody: ByteArray = ByteArray(0)
    private var bodyOffset: Int = 0
    private var responseInfo: SynthesizedUrlResponseInfo? = null

    public override fun start() {
        synchronized(lock) {
            check(state == State.INIT) { "start() already called (state=$state)" }
            state = State.STARTED
        }
        workExecutor.execute { encapsulateAndDispatch() }
    }

    public override fun cancel() {
        val relay: UrlRequest?
        val info: SynthesizedUrlResponseInfo?
        synchronized(lock) {
            if (state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELED) return
            state = State.CANCELED
            relay = relayRequest
            info = responseInfo
        }
        relay?.cancel()
        userExecutor.execute { userCallback.onCanceled(this, info) }
    }

    public override fun isDone(): Boolean = synchronized(lock) {
        state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELED
    }

    public override fun read(buffer: ByteBuffer) {
        require(buffer.hasRemaining()) { "read() called with a full ByteBuffer" }
        val info: SynthesizedUrlResponseInfo
        val deliveredBytes: Int
        val isLast: Boolean
        synchronized(lock) {
            // Cronet contract is that the user stops calling read() once
            // onSucceeded fires, but the user's onReadCompleted may queue one
            // last read() before the onSucceeded callback runs — tolerate it.
            if (state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELED) return
            check(state == State.DELIVERING) { "read() called in state $state" }
            deliveredBytes = minOf(buffer.remaining(), responseBody.size - bodyOffset)
            buffer.put(responseBody, bodyOffset, deliveredBytes)
            bodyOffset += deliveredBytes
            info = responseInfo!!
            info.addReceivedBytes(deliveredBytes.toLong())
            isLast = bodyOffset == responseBody.size
            // Eagerly transition before scheduling so re-entrant read() inside
            // the user's onReadCompleted callback hits the SUCCEEDED branch
            // above and silently returns rather than re-triggering the chain.
            if (isLast) state = State.SUCCEEDED
        }
        if (deliveredBytes > 0) {
            userExecutor.execute { userCallback.onReadCompleted(this, info, buffer) }
        }
        if (isLast) {
            userExecutor.execute { userCallback.onSucceeded(this, info) }
        }
    }

    public override fun followRedirect() {
        throw UnsupportedOperationException(
            "OhttpUrlRequest surfaces all responses to onResponseStarted; redirects must be followed by the caller"
        )
    }

    public override fun getStatus(listener: StatusListener) {
        val s = synchronized(lock) { state }
        val cronetStatus = when (s) {
            State.INIT -> Status.IDLE
            State.STARTED -> Status.WAITING_FOR_DELEGATE
            State.AWAITING_RELAY -> Status.WAITING_FOR_RESPONSE
            State.DELIVERING -> Status.READING_RESPONSE
            else -> Status.INVALID
        }
        userExecutor.execute { listener.onStatus(cronetStatus) }
    }

    // ---- internal mechanics ----

    private fun encapsulateAndDispatch() {
        try {
            if (isCanceled()) return

            val bodyBytes = uploadDataProvider
                ?.let { UploadBuffering.bufferAll(it, uploadDataExecutor!!) }
                ?: ByteArray(0)
            if (isCanceled()) return

            val bhttpReq = buildBhttpRequest(bodyBytes)
            val bhttpBytes = Bhttp.encodeRequest(bhttpReq)
            val encapsulated = Ohttp.encapsulateRequest(config.keyConfig, bhttpBytes)

            // Per-request serial executor: the underlying workExecutor may be a
            // thread pool, but the relay UrlRequest's onReadCompleted /
            // onSucceeded callbacks share a buffer and MUST run sequentially.
            val relayExecutor = SerialExecutor(workExecutor)
            val relayCallback = RelayCallback(encapsulated.context)
            val relayBuilder = delegate.newUrlRequestBuilder(
                config.relayUrl,
                relayCallback,
                relayExecutor,
            )
            relayBuilder.setHttpMethod("POST")
            relayBuilder.addHeader("Content-Type", Ohttp.REQUEST_MEDIA_TYPE)
            relayBuilder.addHeader("Accept", Ohttp.RESPONSE_MEDIA_TYPE)
            relayBuilder.setUploadDataProvider(ByteArrayUploadDataProvider(encapsulated.ciphertext), relayExecutor)
            val relay = relayBuilder.build()

            synchronized(lock) {
                if (state == State.CANCELED) {
                    relay.cancel()
                    return
                }
                state = State.AWAITING_RELAY
                relayRequest = relay
            }
            relay.start()
        } catch (t: Throwable) {
            deliverFailure(t)
        }
    }

    private fun buildBhttpRequest(body: ByteArray): BhttpRequest {
        val uri = URI(targetUrl)
        val scheme = uri.scheme ?: throw IllegalArgumentException("targetUrl has no scheme: $targetUrl")
        val host = uri.host ?: throw IllegalArgumentException("targetUrl has no host: $targetUrl")
        val authority = buildAuthority(scheme, host, uri.port)
        val rawPath = uri.rawPath.ifNullOrEmpty("/")
        val pathWithQuery = if (uri.rawQuery.isNullOrEmpty()) rawPath else "$rawPath?${uri.rawQuery}"
        return BhttpRequest(httpMethod, scheme, authority, pathWithQuery, requestHeaders, body)
    }

    private fun buildAuthority(scheme: String, host: String, port: Int): String {
        val defaultPort = (scheme == "https" && (port == -1 || port == 443)) ||
            (scheme == "http" && (port == -1 || port == 80))
        return if (defaultPort) host else "$host:$port"
    }

    private fun String?.ifNullOrEmpty(default: String): String = if (this.isNullOrEmpty()) default else this

    private fun deliverFailure(cause: Throwable) {
        val toCancel: UrlRequest?
        val info: SynthesizedUrlResponseInfo?
        val shouldFire = synchronized(lock) {
            if (state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELED) {
                false
            } else {
                state = State.FAILED
                true
            }
        }
        synchronized(lock) {
            toCancel = relayRequest
            info = responseInfo
        }
        if (!shouldFire) return
        toCancel?.cancel()
        val ex = cause as? CronetException ?: OhttpCronetException(cause.message ?: cause.javaClass.simpleName, cause)
        userExecutor.execute { userCallback.onFailed(this, info, ex) }
    }

    private fun isCanceled(): Boolean = synchronized(lock) { state == State.CANCELED }

    /** Internal callback driven by the delegate Cronet engine for the relay leg. */
    private inner class RelayCallback(
        private val clientContext: Ohttp.ClientContext,
    ) : UrlRequest.Callback() {

        private val sink = ByteArrayOutputStream()
        private val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(32 * 1024)
        private val finished = AtomicBoolean(false)

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            // Relays should never redirect; refuse and surface as failure.
            request.cancel()
            deliverFailure(OhttpCronetException("OHTTP relay returned a redirect to $newLocationUrl", null))
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            if (info.httpStatusCode !in 200..299) {
                request.cancel()
                deliverFailure(OhttpCronetException("OHTTP relay returned HTTP ${info.httpStatusCode}", null))
                return
            }
            val ct = info.allHeaders["Content-Type"]?.firstOrNull()
            if (ct != null && !ct.startsWith(Ohttp.RESPONSE_MEDIA_TYPE)) {
                request.cancel()
                deliverFailure(OhttpCronetException("OHTTP relay returned wrong Content-Type: $ct", null))
                return
            }
            request.read(readBuffer)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = ByteArray(byteBuffer.remaining())
            byteBuffer.get(chunk)
            sink.write(chunk)
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            if (!finished.compareAndSet(false, true)) return
            try {
                val encResponse = sink.toByteArray()
                val bhttpBytes = Ohttp.decapsulateResponse(clientContext, encResponse)
                val bhttpResp = Bhttp.decodeResponse(bhttpBytes)
                val synthInfo = SynthesizedUrlResponseInfo.fromBhttpResponse(targetUrl, bhttpResp)
                val shouldFire = synchronized(lock) {
                    if (state == State.CANCELED || state == State.FAILED) false
                    else { state = State.DELIVERING; responseBody = bhttpResp.body; responseInfo = synthInfo; true }
                }
                if (!shouldFire) return
                userExecutor.execute { userCallback.onResponseStarted(this@OhttpUrlRequest, synthInfo) }
            } catch (t: Throwable) {
                deliverFailure(t)
            }
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            if (!finished.compareAndSet(false, true)) return
            deliverFailure(error)
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            // If the relay was cancelled, our own state was already moved to CANCELED.
        }
    }

    public class Builder internal constructor(
        private val delegate: CronetEngine,
        private val targetUrl: String,
        private val callback: UrlRequest.Callback,
        private val executor: Executor,
        private val workExecutor: Executor,
        private val config: OhttpConfig,
    ) : UrlRequest.Builder() {

        private var method: String = "GET"
        private val headers = mutableListOf<Pair<String, String>>()
        private var uploadDataProvider: UploadDataProvider? = null
        private var uploadDataExecutor: Executor? = null

        override fun setHttpMethod(method: String): Builder = apply { this.method = method }
        override fun addHeader(header: String, value: String): Builder = apply { headers.add(header to value) }
        override fun disableCache(): Builder = this // OHTTP responses aren't cached locally.
        override fun setPriority(priority: Int): Builder = this // No-op; priority isn't meaningful for OHTTP.
        override fun setUploadDataProvider(provider: UploadDataProvider, executor: Executor): Builder = apply {
            uploadDataProvider = provider
            uploadDataExecutor = executor
        }
        override fun allowDirectExecutor(): Builder = this

        override fun build(): UrlRequest = OhttpUrlRequest(
            delegate = delegate,
            targetUrl = targetUrl,
            userCallback = callback,
            userExecutor = executor,
            workExecutor = workExecutor,
            config = config,
            httpMethod = method,
            requestHeaders = headers.toList(),
            uploadDataProvider = uploadDataProvider,
            uploadDataExecutor = uploadDataExecutor,
        )
    }
}
