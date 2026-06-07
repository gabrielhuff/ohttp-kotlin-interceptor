package io.github.gabrielhuff.ohttp.internal

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Plain (unlabeled) HKDF-Extract / HKDF-Expand per RFC 5869. We need this for
// OHTTP response encryption (RFC 9458 §4.5), which uses unlabeled HKDF; Tink's
// HpkeKdf interface only exposes the *labeled* variants used internally by HPKE.
internal class Hkdf(private val macAlgorithm: String) {

    // SecretKeySpec rejects an empty key, so probe macLength with a 1-byte dummy.
    val hashLen: Int = newMac(byteArrayOf(0)).macLength

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val saltOrZero = if (salt.isEmpty()) ByteArray(hashLen) else salt
        return newMac(saltOrZero).doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * hashLen) { "HKDF expand length too large: $length" }
        val out = ByteArray(length)
        val mac = newMac(prk)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(hashLen, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    private fun newMac(key: ByteArray): Mac {
        val mac = Mac.getInstance(macAlgorithm)
        mac.init(SecretKeySpec(key, macAlgorithm))
        return mac
    }
}
