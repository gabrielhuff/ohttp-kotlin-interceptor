package io.github.gabrielhuff.ohttp.cronet

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.nio.ByteBuffer
import java.util.AbstractMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal [CronetEngine] used by the unit tests. It dispatches each
 * `UrlRequest` through an [OkHttpClient] running on a small thread pool so we
 * can exercise the OHTTP UrlRequest pipeline end-to-end against MockWebServer
 * without depending on the real native Cronet runtime (which is only
 * available via Google Maven).
 */
class FakeCronetEngine(
    private val client: OkHttpClient = OkHttpClient(),
    private val networkExecutor: Executor = Executors.newCachedThreadPool { r ->
        Thread(r, "fake-cronet").apply { isDaemon = true }
    },
) : CronetEngine() {

    override fun newUrlRequestBuilder(
        url: String,
        callback: UrlRequest.Callback,
        executor: Executor,
    ): UrlRequest.Builder = FakeBuilder(url, callback, executor)

    private inner class FakeBuilder(
        private val url: String,
        private val callback: UrlRequest.Callback,
        private val executor: Executor,
    ) : UrlRequest.Builder() {
        var method: String = "GET"
        val headers = mutableListOf<Pair<String, String>>()
        var uploadDataProvider: UploadDataProvider? = null
        var uploadDataExecutor: Executor? = null

        override fun setHttpMethod(method: String): UrlRequest.Builder = apply { this.method = method }
        override fun addHeader(header: String, value: String): UrlRequest.Builder = apply { headers.add(header to value) }
        override fun disableCache(): UrlRequest.Builder = this
        override fun setPriority(priority: Int): UrlRequest.Builder = this
        override fun setUploadDataProvider(provider: UploadDataProvider, executor: Executor): UrlRequest.Builder = apply {
            uploadDataProvider = provider
            uploadDataExecutor = executor
        }
        override fun allowDirectExecutor(): UrlRequest.Builder = this
        override fun build(): UrlRequest = FakeUrlRequest(url, callback, executor, method, headers.toList(), uploadDataProvider, uploadDataExecutor)
    }

    private inner class FakeUrlRequest(
        private val url: String,
        private val callback: UrlRequest.Callback,
        private val executor: Executor,
        private val method: String,
        private val headers: List<Pair<String, String>>,
        private val uploadDataProvider: UploadDataProvider?,
        private val uploadDataExecutor: Executor?,
    ) : UrlRequest() {

        private val canceled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private val responseBodyRef = AtomicReference<ByteArray>()
        private val responseInfoRef = AtomicReference<UrlResponseInfo>()
        private var bodyOffset: Int = 0

        override fun start() {
            networkExecutor.execute { execute() }
        }

        private fun execute() {
            try {
                val bodyBytes = uploadDataProvider?.let { bufferUpload(it, uploadDataExecutor!!) } ?: ByteArray(0)
                if (canceled.get()) {
                    executor.execute { callback.onCanceled(this, null) }
                    return
                }
                val ctHeader = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                val reqBody = if (bodyBytes.isNotEmpty() || method !in setOf("GET", "HEAD", "DELETE")) {
                    bodyBytes.toRequestBody(ctHeader?.toMediaTypeOrNull())
                } else null
                val reqBuilder = Request.Builder().url(url).method(method, reqBody)
                for ((n, v) in headers) reqBuilder.addHeader(n, v)
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val info = SyntheticInfo(url, resp)
                    val body = resp.body?.bytes() ?: ByteArray(0)
                    responseBodyRef.set(body)
                    responseInfoRef.set(info)
                    if (canceled.get()) {
                        executor.execute { callback.onCanceled(this, info) }
                        return
                    }
                    executor.execute { callback.onResponseStarted(this, info) }
                }
            } catch (t: Throwable) {
                if (!done.compareAndSet(false, true)) return
                val ex = t as? CronetException ?: object : CronetException(t.message ?: "I/O error", t) {}
                executor.execute { callback.onFailed(this, responseInfoRef.get(), ex) }
            }
        }

        override fun followRedirect() = throw UnsupportedOperationException()

        override fun read(buffer: ByteBuffer) {
            val body = responseBodyRef.get() ?: throw IllegalStateException("read before onResponseStarted")
            val info = responseInfoRef.get()
            if (canceled.get()) return
            val remaining = body.size - bodyOffset
            if (remaining <= 0) {
                if (done.compareAndSet(false, true)) executor.execute { callback.onSucceeded(this, info) }
                return
            }
            val toCopy = minOf(buffer.remaining(), remaining)
            buffer.put(body, bodyOffset, toCopy)
            bodyOffset += toCopy
            val isLast = bodyOffset == body.size
            executor.execute { callback.onReadCompleted(this, info, buffer) }
            if (isLast && done.compareAndSet(false, true)) {
                executor.execute { callback.onSucceeded(this, info) }
            }
        }

        override fun cancel() {
            if (canceled.compareAndSet(false, true)) {
                executor.execute { callback.onCanceled(this, responseInfoRef.get()) }
            }
        }

        override fun isDone(): Boolean = done.get() || canceled.get()
        override fun getStatus(listener: StatusListener) { executor.execute { listener.onStatus(Status.IDLE) } }
    }

    private fun bufferUpload(provider: UploadDataProvider, executor: Executor): ByteArray {
        // Reuse the same buffering helper used by OhttpUrlRequest to keep paths equivalent.
        return io.github.gabrielhuff.ohttp.cronet.internal.UploadBuffering.bufferAll(provider, executor)
    }

    private class SyntheticInfo(private val url: String, response: Response) : UrlResponseInfo() {
        private val statusCode = response.code
        private val statusText = response.message
        private val headerList: List<Map.Entry<String, String>>
        private val headerMap: Map<String, List<String>>
        private val negotiated = response.protocol.toString()
        init {
            val list = ArrayList<Map.Entry<String, String>>(response.headers.size)
            val map = LinkedHashMap<String, MutableList<String>>()
            response.headers.forEachHeader { name, value ->
                list.add(AbstractMap.SimpleImmutableEntry(name, value))
                map.getOrPut(name) { mutableListOf() }.add(value)
            }
            headerList = list
            headerMap = map.mapValues { it.value.toList() }
        }
        override fun getUrl(): String = url
        override fun getUrlChain(): List<String> = listOf(url)
        override fun getHttpStatusCode(): Int = statusCode
        override fun getHttpStatusText(): String = statusText
        override fun getAllHeadersAsList(): List<Map.Entry<String, String>> = headerList
        override fun getAllHeaders(): Map<String, List<String>> = headerMap
        override fun wasCached(): Boolean = false
        override fun getNegotiatedProtocol(): String = negotiated
        override fun getProxyServer(): String = ""
        override fun getReceivedByteCount(): Long = 0
    }
}

private inline fun Headers.forEachHeader(block: (String, String) -> Unit) {
    for (i in 0 until size) block(name(i), value(i))
}
