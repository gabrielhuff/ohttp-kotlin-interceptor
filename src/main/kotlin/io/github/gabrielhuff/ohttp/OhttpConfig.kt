package io.github.gabrielhuff.ohttp

/**
 * Per-target configuration. Wraps the relay URL the encapsulated request
 * should be POSTed to, plus the gateway's published OHTTP key configuration
 * (RFC 9458 §3.1) used to encrypt to that gateway.
 *
 * Pass [keyConfigBytes] exactly as fetched from the source (e.g. Fastly's
 * `/.well-known/ohttp-gateway`); the byte format is stable and survives key
 * rotation, so callers just swap the bytes when the gateway publishes a new
 * configuration.
 */
public class OhttpConfig(
    public val relayUrl: String,
    public val keyConfigBytes: ByteArray,
) {
    internal val keyConfig: KeyConfig = KeyConfig.parse(keyConfigBytes)
}
