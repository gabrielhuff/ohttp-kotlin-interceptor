package io.github.gabrielhuff.ohttp

import com.google.crypto.tink.hybrid.internal.HpkeUtil
import io.github.gabrielhuff.ohttp.internal.HpkeSuite
import okio.Buffer
import okio.ByteString.Companion.toByteString

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
        val kemBytes = idToBytes(kemId, 2)
        check(kemBytes.contentEquals(HpkeUtil.X25519_HKDF_SHA256_KEM_ID)) {
            "unsupported KEM 0x${"%04x".format(kemId)} (only X25519 is wired up; extend HpkeSuite to add more)"
        }
        for (pair in symmetricAlgorithms) {
            val kdfBytes = idToBytes(pair.kdfId, 2)
            val aeadBytes = idToBytes(pair.aeadId, 2)
            if (!SUPPORTED_KDF_IDS.any { it.contentEquals(kdfBytes) }) continue
            if (!SUPPORTED_AEAD_IDS.any { it.contentEquals(aeadBytes) }) continue
            return HpkeSuite(kemBytes, kdfBytes, aeadBytes)
        }
        error("no supported symmetric algorithm pair in key config: $symmetricAlgorithms")
    }

    public companion object {
        private val SUPPORTED_KDF_IDS = listOf(
            HpkeUtil.HKDF_SHA256_KDF_ID,
            HpkeUtil.HKDF_SHA384_KDF_ID,
            HpkeUtil.HKDF_SHA512_KDF_ID,
        )
        private val SUPPORTED_AEAD_IDS = listOf(
            HpkeUtil.AES_128_GCM_AEAD_ID,
            HpkeUtil.AES_256_GCM_AEAD_ID,
            HpkeUtil.CHACHA20_POLY1305_AEAD_ID,
        )

        @JvmStatic
        public fun parse(bytes: ByteArray): KeyConfig {
            val src = Buffer().write(bytes)
            val keyId = src.readByte().toInt() and 0xFF
            val kemId = src.readShort().toInt() and 0xFFFF
            val npk = publicKeySizeForKem(kemId)
            require(src.size >= npk) { "key config truncated: missing public key" }
            val publicKey = src.readByteArray(npk.toLong())
            val symLen = src.readShort().toInt() and 0xFFFF
            require(symLen % 4 == 0) { "symmetric algorithms section must be a multiple of 4 bytes" }
            require(src.size >= symLen) { "key config truncated: missing symmetric algorithms" }
            val symBytes = src.readByteArray(symLen.toLong())
            val syms = Buffer().write(symBytes).let { buf ->
                buildList {
                    while (!buf.exhausted()) {
                        val kdf = buf.readShort().toInt() and 0xFFFF
                        val aead = buf.readShort().toInt() and 0xFFFF
                        add(SymmetricAlgorithmPair(kdf, aead))
                    }
                }
            }
            require(src.exhausted()) { "trailing bytes in key config" }
            return KeyConfig(keyId, kemId, publicKey, syms)
        }

        @JvmStatic
        public fun serialize(config: KeyConfig): ByteArray {
            val out = Buffer()
            out.writeByte(config.keyId)
            out.writeShort(config.kemId)
            out.write(config.publicKey)
            out.writeShort(config.symmetricAlgorithms.size * 4)
            for (pair in config.symmetricAlgorithms) {
                out.writeShort(pair.kdfId)
                out.writeShort(pair.aeadId)
            }
            return out.readByteArray()
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

        private fun idToBytes(id: Int, size: Int): ByteArray =
            ByteArray(size).also { for (i in 0 until size) it[i] = ((id ushr (8 * (size - 1 - i))) and 0xFF).toByte() }
    }

    override fun toString(): String =
        "KeyConfig(keyId=$keyId, kemId=0x${"%04x".format(kemId)}, pk=${publicKey.toByteString().hex()}, " +
            "symmetric=$symmetricAlgorithms)"
}
