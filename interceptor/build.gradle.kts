dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Tink is the only crypto dependency. We use its internal HPKE implementation
    // (com.google.crypto.tink.hybrid.internal.*) because the public HybridEncrypt
    // primitive does not expose HPKE Export, which OHTTP (RFC 9458 §4.4) requires.
    // Pin to a known-good version; revisit if the internal package layout changes.
    api("com.google.crypto.tink:tink:1.21.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
