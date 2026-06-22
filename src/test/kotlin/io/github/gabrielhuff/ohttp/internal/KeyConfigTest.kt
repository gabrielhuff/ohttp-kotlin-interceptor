package io.github.gabrielhuff.ohttp.internal

import io.github.gabrielhuff.ohttp.internal.Ohttp.KeyConfig
import io.github.gabrielhuff.ohttp.internal.Ohttp.KeyConfig.SymmetricAlgorithmPair
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class KeyConfigTest {

    private fun config(
        keyId: Int = 0x01,
        kemId: Int = 0x0020,   // X25519, Npk = 32
        npk: Int = 32,
        pairs: List<Pair<Int, Int>> = listOf(0x0001 to 0x0001),
    ) = KeyConfig(
        keyId = keyId,
        kemId = kemId,
        publicKey = ByteArray(npk) { it.toByte() },
        symmetricAlgorithms = pairs.map { SymmetricAlgorithmPair(it.first, it.second) },
    )

    @Test
    fun `serialize then parse round-trips an X25519 config`() {
        val original = config()
        val parsed = KeyConfig.parse(KeyConfig.serialize(original))
        assertEquals(original.keyId, parsed.keyId)
        assertEquals(original.kemId, parsed.kemId)
        assertArrayEquals(original.publicKey, parsed.publicKey)
        assertEquals(original.symmetricAlgorithms, parsed.symmetricAlgorithms)
    }

    @Test
    fun `round-trips a P-256 config with several symmetric pairs`() {
        val original = config(
            kemId = 0x0010,   // P-256, Npk = 65
            npk = 65,
            pairs = listOf(0x0001 to 0x0001, 0x0002 to 0x0002, 0x0003 to 0x0003),
        )
        val parsed = KeyConfig.parse(KeyConfig.serialize(original))
        assertEquals(0x0010, parsed.kemId)
        assertArrayEquals(original.publicKey, parsed.publicKey)
        assertEquals(original.symmetricAlgorithms, parsed.symmetricAlgorithms)
    }

    @Test
    fun `parse rejects bytes truncated before the public key`() {
        val full = KeyConfig.serialize(config())
        assertThrows<IllegalArgumentException> { KeyConfig.parse(full.copyOf(10)) }
    }

    @Test
    fun `parse rejects trailing bytes`() {
        val full = KeyConfig.serialize(config())
        assertThrows<IllegalArgumentException> { KeyConfig.parse(full + 0x00) }
    }

    @Test
    fun `parse rejects a symmetric section length that is not a multiple of 4`() {
        // keyId | kemId(X25519) | 32-byte key | symLen=3 | 3 bytes
        val bytes = ByteBuffer.allocate(1 + 2 + 32 + 2 + 3)
            .put(0x01)
            .putShort(0x0020)
            .put(ByteArray(32))
            .putShort(3)
            .put(ByteArray(3))
            .array()
        assertThrows<IllegalArgumentException> { KeyConfig.parse(bytes) }
    }

    @Test
    fun `parse rejects an unknown KEM id`() {
        // keyId | kemId=0x00FF (unknown) — fails before reading the key.
        val bytes = byteArrayOf(0x01, 0x00, 0xFF.toByte())
        assertThrows<IllegalArgumentException> { KeyConfig.parse(bytes) }
    }

    @Test
    fun `pickSupportedSuite skips unsupported pairs and selects the first supported one`() {
        val suite = config(pairs = listOf(0x00FF to 0x0001, 0x0001 to 0x0002)).pickSupportedSuite()
        assertEquals(0x0020.toShort(), suite.kemId)
        assertEquals(0x0001.toShort(), suite.kdfId)
        assertEquals(0x0002.toShort(), suite.aeadId)
    }

    @Test
    fun `pickSupportedSuite throws when no symmetric pair is supported`() {
        assertThrows<IllegalStateException> {
            config(pairs = listOf(0x00FF to 0x00FF)).pickSupportedSuite()
        }
    }

    @Test
    fun `pickSupportedSuite throws when the KEM is unsupported`() {
        // X448 (0x0021) parses (Npk = 56) but is not in the supported KEM set.
        assertThrows<IllegalStateException> {
            config(kemId = 0x0021, npk = 56).pickSupportedSuite()
        }
    }
}
