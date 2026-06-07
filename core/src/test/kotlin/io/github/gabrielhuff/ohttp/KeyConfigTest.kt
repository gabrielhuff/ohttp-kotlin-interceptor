package io.github.gabrielhuff.ohttp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.HexFormat

class KeyConfigTest {

    @Test
    fun `RFC 9458 Appendix A test vector parses`() {
        // From RFC 9458 Appendix A — single key config: keyId=0x01, kemId=0x0020
        // (X25519), pk=31..32 zero/well-known bytes (use a known 32-byte value),
        // 1 symmetric suite (HKDF-SHA256, AES-128-GCM).
        val hex = (
            "01" +                                                         // keyId
            "0020" +                                                       // kemId X25519
            "31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" + // 32-byte pk
            "0004" +                                                       // 4 bytes of symmetric algorithms
            "0001" + "0001"                                                // HKDF-SHA256, AES-128-GCM
        )
        val bytes = HexFormat.of().parseHex(hex)
        val cfg = KeyConfig.parse(bytes)

        assertEquals(0x01, cfg.keyId)
        assertEquals(0x0020, cfg.kemId)
        assertEquals(32, cfg.publicKey.size)
        assertEquals(1, cfg.symmetricAlgorithms.size)
        assertEquals(KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001), cfg.symmetricAlgorithms[0])

        // Round-trip.
        val reEncoded = KeyConfig.serialize(cfg)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun `multiple symmetric algorithm pairs round-trip`() {
        val original = KeyConfig(
            keyId = 0xAB,
            kemId = 0x0020,
            publicKey = ByteArray(32) { it.toByte() },
            symmetricAlgorithms = listOf(
                KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001),
                KeyConfig.SymmetricAlgorithmPair(0x0003, 0x0003), // HKDF-SHA512, ChaCha20
            ),
        )
        val bytes = KeyConfig.serialize(original)
        val reparsed = KeyConfig.parse(bytes)
        assertEquals(original.keyId, reparsed.keyId)
        assertEquals(original.kemId, reparsed.kemId)
        assertArrayEquals(original.publicKey, reparsed.publicKey)
        assertEquals(original.symmetricAlgorithms, reparsed.symmetricAlgorithms)
    }
}
