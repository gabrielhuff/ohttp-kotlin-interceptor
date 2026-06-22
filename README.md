# ohttp-kotlin-interceptor

An OkHttp `Interceptor` that transparently converts regular HTTP(S) requests to
configured target hosts into **Oblivious HTTP** (RFC 9458) exchanges. Drop it
in and your traffic goes through an OHTTP relay; remove it and the same client
makes ordinary HTTP requests. Designed to work with Fastly's OHTTP relay/gateway
deployments and any other RFC 9458 implementation.

Pure JVM — no Android-specific code. A single Gradle module built around OkHttp.
HPKE crypto is provided by BouncyCastle (`org.bouncycastle:bcprov-jdk18on`).

## Layout

A single library module. The public API in `io.github.gabrielhuff.ohttp` is
`OhttpInterceptor` plus the `OhttpException` family it throws. Everything else
(BHTTP framing, HPKE, OHTTP encapsulation, key-config parsing/fetching) lives in
`io.github.gabrielhuff.ohttp.internal` and works directly against OkHttp's
`Request`/`Response` — there are no intermediate, stack-neutral message types.

## Usage

```kotlin
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

val client = OkHttpClient.Builder()
    .addInterceptor(
        OhttpInterceptor(
            gatewayUrl = "https://api.example.com".toHttpUrl(),
            relayUrl = "https://relay.fastly-edge.example/ohttp".toHttpUrl(),
            // Optional: seed the key so the first request needs no fetch.
            // Omit it and the key is pulled from the well-known endpoint instead.
            defaultKeyConfigBytes = keyConfigBytes,
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
* `keyConfigUrl` — where the gateway's OHTTP Key Configuration (RFC 9458 §3.1) is
  fetched from. Defaults to the **RFC 9540 §4.1** well-known endpoint derived
  from `gatewayUrl` (`https://{gateway-host}/.well-known/ohttp-gateway`).
* `keyConfigClient` — the `OkHttpClient` used for that fetch. The default is a
  fresh, cache-less client. Supply one backed by an `okhttp3.Cache` (on Android,
  built from `context.cacheDir`) for persistence, or one routed through the relay
  for stronger metadata privacy.
* `defaultKeyConfigBytes` — optional initial key configuration to seed the
  in-memory cache so the first request needs no round trip. Unparseable bytes are
  ignored — the interceptor just fetches instead.

The encapsulated request is sent to the relay via `chain.proceed`, so the relay
leg reuses the same `OkHttpClient` (connection pool, timeouts, proxy). Because
`chain.proceed` descends to later interceptors and the network rather than
restarting the chain, the relay request is never re-intercepted.

### Key management

The in-memory parsed key configuration is the source of truth (seeded from
`defaultKeyConfigBytes` if given). When the gateway rotates keys, the first
affected request is rejected, the interceptor refetches the config from
`keyConfigUrl`, and retries the request **once**. Callers can also force a
refresh proactively — e.g. on app foreground:

```kotlin
val interceptor = OhttpInterceptor(gatewayUrl, relayUrl)
// ...
interceptor.refreshKey() // blocking; throws OhttpKeyFetchException / OhttpKeyParseException
```

### Errors

Every interceptor-originated failure is a `sealed class OhttpException : IOException`,
so existing `catch (IOException)` handlers keep working while callers can
`when`-match for detail:

| Exception | Meaning |
|---|---|
| `OhttpKeyParseException` | key bytes (default or fetched) are not a valid configuration |
| `OhttpKeyFetchException` | couldn't download the key config (network, non-2xx, empty body) |
| `OhttpKeyMismatchException` | gateway rejected the request even after a refresh (key outdated/not registered) |
| `OhttpRequestEncodingException` | request can't be encapsulated (e.g. a streaming/duplex body — OHTTP needs a bufferable request) |
| `OhttpUnexpectedResponseException` | relay returned an unexpected status, content type, or empty body |
| `OhttpDecapsulationException` | response was OHTTP-shaped but couldn't be decrypted/decoded |

Transport errors talking to the relay (relay unreachable, socket timeouts) and
call cancellation propagate as plain `IOException`, exactly as for any other
OkHttp call.

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
client using only our own implementation — an `InProcessRelay`,
`InProcessGateway`, and `InProcessKeyDistributor` (aggregated by
`InProcessOhttpInfra`), all backed by `MockWebServer`. The gateway is the exact
mirror of the client, driving the same symmetric `Ohttp` methods in reverse. It
verifies the encapsulated
round trip for GET and POST, that unconfigured hosts pass through untouched,
that the relay only ever sees opaque encapsulated bytes, and the key-management
paths: fetch-on-first-use, automatic refresh-and-retry after the gateway rotates
keys, and the `OhttpKeyFetchException` / `OhttpKeyMismatchException` failure
modes.

Broader automated coverage (BHTTP/varint/key-config unit tests, reference-
implementation interop) is intended to follow.

Run the tests with:

```
gradle test
```
