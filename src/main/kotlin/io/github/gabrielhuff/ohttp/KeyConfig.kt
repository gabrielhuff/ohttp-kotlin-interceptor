package io.github.gabrielhuff.ohttp

import io.github.gabrielhuff.ohttp.internal.HpkeSuite
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.HexFormat

/**
 * Parsed OHTTP key configuration as defined in RFC 9458 §3.1.
 *
 * Fastly, Cloudflare, and other deployments publish these as opaque bytes
 * (typically fetched from `/.well-known/ohttp-gateway` or shipped out-of-band);
 * users of this library should pass the raw bytes straight from the source.
 */
public class KeyConfig internal constructor(
    public val keyId: Int,
    public val kemId: Int,
    public val publicKey: ByteArray,
    public val symmetricAlgorithms: List<SymmetricAlgorithmPair>,
) {
    public data class SymmetricAlgorithmPair(val kdfId: Int, val aeadId: Int)

    // First (KDF, AEAD) pair from the config that we can actually instantiate.
    internal fun pickSupportedSuite(): HpkeSuite {
        check(kemId in SUPPORTED_KEM_IDS) { "unsupported KEM 0x${"%04x".format(kemId)}" }
        for (pair in symmetricAlgorithms) {
            if (pair.kdfId !in SUPPORTED_KDF_IDS) continue
            if (pair.aeadId !in SUPPORTED_AEAD_IDS) continue
            return HpkeSuite(kemId.toShort(), pair.kdfId.toShort(), pair.aeadId.toShort())
        }
        error("no supported symmetric algorithm pair in key config: $symmetricAlgorithms")
    }

    public companion object {
        // HPKE primitives we accept from a published key configuration. Values
        // are the RFC 9180 §7 registry identifiers; BouncyCastle implements all
        // of these.
        private val SUPPORTED_KEM_IDS = setOf(
            0x0010, // P-256 + HKDF-SHA256
            0x0011, // P-384 + HKDF-SHA384
            0x0012, // P-521 + HKDF-SHA512
            0x0020, // X25519 + HKDF-SHA256
        )
        private val SUPPORTED_KDF_IDS = setOf(
            0x0001, // HKDF-SHA256
            0x0002, // HKDF-SHA384
            0x0003, // HKDF-SHA512
        )
        private val SUPPORTED_AEAD_IDS = setOf(
            0x0001, // AES-128-GCM
            0x0002, // AES-256-GCM
            0x0003, // ChaCha20-Poly1305
        )

        @JvmStatic
        public fun parse(bytes: ByteArray): KeyConfig {
            val src = ByteBuffer.wrap(bytes)
            val keyId = src.get().toInt() and 0xFF
            val kemId = src.short.toInt() and 0xFFFF
            val npk = publicKeySizeForKem(kemId)
            require(src.remaining() >= npk) { "key config truncated: missing public key" }
            val publicKey = ByteArray(npk)
            src.get(publicKey)
            require(src.remaining() >= 2) { "key config truncated: missing symmetric algorithms length" }
            val symLen = src.short.toInt() and 0xFFFF
            require(symLen % 4 == 0) { "symmetric algorithms section must be a multiple of 4 bytes" }
            require(src.remaining() >= symLen) { "key config truncated: missing symmetric algorithms" }
            val syms = buildList {
                val end = src.position() + symLen
                while (src.position() < end) {
                    val kdf = src.short.toInt() and 0xFFFF
                    val aead = src.short.toInt() and 0xFFFF
                    add(SymmetricAlgorithmPair(kdf, aead))
                }
            }
            require(!src.hasRemaining()) { "trailing bytes in key config" }
            return KeyConfig(keyId, kemId, publicKey, syms)
        }

        @JvmStatic
        public fun serialize(config: KeyConfig): ByteArray {
            val baos = ByteArrayOutputStream(8 + config.publicKey.size + config.symmetricAlgorithms.size * 4)
            val out = DataOutputStream(baos)
            out.writeByte(config.keyId)
            out.writeShort(config.kemId)
            out.write(config.publicKey)
            out.writeShort(config.symmetricAlgorithms.size * 4)
            for (pair in config.symmetricAlgorithms) {
                out.writeShort(pair.kdfId)
                out.writeShort(pair.aeadId)
            }
            return baos.toByteArray()
        }

        // RFC 9180 §7.1 — Npk for each KEM.
        private fun publicKeySizeForKem(kemId: Int): Int = when (kemId) {
            0x0010 -> 65   // P-256 uncompressed
            0x0011 -> 97   // P-384
            0x0012 -> 133  // P-521
            0x0020 -> 32   // X25519
            0x0021 -> 56   // X448
            else -> throw IllegalArgumentException("unknown KEM id 0x${"%04x".format(kemId)}")
        }
    }

    override fun toString(): String =
        "KeyConfig(keyId=$keyId, kemId=0x${"%04x".format(kemId)}, pk=${HexFormat.of().formatHex(publicKey)}, " +
            "symmetric=$symmetricAlgorithms)"
}
