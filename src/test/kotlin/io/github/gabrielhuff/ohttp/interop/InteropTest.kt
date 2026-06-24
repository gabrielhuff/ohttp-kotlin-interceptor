package io.github.gabrielhuff.ohttp.interop

import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Probes a **real** OHTTP deployment and asserts that fetching a resource
 * through the interceptor yields the same result as fetching it directly — i.e.
 * OHTTP is transparent to the response.
 *
 * This talks to the public internet, so it is **opt-in**: it is skipped unless
 * the relay and target endpoints are supplied. Configure via system properties
 * (or the upper-snake-case env var equivalent, e.g. `OHTTP_INTEROP_RELAY`):
 *
 * - `ohttp.interop.relay`     — the OHTTP relay URL (where encapsulated POSTs go)
 * - `ohttp.interop.target`    — the target origin (its host is what gets intercepted)
 * - `ohttp.interop.keyConfig` — key-config URL (optional; defaults to the RFC 9540 well-known on the target)
 * - `ohttp.interop.path`      — the resource path to GET (default `/`)
 *
 * Candidate target — **Google Safe Browsing**, the most prominent public OHTTP
 * user (Chrome): point `target` at `https://safebrowsing.googleapis.com`, a
 * `path` at a valid v5 endpoint (with your `?key=API_KEY`), `relay` at the Safe
 * Browsing Fastly relay, and `keyConfig` at Google's published key endpoint.
 * Those relay/gateway endpoints are part of Chrome's deployment rather than a
 * casual public API, which is exactly why this test is configuration-driven
 * rather than hard-coded.
 */
class InteropTest {

    private fun config(name: String): String? =
        System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))

    @Test
    fun `OHTTP fetch matches a direct fetch of the same resource`() {
        val relay = config("ohttp.interop.relay")
        val target = config("ohttp.interop.target")
        assumeTrue(relay != null && target != null) {
            "interop test skipped — set -Dohttp.interop.relay and -Dohttp.interop.target to run"
        }

        val targetUrl = target!!.toHttpUrl()
        val resource = targetUrl.newBuilder().encodedPath(config("ohttp.interop.path") ?: "/").build()
        val keyConfigUrl = config("ohttp.interop.keyConfig")?.toHttpUrl()

        val interceptor = if (keyConfigUrl != null) {
            OhttpInterceptor(targetUrl, relay!!.toHttpUrl(), keyConfigUrl)
        } else {
            OhttpInterceptor(targetUrl, relay!!.toHttpUrl())
        }
        val ohttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val directClient = OkHttpClient()

        val direct = directClient.newCall(Request.Builder().url(resource).build()).execute()
        val directCode = direct.code
        val directBody = direct.use { it.body!!.bytes() }

        val viaOhttp = ohttpClient.newCall(Request.Builder().url(resource).build()).execute()
        val ohttpBody = viaOhttp.use { it.body!!.bytes() }

        // OHTTP must be transparent: same status, and a non-empty body that
        // matches the direct fetch (tighten/loosen if the endpoint is dynamic).
        assertEquals(directCode, viaOhttp.code)
        assertTrue(ohttpBody.isNotEmpty(), "OHTTP response body was empty")
        assertEquals(directBody.toList(), ohttpBody.toList())
    }
}
