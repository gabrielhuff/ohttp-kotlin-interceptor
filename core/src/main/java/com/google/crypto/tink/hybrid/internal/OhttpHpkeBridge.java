// Lives in com.google.crypto.tink.hybrid.internal so it can call package-private
// helpers (HpkeUtil.{hpkeSuiteId,kemSuiteId} and HpkeKemEncapOutput's accessors)
// that Tink does not export. Without this bridge we would need reflection.
// If Tink ever promotes these APIs to public, delete this class.
package com.google.crypto.tink.hybrid.internal;

import java.security.GeneralSecurityException;

public final class OhttpHpkeBridge {

    private OhttpHpkeBridge() {}

    public static byte[] hpkeSuiteId(byte[] kemId, byte[] kdfId, byte[] aeadId)
            throws GeneralSecurityException {
        return HpkeUtil.hpkeSuiteId(kemId, kdfId, aeadId);
    }

    public static byte[] kemSuiteId(byte[] kemId) throws GeneralSecurityException {
        return HpkeUtil.kemSuiteId(kemId);
    }

    public static final class Encapsulated {
        public final byte[] sharedSecret;
        public final byte[] enc;

        public Encapsulated(byte[] sharedSecret, byte[] enc) {
            this.sharedSecret = sharedSecret;
            this.enc = enc;
        }
    }

    public static Encapsulated encapsulate(HpkeKem kem, byte[] recipientPublicKey)
            throws GeneralSecurityException {
        HpkeKemEncapOutput out = kem.encapsulate(recipientPublicKey);
        return new Encapsulated(out.getSharedSecret(), out.getEncapsulatedKey());
    }
}
