package io.github.gabrielhuff.ohttp.internal

import com.google.crypto.tink.hybrid.internal.HpkeAead
import com.google.crypto.tink.hybrid.internal.HpkeKdf
import com.google.crypto.tink.hybrid.internal.HpkeKem
import com.google.crypto.tink.hybrid.internal.HpkeKemPrivateKey
import com.google.crypto.tink.hybrid.internal.HpkePrimitiveFactory
import com.google.crypto.tink.hybrid.internal.HpkeUtil
import com.google.crypto.tink.hybrid.internal.OhttpHpkeBridge
import com.google.crypto.tink.util.Bytes

// HPKE Base-mode wrapper that exposes Seal/Open AND Export (RFC 9180 §5.3).
// Tink's own HpkeContext discards exporter_secret, so we re-implement the
// KeySchedule on top of Tink's HpkeKdf / HpkeAead primitives. Suite identifiers
// follow RFC 9180; encoded values are taken from HpkeUtil.
internal class HpkeSuite(
    val kemId: ByteArray,
    val kdfId: ByteArray,
    val aeadId: ByteArray,
) {
    val kem: HpkeKem = HpkePrimitiveFactory.createKem(kemId)
    val kdf: HpkeKdf = HpkePrimitiveFactory.createKdf(kdfId)
    val aead: HpkeAead = HpkePrimitiveFactory.createAead(aeadId)
    val suiteId: ByteArray = OhttpHpkeBridge.hpkeSuiteId(kemId, kdfId, aeadId)
    val nK: Int = aead.keyLength
    val nN: Int = aead.nonceLength

    companion object {
        val X25519_SHA256_AES128GCM = HpkeSuite(
            HpkeUtil.X25519_HKDF_SHA256_KEM_ID,
            HpkeUtil.HKDF_SHA256_KDF_ID,
            HpkeUtil.AES_128_GCM_AEAD_ID,
        )
    }
}

internal class HpkeContext(
    private val suite: HpkeSuite,
    private val key: ByteArray,
    private val baseNonce: ByteArray,
    private val exporterSecret: ByteArray,
) {
    private var sequenceNumber: Long = 0

    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = computeNonceAndAdvance()
        return suite.aead.seal(key, nonce, plaintext, aad)
    }

    fun open(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = computeNonceAndAdvance()
        return suite.aead.open(key, nonce, ciphertext, aad)
    }

    // RFC 9180 §5.3 — Secret Export.
    fun export(exporterContext: ByteArray, length: Int): ByteArray =
        suite.kdf.labeledExpand(exporterSecret, exporterContext, "sec", suite.suiteId, length)

    private fun computeNonceAndAdvance(): ByteArray {
        val nonce = baseNonce.copyOf()
        var s = sequenceNumber
        var i = nonce.size - 1
        while (s != 0L && i >= 0) {
            nonce[i] = (nonce[i].toInt() xor (s and 0xFF).toInt()).toByte()
            s = s ushr 8
            i--
        }
        sequenceNumber++
        return nonce
    }
}

internal object Hpke {

    // RFC 9180 §5.1 — KeySchedule (Base mode).
    private fun keySchedule(
        suite: HpkeSuite,
        sharedSecret: ByteArray,
        info: ByteArray,
    ): HpkeContext {
        val emptyPsk = ByteArray(0)
        val emptyPskId = ByteArray(0)
        val pskIdHash = suite.kdf.labeledExtract(EMPTY_SALT, emptyPskId, "psk_id_hash", suite.suiteId)
        val infoHash = suite.kdf.labeledExtract(EMPTY_SALT, info, "info_hash", suite.suiteId)
        val keyScheduleContext = byteArrayOf(MODE_BASE) + pskIdHash + infoHash

        val secret = suite.kdf.labeledExtract(sharedSecret, emptyPsk, "secret", suite.suiteId)
        val key = suite.kdf.labeledExpand(secret, keyScheduleContext, "key", suite.suiteId, suite.nK)
        val baseNonce = suite.kdf.labeledExpand(secret, keyScheduleContext, "base_nonce", suite.suiteId, suite.nN)
        // Nh — HKDF output length. For SHA-256 it's 32. We derive it indirectly via
        // a labeled_expand of length=hashLen; since the only KDF we ship is HKDF-SHA256
        // and Tink doesn't expose the hash length, we hardcode the suite mapping.
        val nH = nHForKdf(suite.kdfId)
        val exporterSecret = suite.kdf.labeledExpand(secret, keyScheduleContext, "exp", suite.suiteId, nH)
        return HpkeContext(suite, key, baseNonce, exporterSecret)
    }

    fun nHForKdf(kdfId: ByteArray): Int = when {
        kdfId.contentEquals(HpkeUtil.HKDF_SHA256_KDF_ID) -> 32
        kdfId.contentEquals(HpkeUtil.HKDF_SHA384_KDF_ID) -> 48
        kdfId.contentEquals(HpkeUtil.HKDF_SHA512_KDF_ID) -> 64
        else -> throw IllegalArgumentException("unsupported KDF: ${kdfId.toHex()}")
    }

    data class SetupSenderResult(val enc: ByteArray, val context: HpkeContext)

    // RFC 9180 §5.1.1 — SetupBaseS.
    fun setupBaseSender(suite: HpkeSuite, recipientPublicKey: ByteArray, info: ByteArray): SetupSenderResult {
        val encap = OhttpHpkeBridge.encapsulate(suite.kem, recipientPublicKey)
        return SetupSenderResult(encap.enc, keySchedule(suite, encap.sharedSecret, info))
    }

    // RFC 9180 §5.1.1 — SetupBaseR.
    fun setupBaseRecipient(
        suite: HpkeSuite,
        enc: ByteArray,
        recipientPrivateKey: ByteArray,
        recipientPublicKey: ByteArray,
        info: ByteArray,
    ): HpkeContext {
        val kemPrivateKey = HpkeKemPrivateKey(
            Bytes.copyFrom(recipientPrivateKey),
            Bytes.copyFrom(recipientPublicKey),
        )
        val sharedSecret = suite.kem.decapsulate(enc, kemPrivateKey)
        return keySchedule(suite, sharedSecret, info)
    }

    private val EMPTY_SALT = ByteArray(0)
    private const val MODE_BASE: Byte = 0x00
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
