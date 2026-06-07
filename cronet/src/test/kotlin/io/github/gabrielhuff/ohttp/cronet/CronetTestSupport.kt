package io.github.gabrielhuff.ohttp.cronet

import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [UrlRequest.Callback] that drives a request to completion, accumulates the
 * response body, and records the sequence of callbacks. Shared across the
 * cronet unit and interop tests.
 */
internal class CollectingCallback : UrlRequest.Callback() {
    private val terminal = CountDownLatch(1)
    private val sink = ByteArrayOutputStream()
    private val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(8192)
    val events = mutableListOf<String>()
    @Volatile var info: UrlResponseInfo? = null
    @Volatile var failed: Boolean = false
    @Volatile var canceled: Boolean = false
    @Volatile var failure: CronetException? = null

    fun awaitDone(timeout: Long, unit: TimeUnit): Boolean = terminal.await(timeout, unit)
    fun bodyUtf8(): String = sink.toByteArray().decodeToString()

    override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        events.add("redirect")
        request.followRedirect()
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        events.add("started:${info.httpStatusCode}")
        this.info = info
        request.read(readBuffer)
    }

    override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        byteBuffer.flip()
        val chunk = ByteArray(byteBuffer.remaining())
        byteBuffer.get(chunk)
        sink.write(chunk)
        byteBuffer.clear()
        events.add("read:${chunk.size}")
        request.read(byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        events.add("succeeded")
        terminal.countDown()
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
        events.add("failed:${error.message}")
        failed = true
        failure = error
        terminal.countDown()
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        events.add("canceled")
        canceled = true
        terminal.countDown()
    }
}

/** A fixed in-memory [UploadDataProvider] for exercising request bodies. */
internal class FixedUploadProvider(private val bytes: ByteArray) : UploadDataProvider() {
    private var offset = 0
    override fun getLength(): Long = bytes.size.toLong()
    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val toCopy = minOf(bytes.size - offset, byteBuffer.remaining())
        byteBuffer.put(bytes, offset, toCopy)
        offset += toCopy
        uploadDataSink.onReadSucceeded(offset == bytes.size)
    }
    override fun rewind(uploadDataSink: UploadDataSink) {
        offset = 0
        uploadDataSink.onRewindSucceeded()
    }
}
