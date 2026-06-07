dependencies {
    // The only crypto dependency. We use Tink's internal HPKE primitives
    // (com.google.crypto.tink.hybrid.internal.*) because the public
    // HybridEncrypt primitive does not expose HPKE Export, which OHTTP
    // (RFC 9458 §4.4) requires.
    api("com.google.crypto.tink:tink:1.21.0")

    // Pure JVM otherwise: BHTTP framing uses java.nio.ByteBuffer +
    // java.io.DataOutputStream. No Okio, no OkHttp on this module's runtime
    // classpath.

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
