package io.github.gabrielhuff.ohttp.internal

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

// QUIC variable-length integer encoding (RFC 9000 §16). Used by BHTTP (RFC 9292).
internal object Varint {

    fun encodedSize(value: Long): Int {
        require(value >= 0) { "varint must be non-negative: $value" }
        return when {
            value < (1L shl 6) -> 1
            value < (1L shl 14) -> 2
            value < (1L shl 30) -> 4
            value < (1L shl 62) -> 8
            else -> throw IllegalArgumentException("varint too large: $value")
        }
    }

    fun write(sink: BufferedSink, value: Long) {
        require(value >= 0) { "varint must be non-negative: $value" }
        when {
            value < (1L shl 6) -> sink.writeByte(value.toInt())
            value < (1L shl 14) -> sink.writeShort((value or (0b01L shl 14)).toInt())
            value < (1L shl 30) -> sink.writeInt((value or (0b10L shl 30)).toInt())
            value < (1L shl 62) -> sink.writeLong(value or (0b11L shl 62))
            else -> throw IllegalArgumentException("varint too large: $value")
        }
    }

    fun read(source: BufferedSource): Long {
        source.require(1)
        val first = source.peek().readByte().toInt() and 0xFF
        return when (first ushr 6) {
            0 -> (source.readByte().toInt() and 0x3F).toLong()
            1 -> (source.readShort().toInt() and 0x3FFF).toLong()
            2 -> (source.readInt().toLong() and 0x3FFFFFFFL)
            3 -> source.readLong() and 0x3FFFFFFFFFFFFFFFL
            else -> error("unreachable")
        }
    }

    fun toBytes(value: Long): ByteArray = Buffer().also { write(it, value) }.readByteArray()
}
