package io.github.gabrielhuff.ohttp.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.HexFormat

class VarintTest {

    @Test
    fun `RFC 9000 Appendix A1 test vectors round-trip`() {
        // (value, encoded-hex) — straight from RFC 9000 Appendix A.1.
        val vectors = listOf(
            151_288_809_941_952_652L to "c2197c5eff14e88c",
            494_878_333L to "9d7f3e7d",
            15_293L to "7bbd",
            37L to "25",
            // Edge value: 1-byte max.
            63L to "3f",
            // Edge value: 2-byte max.
            16_383L to "7fff",
            // Edge value: 4-byte max.
            1_073_741_823L to "bfffffff",
        )
        val hex = HexFormat.of()
        for ((value, hexString) in vectors) {
            val expected = hex.parseHex(hexString)
            val encoded = Varint.toBytes(value)
            assertEquals(hex.formatHex(expected), hex.formatHex(encoded), "encode $value")
            val decoded = Varint.read(ByteBuffer.wrap(expected))
            assertEquals(value, decoded, "decode $hexString")
        }
    }

    @Test
    fun `zero encodes to single byte`() {
        assertEquals("00", HexFormat.of().formatHex(Varint.toBytes(0L)))
    }

    @Test
    fun `negative throws`() {
        assertThrows(IllegalArgumentException::class.java) { Varint.toBytes(-1L) }
    }

    @Test
    fun `encodedSize matches actual encoded length for boundary values`() {
        val cases = listOf(0L, 63L, 64L, 16_383L, 16_384L, 1_073_741_823L, 1_073_741_824L)
        for (v in cases) {
            assertEquals(Varint.toBytes(v).size, Varint.encodedSize(v), "size mismatch for $v")
        }
    }
}
