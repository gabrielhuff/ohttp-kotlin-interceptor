package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Interop test using payjoin/ohttp-relay (the closest pure-Rust relay
 * binary built on the same hyper/tokio stack as Martin Thomson's `ohttp`
 * crate; there's no martinthomson/ohttp-relay).
 *
 * Topology:
 *   client (Kotlin) -> reference-relay (Rust) -> reference-gateway (Rust)
 *                                                -> MockWebServer (origin)
 *
 * This exercises the relay's full HTTP path independently of our
 * `InProcessRelay`, which catches any wire-level oddity in our Content-Type
 * handling, request shape, or error semantics.
 *
 * Skipped automatically when either reference binary is missing; build both
 * with `./gradlew :testing:buildReferenceGateway :testing:buildReferenceRelay`.
 *
 * Note: payjoin's relay performs a BIP77 opt-in probe against the gateway's
 * `/.well-known/ohttp-gateway?allowed_purposes`. Our reference gateway
 * answers that probe positively so the relay accepts it as a forwarding
 * target.
 */
@EnabledIf("io.github.gabrielhuff.ohttp.testing.ReferenceRelayInteropTest#binariesAvailable")
class ReferenceRelayInteropTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: ReferenceGateway
    private lateinit var relayProcess: Process
    private lateinit var gatewayKeyConfigBytes: ByteArray
    private lateinit var relayAddress: String

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }

        gateway = ReferenceGateway(originUrl = "http://${origin.hostName}:${origin.port}")
        gatewayKeyConfigBytes = gateway.keyConfigBytes

        val relayBinary = relayBinaryPath()
        relayProcess = ProcessBuilder(
            relayBinary.absolutePath,
            "--gateway", "http://${gateway.address}",
        ).redirectErrorStream(false).start()
        relayAddress = readRelayStartup(relayProcess)
    }

    @AfterEach
    fun tearDown() {
        relayProcess.destroy()
        if (!relayProcess.waitFor(2, TimeUnit.SECONDS)) relayProcess.destroyForcibly()
        gateway.close()
        origin.shutdown()
    }

    @Test
    fun `GET round-trip via reference relay and reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"interop":"relay+gateway"}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(
                OhttpInterceptor(
                    mapOf("api.example.com" to OhttpConfig("http://$relayAddress/".toHttpUrl().toString(), gatewayKeyConfigBytes))
                )
            )
            .build()

        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/status")
                .header("X-Source", "ref-relay-test")
                .build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("""{"interop":"relay+gateway"}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("GET", originReq.method)
        assertEquals("/v1/status", originReq.path)
        assertEquals("ref-relay-test", originReq.getHeader("X-Source"))
    }

    companion object {
        @JvmStatic
        fun binariesAvailable(): Boolean =
            ReferenceGateway.isAvailable() && relayBinaryPath().canExecute()

        private fun relayBinaryPath(): File {
            val override = System.getenv("OHTTP_REFERENCE_RELAY")
            if (override != null) return File(override)
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return File(dir, "interop/reference-relay/target/release/ohttp-reference-relay")
        }

        private fun readRelayStartup(process: Process): String {
            val q = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(process.errorStream))
            Thread({
                reader.useLines { lines -> lines.forEach { q.put(it) } }
            }, "ref-relay-stderr-drain").apply { isDaemon = true }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val remaining = deadline - System.nanoTime()
                val line = q.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
                if (line.startsWith("listening=")) return line.removePrefix("listening=").trim()
            }
            error("reference relay did not emit listening address")
        }
    }
}
