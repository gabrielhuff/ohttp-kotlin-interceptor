# ohttp-kotlin-interceptor

An OkHttp `Interceptor` that transparently converts regular HTTP(S) requests to
configured target hosts into **Oblivious HTTP** (RFC 9458) exchanges. Drop it
in and your traffic goes through an OHTTP relay; remove it and the same client
makes ordinary HTTP requests. Designed to work with Fastly's OHTTP relay/gateway
deployments and any other RFC 9458 implementation.

Pure JVM — no Android-specific code. Crypto is provided exclusively by
Google Tink (`com.google.crypto.tink:tink:1.21.0`).

## Modules

| Module        | Artifact                   | Purpose                                                                                                                                                                                                                  |
|---------------|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`        | `ohttp-kotlin-core`        | HTTP-stack-agnostic implementation: BHTTP (RFC 9292), HPKE Base + Export on Tink primitives, OHTTP encapsulation (RFC 9458), `KeyConfig`, `OhttpConfig`. Depends only on Tink + Okio. No OkHttp.                          |
| `interceptor` | `ohttp-kotlin-interceptor` | `OhttpInterceptor` for OkHttp, plus the small `OkHttpBhttpAdapter` that translates between OkHttp `Request`/`Response` and `:core`'s neutral BHTTP types.                                                                  |
| `testing`     | `ohttp-kotlin-testing`     | `InProcessRelay` (fake Fastly) and `InProcessGateway` for integration tests.                                                                                                                                              |
| `cronet`      | `ohttp-kotlin-cronet`      | `OhttpCronetEngine` / `OhttpUrlRequest` — Cronet-native wrapper that talks straight to `:core` (no OkHttp on the runtime classpath). Cronet API is compileOnly; consumers add `org.chromium.net:cronet-api` from Google Maven. |

## Usage

```kotlin
import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

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

* **HPKE base-mode**, suite **DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 /
  AES-128-GCM** (Fastly/Cloudflare default). Adding more suites is a matter of
  extending `HpkeSuite` and `KeyConfig.pickSupportedSuite`.
* The HPKE KEM, KDF, and AEAD primitives come from Tink. Because Tink's
  `HpkeContext` does not expose HPKE `Export`, we re-implement the **KeySchedule**
  on top of Tink's `HpkeKdf`/`HpkeAead` primitives and derive the response key
  per RFC 9458 §4.5 with plain HKDF (JDK `Mac`).
* A small Java shim in `com.google.crypto.tink.hybrid.internal.OhttpHpkeBridge`
  exposes two package-private Tink helpers (`hpkeSuiteId`, `kem.encapsulate`)
  rather than using reflection. If a future Tink release promotes them, the
  bridge can be deleted.

## Binary HTTP

`io.github.gabrielhuff.ohttp.internal.Bhttp` implements **known-length**
request/response framing only — that's what RFC 9458 §4 mandates for OHTTP.
Indeterminate-length framing is intentionally not implemented.

## In-process relay & gateway

```kotlin
import io.github.gabrielhuff.ohttp.testing.InProcessGateway
import io.github.gabrielhuff.ohttp.testing.InProcessRelay

val origin = MockWebServer().apply { start() }
val gateway = InProcessGateway(hostRewriter = { it.newBuilder().host(origin.hostName).port(origin.port).scheme("http").build() })
val relay = InProcessRelay(gatewayUrl = gateway.url)

val client = OkHttpClient.Builder()
    .addInterceptor(OhttpInterceptor(mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes))))
    .build()
```

`InProcessRelay` is a real OHTTP relay: it forwards `message/ohttp-req` byte
streams to the configured gateway without decrypting them.

## Validation

The unit tests in `interceptor/src/test/` cover BHTTP, QUIC varints, RFC 9458
key configs (including the appendix test vector), and the OHTTP request/response
crypto round-trip.

The `testing` module's tests cover:

1. **End-to-end self-loop** — `EndToEndTest` exercises
   client → relay → gateway → origin → gateway → relay → client using only
   our own implementation. Verifies the interceptor is the only thing standing
   between OHTTP and plain HTTP.

2. **Reference gateway interop** — `ReferenceGatewayInteropTest` runs the
   same loop against [`martinthomson/ohttp`](https://github.com/martinthomson/ohttp),
   the OHTTP spec author's Rust implementation (using the `rust-hpke`
   backend, so no NSS install required). The wrapper binary lives in
   `interop/reference-gateway`; the Gradle test task builds it automatically
   when `cargo` is available on PATH and skips the interop test cleanly
   otherwise.

3. **Reference relay interop** — `ReferenceRelayInteropTest` chains a
   second reference: [`payjoin/ohttp-relay`](https://github.com/payjoin/ohttp-relay)
   (pure-Rust on hyper/tokio; the closest equivalent to a martinthomson-
   maintained relay, which doesn't exist). Topology: client →
   reference-relay (payjoin) → reference-gateway (martinthomson) → origin.
   Validates wire-level HTTP plumbing across two independent implementations.
   The relay performs a BIP77 opt-in probe of the gateway before forwarding;
   our reference gateway answers that probe positively so the chain can
   form.

Run everything with:

```
gradle test
```

## Cronet integration

The `cronet` module wraps a `CronetEngine`. Build a Cronet `UrlRequest` via
`OhttpCronetEngine` instead of the engine directly:

```kotlin
val ohttpEngine = OhttpCronetEngine(
    delegate = realCronetEngine,
    configs = mapOf("api.example.com" to OhttpConfig(relayUrl, keyConfigBytes)),
)

val request = ohttpEngine.newUrlRequestBuilder(
    "https://api.example.com/v1/things",
    callback,
    callbackExecutor,
)
    .setHttpMethod("POST")
    .addHeader("Content-Type", "application/json")
    .setUploadDataProvider(uploadProvider, uploadExecutor)
    .build()

request.start()
```

Requests to hosts not in the map fall through to the wrapped `CronetEngine`
verbatim. The relay leg also goes via the wrapped engine, so HTTP/3 and
connection migration apply to client→relay traffic.

The module depends on Cronet at **compile time only**. Add the real artifact
in your app's build:

```kotlin
implementation("org.chromium.net:cronet-api:119.6045.31")
// And one of the Cronet implementations: cronet-embedded (full),
// Google Play Services provider, or HttpEngine.
```

(Cronet artifacts are hosted on `https://maven.google.com/`; add that repo to
your build if it isn't already.)

> **Building this repo:** `cronet-api` lives only on Google Maven, not Maven
> Central. Until the build can resolve it from there, the module compiles and
> tests against a vendored copy of the real API jar under `cronet/libs/`
> (wired `compileOnly`, so it never enters the published jar). Swap the
> `files(...)` dependency in `cronet/build.gradle.kts` for the Maven
> coordinate above once Google Maven is reachable from your build environment.

### Known limitations vs. native Cronet

- **One-shot.** OHTTP doesn't stream, so request/response bodies are buffered
  end to end. Don't use this path for large uploads/downloads if memory is
  tight.
- **No transparent redirect following.** `UrlRequest.followRedirect()` throws.
  3xx responses are surfaced as the final response; follow them yourself if
  needed.
- **`getStatus`** returns a coarse-grained status (engine internals aren't
  visible to the wrapper).
