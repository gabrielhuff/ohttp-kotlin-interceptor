package io.github.gabrielhuff.ohttp.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class VarintTest {

    private fun roundTrip(value: Long, expectedBytes: Int) {
        val buf = ByteBuffer.allocate(Varint.MAX_BYTES)
        Varint.write(buf, value)
        assertEquals(expectedBytes, buf.position(), "encoded byte count for $value")
        buf.flip()
        assertEquals(value, Varint.read(buf), "round-trip for $value")
        assertFalse(buf.hasRemaining(), "all bytes consumed for $value")
    }

    @Test
    fun `1-byte range and its upper boundary`() {
        roundTrip(0, 1)
        roundTrip(1, 1)
        roundTrip(63, 1)          // 2^6 - 1, largest 1-byte value
        roundTrip(64, 2)          // crosses into 2-byte encoding
    }

    @Test
    fun `2-byte range and its upper boundary`() {
        roundTrip(300, 2)
        roundTrip(16383, 2)       // 2^14 - 1, largest 2-byte value
        roundTrip(16384, 4)       // crosses into 4-byte encoding
    }

    @Test
    fun `4-byte range and its upper boundary`() {
        roundTrip(1_000_000, 4)
        roundTrip((1L shl 30) - 1, 4)   // largest 4-byte value
        roundTrip(1L shl 30, 8)         // crosses into 8-byte encoding
    }

    @Test
    fun `8-byte range up to the maximum`() {
        roundTrip(5_000_000_000L, 8)
        roundTrip((1L shl 62) - 1, 8)   // largest representable varint
    }

    @Test
    fun `write rejects negative values`() {
        assertThrows<IllegalArgumentException> { Varint.write(ByteBuffer.allocate(8), -1) }
    }

    @Test
    fun `write rejects values that do not fit in 62 bits`() {
        assertThrows<IllegalArgumentException> { Varint.write(ByteBuffer.allocate(8), 1L shl 62) }
    }

    @Test
    fun `read rejects an empty buffer`() {
        assertThrows<IllegalArgumentException> { Varint.read(ByteBuffer.allocate(0)) }
    }
}
