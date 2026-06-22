package io.github.gabrielhuff.ohttp.internal

import java.nio.ByteBuffer

/**
 * QUIC variable-length integer encoding (RFC 9000 §16).
 *
 * API:
 * - [write] serializes a non-negative [Long] into a [ByteBuffer]
 * - [read] deserializes the next varint from a [ByteBuffer] to a [Long]
 * - [MAX_BYTES] upper bound on an encoded varint, for sizing buffers
 *
 * Used by [Bhttp].
 */
internal object Varint {

    /** The most bytes a single varint can occupy; useful for sizing output buffers. */
    const val MAX_BYTES: Int = 8

    fun write(sink: ByteBuffer, value: Long) {
        require(value >= 0) { "varint must be non-negative: $value" }
        when {
            value < (1L shl 6) -> sink.put(value.toByte())
            value < (1L shl 14) -> sink.putShort((value or (0b01L shl 14)).toShort())
            value < (1L shl 30) -> sink.putInt((value or (0b10L shl 30)).toInt())
            value < (1L shl 62) -> sink.putLong(value or (0b11L shl 62))
            else -> throw IllegalArgumentException("varint too large: $value")
        }
    }

    fun read(source: ByteBuffer): Long {
        require(source.hasRemaining()) { "varint requires at least one byte" }
        // Peek the prefix without advancing — branch on the top two bits.
        val first = source.get(source.position()).toInt() and 0xFF
        return when (first ushr 6) {
            0 -> (source.get().toInt() and 0x3F).toLong()
            1 -> (source.short.toInt() and 0x3FFF).toLong()
            2 -> source.int.toLong() and 0x3FFFFFFFL
            3 -> source.long and 0x3FFFFFFFFFFFFFFFL
            else -> error("unreachable")
        }
    }
}
