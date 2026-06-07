package io.github.gabrielhuff.ohttp.testing

import com.google.crypto.tink.subtle.EllipticCurves
import com.google.crypto.tink.subtle.X25519
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Generates an HPKE-format keypair for the given KEM. Used by
 * [InProcessGateway] to wire up gateways for non-Fastly suites in tests.
 *
 * The returned byte encodings match what RFC 9180 §7.1 requires:
 *  - X25519 / X448: raw scalar / raw public key bytes
 *  - NIST P-256/P-384/P-521: SEC1 uncompressed point (`0x04 || X || Y`)
 *    for the public key; big-endian, fixed-length scalar for the private key.
 */
internal object HpkeKeyGen {

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generate(kemId: Int): KeyPair = when (kemId) {
        0x0020 -> { // X25519
            val sk = X25519.generatePrivateKey()
            KeyPair(sk, X25519.publicFromPrivate(sk))
        }
        0x0010 -> generateNist("secp256r1", EllipticCurves.CurveType.NIST_P256, 32)
        0x0011 -> generateNist("secp384r1", EllipticCurves.CurveType.NIST_P384, 48)
        0x0012 -> generateNist("secp521r1", EllipticCurves.CurveType.NIST_P521, 66)
        else -> throw IllegalArgumentException("unsupported KEM id 0x${"%04x".format(kemId)}")
    }

    private fun generateNist(curveName: String, curveType: EllipticCurves.CurveType, scalarSize: Int): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(curveName))
        val pair = gen.generateKeyPair()
        val pk = pair.public as ECPublicKey
        val sk = pair.private as ECPrivateKey
        val params: ECParameterSpec = pk.params
        // Uncompressed SEC1 form expected by Tink's NistCurvesHpkeKem.
        val pkBytes = EllipticCurves.pointEncode(params.curve, EllipticCurves.PointFormatType.UNCOMPRESSED, pk.w)
        val skBytes = encodeScalar(sk.s, scalarSize)
        return KeyPair(skBytes, pkBytes)
    }

    private fun encodeScalar(s: BigInteger, size: Int): ByteArray {
        val raw = s.toByteArray()
        // BigInteger.toByteArray() can prepend a sign byte (0x00) when the high bit is set.
        return when {
            raw.size == size -> raw
            raw.size == size + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < size -> ByteArray(size - raw.size) + raw
            else -> throw IllegalStateException("scalar does not fit in $size bytes: ${raw.size}")
        }
    }
}
