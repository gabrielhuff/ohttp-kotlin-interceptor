package io.github.gabrielhuff.ohttp.internal

import io.github.gabrielhuff.ohttp.OhttpKeyFetchException
import io.github.gabrielhuff.ohttp.OhttpKeyParseException
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Owns the gateway's key configuration (RFC 9458 §3.1): holds the current parsed
 * config in memory, seeds it from optional default bytes, and (re)fetches it from
 * [keyConfigUrl] when needed.
 *
 * The in-memory parsed config is the source of truth. Any [okhttp3.Cache] on
 * [keyConfigClient] only affects how the *fetch* behaves on the wire (conditional
 * requests, persistence across restarts); the manager never relies on it as the
 * primary store, which is why a bad default is simply ignored rather than cached.
 *
 * API:
 * - [get] returns the current key config, fetching (single-flighted) on first use
 * - [refresh] forces a refetch, bypassing any HTTP cache
 *
 * Used by [io.github.gabrielhuff.ohttp.OhttpInterceptor].
 */
internal class KeyConfigManager(
    private val keyConfigUrl: HttpUrl,
    private val keyConfigClient: OkHttpClient,
    defaultKeyConfigBytes: ByteArray?,
) {

    // Best-effort seed: an unparseable default is treated as "no key yet", so the
    // first request fetches instead of failing eagerly. The only path that ever
    // throws is being unable to obtain *any* usable key at request time.
    @Volatile
    private var current: Ohttp.KeyConfig? =
        defaultKeyConfigBytes?.let { runCatching { Ohttp.KeyConfig.parse(it) }.getOrNull() }

    private val fetchLock = Any()

    /**
     * Returns the current key config, fetching on first use. May block on network
     * I/O and may throw [OhttpKeyFetchException] / [OhttpKeyParseException].
     */
    fun get(): Ohttp.KeyConfig = current ?: load(forceNetwork = false)

    /**
     * Forces a refetch of the key config, bypassing any HTTP cache. Throws
     * [OhttpKeyFetchException] / [OhttpKeyParseException] on failure.
     */
    fun refresh() {
        load(forceNetwork = true)
    }

    private fun load(forceNetwork: Boolean): Ohttp.KeyConfig = synchronized(fetchLock) {
        // Concurrent lazy `get()` callers coalesce: whoever wins the lock fetches,
        // the rest see a populated `current` and return it without a second fetch.
        current?.let { if (!forceNetwork) return it }

        val bytes = fetch(forceNetwork)
        val parsed = try {
            Ohttp.KeyConfig.parse(bytes)
        } catch (e: Exception) {
            throw OhttpKeyParseException("failed to parse key configuration from $keyConfigUrl", e)
        }
        current = parsed
        parsed
    }

    private fun fetch(forceNetwork: Boolean): ByteArray {
        val request = Request.Builder()
            .url(keyConfigUrl)
            .header("Accept", KEY_CONFIG_MEDIA_TYPE)
            .apply { if (forceNetwork) cacheControl(CacheControl.FORCE_NETWORK) }
            .build()

        val response = try {
            keyConfigClient.newCall(request).execute()
        } catch (e: IOException) {
            throw OhttpKeyFetchException("failed to fetch key configuration from $keyConfigUrl", e)
        }

        response.use {
            if (!it.isSuccessful) {
                throw OhttpKeyFetchException("key configuration endpoint $keyConfigUrl returned HTTP ${it.code}")
            }
            return it.body?.bytes()
                ?: throw OhttpKeyFetchException("key configuration endpoint $keyConfigUrl returned an empty body")
        }
    }

    private companion object {
        // RFC 9540 §4.1 key configuration media type.
        const val KEY_CONFIG_MEDIA_TYPE = "application/ohttp-keys"
    }
}
