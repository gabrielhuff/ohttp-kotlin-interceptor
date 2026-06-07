package io.github.gabrielhuff.ohttp.internal

import com.google.crypto.tink.subtle.X25519
import io.github.gabrielhuff.ohttp.KeyConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OhttpCryptoTest {

    private fun freshGateway(keyId: Int = 0x55): Pair<Ohttp.GatewayKey, KeyConfig> {
        val sk = X25519.generatePrivateKey()
        val pk = X25519.publicFromPrivate(sk)
        val suite = HpkeSuite.X25519_SHA256_AES128GCM
        val gw = Ohttp.GatewayKey(keyId, suite, sk, pk)
        val cfg = KeyConfig(
            keyId = keyId,
            kemId = 0x0020,
            publicKey = pk,
            symmetricAlgorithms = listOf(KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001)),
        )
        return gw to cfg
    }

    @Test
    fun `request and response round-trip end to end`() {
        val (gw, cfg) = freshGateway()
        val plaintextRequest = "hello-request".toByteArray()

        val enc = Ohttp.encapsulateRequest(cfg, plaintextRequest)
        val dec = Ohttp.decapsulateRequest(gw, enc.ciphertext)
        assertArrayEquals(plaintextRequest, dec.plaintext)

        val plaintextResponse = "hello-response".toByteArray()
        val encResp = Ohttp.encapsulateResponse(dec.context, plaintextResponse)
        val decResp = Ohttp.decapsulateResponse(enc.context, encResp)
        assertArrayEquals(plaintextResponse, decResp)
    }

    @Test
    fun `tampering with ciphertext is rejected`() {
        val (gw, cfg) = freshGateway()
        val enc = Ohttp.encapsulateRequest(cfg, "secret".toByteArray())
        val tampered = enc.ciphertext.copyOf()
        // Flip a bit in the AEAD ciphertext (skip header + enc).
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Ohttp.decapsulateRequest(gw, tampered) }
    }

    @Test
    fun `wrong key id is rejected`() {
        val (_, cfg) = freshGateway(keyId = 0x01)
        val enc = Ohttp.encapsulateRequest(cfg, "x".toByteArray())
        val (otherGw, _) = freshGateway(keyId = 0x02)
        assertThrows(IllegalArgumentException::class.java) { Ohttp.decapsulateRequest(otherGw, enc.ciphertext) }
    }

    @Test
    fun `different requests produce different ciphertexts`() {
        val (_, cfg) = freshGateway()
        val a = Ohttp.encapsulateRequest(cfg, "same".toByteArray())
        val b = Ohttp.encapsulateRequest(cfg, "same".toByteArray())
        // HPKE generates a fresh ephemeral key per call, so enc differs and
        // therefore so does the entire encapsulated request.
        assert(!a.ciphertext.contentEquals(b.ciphertext))
    }
}
