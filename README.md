# ohttp-kotlin-interceptor

An OkHttp `Interceptor` that transparently converts regular HTTP(S) requests to
configured target hosts into **Oblivious HTTP** (RFC 9458) exchanges. Drop it
in and your traffic goes through an OHTTP relay; remove it and the same client
makes ordinary HTTP requests. Designed to work with Fastly's OHTTP relay/gateway
deployments and any other RFC 9458 implementation.

Pure JVM — no Android-specific code. A single Gradle module built around OkHttp.
HPKE crypto is provided by BouncyCastle (`org.bouncycastle:bcprov-jdk18on`).

## Layout

A single library module. The public API is one type — `OhttpInterceptor` in
`io.github.gabrielhuff.ohttp`. Everything else (BHTTP framing, HPKE, OHTTP
encapsulation, key-config parsing) lives in `io.github.gabrielhuff.ohttp.internal`
and works directly against OkHttp's `Request`/`Response` — there are no
intermediate, stack-neutral message types.

## Usage

```kotlin
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

val keyConfigBytes: ByteArray = /* Fetch from /.well-known/ohttp-gateway or ship out-of-band */
val client = OkHttpClient.Builder()
    .addInterceptor(
        OhttpInterceptor(
            gatewayUrl = "https://api.example.com".toHttpUrl(),
            relayUrl = "https://relay.fastly-edge.example/ohttp".toHttpUrl(),
            keyConfigBytes = keyConfigBytes,
        )
    )
    .build()

// Calls to https://api.example.com/... now go via OHTTP. Calls to any other
// host pass through untouched.
client.newCall(Request.Builder().url("https://api.example.com/v1/things").build()).execute()
```

### One gateway per interceptor

Each `OhttpInterceptor` handles a single gateway. To proxy more than one
gateway, install one interceptor per gateway on the same client — each only
acts on requests whose host matches its own `gatewayUrl` and passes everything
else through.

### Configuration model

* `gatewayUrl` — the gateway being proxied. Requests are intercepted when their
  host matches `gatewayUrl.host`; all other requests pass through untouched.
* `relayUrl` — where the encapsulated `POST` is sent. Typically your Fastly
  relay endpoint.
* `keyConfigBytes` — the gateway's published OHTTP Key Configuration
  (RFC 9458 §3.1), opaque bytes. We deliberately accept the wire bytes rather
  than a parsed structure so that **rotating keys is a byte-array swap**. It is
  nullable to reserve room for future auto-discovery from `gatewayUrl`; until
  then a `null` (or unparseable) value throws on first use.

The encapsulated request is sent to the relay via `chain.proceed`, so the relay
leg reuses the same `OkHttpClient` (connection pool, timeouts, proxy). Because
`chain.proceed` descends to later interceptors and the network rather than
restarting the chain, the relay request is never re-intercepted.

## Crypto details

* **HPKE base-mode**, default suite **DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 /
  AES-128-GCM** (Fastly/Cloudflare default). The published key configuration
  selects the suite; the key-config parser accepts the four KEMs and three
  KDF/AEAD pairs in the RFC 9180 §7 registry that BouncyCastle implements.
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
