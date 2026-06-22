package io.github.gabrielhuff.ohttp.internal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

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

    fun write(sink: DataOutputStream, value: Long) {
        require(value >= 0) { "varint must be non-negative: $value" }
        when {
            value < (1L shl 6) -> sink.writeByte(value.toInt())
            value < (1L shl 14) -> sink.writeShort((value or (0b01L shl 14)).toInt())
            value < (1L shl 30) -> sink.writeInt((value or (0b10L shl 30)).toInt())
            value < (1L shl 62) -> sink.writeLong(value or (0b11L shl 62))
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

    fun toBytes(value: Long): ByteArray {
        val baos = ByteArrayOutputStream(encodedSize(value))
        write(DataOutputStream(baos), value)
        return baos.toByteArray()
    }
}
