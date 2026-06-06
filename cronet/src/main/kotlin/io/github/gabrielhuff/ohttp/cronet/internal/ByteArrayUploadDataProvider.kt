package io.github.gabrielhuff.ohttp.cronet.internal

import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.nio.ByteBuffer

/** Hands a fixed byte array to a Cronet [UploadDataProvider]. Used to upload the encapsulated OHTTP payload to the relay. */
internal class ByteArrayUploadDataProvider(private val payload: ByteArray) : UploadDataProvider() {
    private var offset: Int = 0
    override fun getLength(): Long = payload.size.toLong()

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val remaining = payload.size - offset
        if (remaining <= 0) {
            uploadDataSink.onReadSucceeded(true)
            return
        }
        val toCopy = minOf(remaining, byteBuffer.remaining())
        byteBuffer.put(payload, offset, toCopy)
        offset += toCopy
        uploadDataSink.onReadSucceeded(offset == payload.size)
    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        offset = 0
        uploadDataSink.onRewindSucceeded()
    }
}
