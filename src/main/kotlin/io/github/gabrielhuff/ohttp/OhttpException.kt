package io.github.gabrielhuff.ohttp

import java.io.IOException

/**
 * Base type for every failure originating inside [OhttpInterceptor]. It extends
 * [IOException] so existing `catch (IOException)` handlers around OkHttp calls
 * keep working unchanged; callers wanting to react to a specific failure mode
 * can `when`-match on the sealed subtypes.
 *
 * Note that not every failure surfaced by an OHTTP call is an [OhttpException]:
 * transport errors talking to the relay (the relay being unreachable, socket
 * timeouts) and call cancellation propagate as plain [IOException], exactly as
 * they would for any other OkHttp request.
 */
public sealed class OhttpException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The gateway rejected the encapsulated request and a key refresh did not
 * resolve it — the client's key configuration is outdated or not registered
 * with the gateway. There is no standard "wrong key" status in RFC 9458, so
 * this is inferred from the relay/gateway returning a 4xx after a refresh.
 *
 * @property code the HTTP status observed from the relay, when available.
 */
public class OhttpKeyMismatchException(
    message: String,
    public val code: Int? = null,
    cause: Throwable? = null,
) : OhttpException(message, cause)

/** A key configuration could not be downloaded (network error, non-2xx from the key endpoint, empty body, or cache read failure). */
public class OhttpKeyFetchException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, cause)

/** A key configuration's bytes could not be parsed (RFC 9458 §3.1), whether the seeded default or a downloaded one. */
public class OhttpKeyParseException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, cause)

/**
 * The outgoing request could not be turned into an encapsulated OHTTP request —
 * BHTTP encoding or HPKE sealing failed. The most common cause is a streaming or
 * duplex request body: OHTTP requires a fully bufferable request. This failure
 * is deterministic for a given request and should not be retried.
 */
public class OhttpRequestEncodingException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, cause)

/**
 * The relay returned something we could not treat as an OHTTP response at the
 * HTTP layer: an unexpected status code, a wrong/missing content type, or an
 * empty body.
 *
 * @property code the HTTP status observed from the relay, when available.
 */
public class OhttpUnexpectedResponseException(
    message: String,
    public val code: Int? = null,
    cause: Throwable? = null,
) : OhttpException(message, cause)

/** The relay returned an OHTTP-shaped response, but it could not be HPKE-opened or BHTTP-decoded (corruption, or a context/encoding bug). */
public class OhttpDecapsulationException(
    message: String,
    cause: Throwable? = null,
) : OhttpException(message, cause)
