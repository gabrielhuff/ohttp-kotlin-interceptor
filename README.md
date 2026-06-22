# ohttp-kotlin-interceptor

An OkHttp `Interceptor` that transparently converts regular HTTP(S) requests to
configured target hosts into **Oblivious HTTP** (RFC 9458) exchanges. Drop it
in and your traffic goes through an OHTTP relay; remove it and the same client
makes ordinary HTTP requests. Designed to work with Fastly's OHTTP relay/gateway
deployments and any other RFC 9458 implementation.

Pure JVM — no Android-specific code. A single Gradle module built around OkHttp.
HPKE crypto is provided by BouncyCastle (`org.bouncycastle:bcprov-jdk18on`).

## Layout

A single library module. The public API is three types in
`io.github.gabrielhuff.ohttp`:

* `OhttpInterceptor` — the OkHttp `Interceptor`.
* `OhttpConfig` — per-target relay URL + gateway key configuration.
* `KeyConfig` — parses/serializes an RFC 9458 §3.1 key configuration.

Everything else (BHTTP framing, HPKE, OHTTP encapsulation) lives in
`io.github.gabrielhuff.ohttp.internal` and works directly against OkHttp's
`Request`/`Response` — there are no intermediate, stack-neutral message types.

## Usage

```kotlin
import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request

val keyConfigBytes: ByteArray = /* Fetch from /.well-known/ohttp-gateway or ship out-of-band */
val client = OkHttpClient.Builder()
    .addInterceptor(
        OhttpInterceptor(
            mapOf(
                "api.example.com" to OhttpConfig(
                    relayUrl = "https://relay.fastly-edge.example/ohttp",
                    keyConfigBytes = keyConfigBytes,
                ),
            )
        )
    )
    .build()

// Calls to https://api.example.com/... now go via OHTTP. Calls to any other
// host pass through untouched.
client.newCall(Request.Builder().url("https://api.example.com/v1/things").build()).execute()
```

### Removing the interceptor

Drop it from the `OkHttpClient.Builder`, or supply an empty map. Either way,
the rest of your client stack is unaffected — the same OkHttp instance happily
makes plain HTTP requests.

### Configuration model

The interceptor takes a `Map<String, OhttpConfig>` keyed by **target hostname**
(exact match against `HttpUrl.host`). Each `OhttpConfig` bundles:

* `relayUrl` — where the encapsulated `POST` is sent. Typically your Fastly
  relay endpoint.
* `keyConfigBytes` — the gateway's published OHTTP Key Configuration
  (RFC 9458 §3.1), opaque bytes. We deliberately accept the wire bytes rather
  than a parsed structure so that **rotating keys is a byte-array swap**.

The interceptor uses a dedicated `OkHttpClient` to talk to the relay so the
relay request is **not** re-intercepted. Provide your own via the second
constructor argument if you need custom timeouts, proxy, etc.

## Crypto details

* **HPKE base-mode**, default suite **DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 /
  AES-128-GCM** (Fastly/Cloudflare default). The published key configuration
  selects the suite; `KeyConfig.pickSupportedSuite` accepts the four KEMs and
  three KDF/AEAD pairs in the RFC 9180 §7 registry that BouncyCastle implements.
* The HPKE KEM/KDF/AEAD primitives, the key schedule, and `Export` all come from
  BouncyCastle's public `org.bouncycastle.crypto.hpke.HPKEContext`. Because that
  type exposes `Export` (and raw HKDF `Extract`/`Expand`) directly, the OHTTP
  response key derivation (RFC 9458 §4.5) is a handful of calls with no
  library-internal access and no hand-rolled key schedule.

## Binary HTTP

`io.github.gabrielhuff.ohttp.internal.Bhttp` implements **known-length**
request/response framing only — that's what RFC 9458 §4 mandates for OHTTP.
Indeterminate-length framing is intentionally not implemented. It translates
straight to and from OkHttp `Request`/`Response`.

## Validation

`EndToEndTest` exercises client → relay → gateway → origin → gateway → relay →
client using only our own implementation (an `InProcessRelay` and
`InProcessGateway` backed by `MockWebServer`). It verifies the encapsulated
round trip for GET and POST, that unconfigured hosts pass through untouched,
and that the relay only ever sees opaque encapsulated bytes.

Broader automated coverage (BHTTP/varint/key-config unit tests, reference-
implementation interop) is intended to follow.

Run the tests with:

```
gradle test
```
