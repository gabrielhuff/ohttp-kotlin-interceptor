package io.github.gabrielhuff.ohttp.cronet.internal

import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * OHTTP is one-shot, so we must buffer the entire upload body before BHTTP
 * encoding. Drives [UploadDataProvider] through its async sink callback chain
 * and returns once `finalChunk` is observed (or it fails / times out).
 */
internal object UploadBuffering {

    private const val DEFAULT_CHUNK_BYTES = 16 * 1024

    fun bufferAll(provider: UploadDataProvider, executor: Executor, timeoutMs: Long = 30_000L): ByteArray {
        val advertisedLength = try { provider.length } catch (e: IOException) { -1L }
        val chunkSize = when {
            advertisedLength in 1L..DEFAULT_CHUNK_BYTES.toLong() -> advertisedLength.toInt()
            else -> DEFAULT_CHUNK_BYTES
        }
        val out = okio.Buffer()
        val byteBuffer = ByteBuffer.allocate(chunkSize)
        val future = CompletableFuture<Unit>()

        val sink = object : UploadDataSink() {
            override fun onReadSucceeded(finalChunk: Boolean) {
                byteBuffer.flip()
                if (byteBuffer.hasRemaining()) {
                    val chunk = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(chunk)
                    out.write(chunk)
                }
                byteBuffer.clear()
                if (finalChunk) {
                    future.complete(Unit)
                } else {
                    val self = this
                    executor.execute {
                        try {
                            provider.read(self, byteBuffer)
                        } catch (t: Throwable) {
                            future.completeExceptionally(t)
                        }
                    }
                }
            }
            override fun onReadError(e: Exception) { future.completeExceptionally(e) }
            override fun onRewindSucceeded() {}
            override fun onRewindError(e: Exception) { future.completeExceptionally(e) }
        }

        executor.execute {
            try {
                provider.read(sink, byteBuffer)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }

        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IOException("UploadDataProvider read failed", e.cause ?: e)
        } catch (e: TimeoutException) {
            throw IOException("UploadDataProvider read timed out after ${timeoutMs}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("UploadDataProvider read interrupted", e)
        }
        return out.readByteArray()
    }
}
