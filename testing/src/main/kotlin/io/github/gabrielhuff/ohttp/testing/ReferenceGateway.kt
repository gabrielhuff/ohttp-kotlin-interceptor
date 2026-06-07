package io.github.gabrielhuff.ohttp.testing

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.util.HexFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Launches Martin Thomson's `ohttp` Rust reference gateway (the spec author's
 * implementation) as a subprocess and exposes its key configuration and listen
 * address. Used by interop tests to validate the Kotlin OHTTP client's wire
 * format against an independent implementation.
 *
 * The gateway decapsulates incoming OHTTP requests and forwards the decoded
 * HTTP request to [originUrl]. Construction blocks until the gateway has
 * reported its key config and listen address on stderr; [close] stops it.
 *
 * The binary is built by `./gradlew :testing:buildReferenceGateway` (a no-op
 * when `cargo` isn't on PATH). Gate tests on [isAvailable] so they skip
 * automatically when it hasn't been built.
 */
public class ReferenceGateway @JvmOverloads constructor(
    originUrl: String,
    keyId: Int = 1,
) : Closeable {

    private val process: Process

    /** The gateway's HPKE key configuration, as the client-facing wire bytes. */
    public val keyConfigBytes: ByteArray

    /** The gateway's `host:port` listen address. */
    public val address: String

    init {
        process = ProcessBuilder(
            binaryPath().absolutePath,
            "--addr", "127.0.0.1:0",
            "--origin", originUrl,
            "--key-id", keyId.toString(),
        ).redirectErrorStream(false).start()

        val startup = readStartup(process)
        keyConfigBytes = HexFormat.of().parseHex(startup.keyConfigHex)
        address = startup.address
    }

    /** The gateway's OHTTP endpoint, suitable as [InProcessRelay]'s `gatewayUrl`. */
    public val ohttpUrl: String
        get() = "http://$address/ohttp"

    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    public companion object {
        /** True when the reference gateway binary has been built and is runnable. */
        @JvmStatic
        public fun isAvailable(): Boolean = binaryPath().canExecute()

        /**
         * Location of the reference gateway binary. Honors the
         * `OHTTP_REFERENCE_GATEWAY` env override, otherwise resolves it relative
         * to the repo root (located by walking up to `settings.gradle.kts`).
         */
        @JvmStatic
        public fun binaryPath(): File {
            System.getenv("OHTTP_REFERENCE_GATEWAY")?.let { return File(it) }
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return File(dir, "interop/reference-gateway/target/release/ohttp-reference-gateway")
        }

        private data class Startup(val keyConfigHex: String, val address: String)

        private fun readStartup(process: Process): Startup {
            val q = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(process.errorStream))
            Thread({
                reader.useLines { lines -> lines.forEach { q.put(it) } }
            }, "ref-gateway-stderr-drain").apply { isDaemon = true }.start()

            var keyConfig: String? = null
            var address: String? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && (keyConfig == null || address == null)) {
                val remaining = deadline - System.nanoTime()
                val line = q.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                when {
                    line.startsWith("keyConfigHex=") -> keyConfig = line.removePrefix("keyConfigHex=").trim()
                    line.startsWith("listening=") -> address = line.removePrefix("listening=").trim()
                }
            }
            return Startup(
                keyConfig ?: error("reference gateway did not emit keyConfigHex"),
                address ?: error("reference gateway did not emit listening address"),
            )
        }
    }
}
