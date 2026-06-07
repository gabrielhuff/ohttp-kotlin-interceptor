package io.github.gabrielhuff.ohttp.testing

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.OhttpInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.HexFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Interop test: drives the Kotlin OHTTP client against
 * Martin Thomson's `ohttp` Rust crate — the spec author's reference
 * implementation, with `rust-hpke` as the HPKE backend. Validates wire-level
 * compatibility with an independent implementation by the RFC's author.
 *
 * Skipped automatically when the reference gateway binary isn't built; build it
 * with `./gradlew :testing:buildReferenceGateway` (or `cargo build --release`
 * directly in `interop/reference-gateway`).
 */
@EnabledIf("io.github.gabrielhuff.ohttp.testing.ReferenceGatewayInteropTest#binaryAvailable")
class ReferenceGatewayInteropTest {

    private lateinit var origin: MockWebServer
    private lateinit var gatewayProcess: Process
    private lateinit var gatewayKeyConfigBytes: ByteArray
    private lateinit var gatewayAddress: String
    private lateinit var relay: InProcessRelay

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gatewayProcess = ProcessBuilder(
            binaryPath().absolutePath,
            "--addr", "127.0.0.1:0",
            "--origin", "http://${origin.hostName}:${origin.port}",
            "--key-id", "1",
        ).redirectErrorStream(false).start()

        val (kc, addr) = readGatewayStartup(gatewayProcess)
        gatewayKeyConfigBytes = HexFormat.of().parseHex(kc)
        gatewayAddress = addr

        relay = InProcessRelay(gatewayUrl = "http://$gatewayAddress/ohttp".toHttpUrl())
    }

    @AfterEach
    fun tearDown() {
        relay.close()
        gatewayProcess.destroy()
        if (!gatewayProcess.waitFor(2, TimeUnit.SECONDS)) gatewayProcess.destroyForcibly()
        origin.shutdown()
    }

    @Test
    fun `GET round-trip via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"interop":"ok"}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(
                OhttpInterceptor(
                    mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gatewayKeyConfigBytes))
                )
            )
            .build()

        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/ping?n=1")
                .header("X-Trace", "interop-42")
                .build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("""{"interop":"ok"}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("GET", originReq.method)
        assertEquals("/v1/ping?n=1", originReq.path)
        assertEquals("interop-42", originReq.getHeader("X-Trace"))
    }

    @Test
    fun `POST round-trip via reference gateway`() {
        origin.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":99}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(
                OhttpInterceptor(
                    mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gatewayKeyConfigBytes))
                )
            )
            .build()

        val payload = """{"value":"crypto-interop"}"""
        val response = client.newCall(
            Request.Builder()
                .url("https://api.example.com/v1/widgets")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

        assertEquals(201, response.code)
        assertEquals("""{"id":99}""", response.body!!.string())

        val originReq = origin.takeRequest()
        assertEquals("POST", originReq.method)
        assertEquals(payload, originReq.body.readUtf8())
    }

    companion object {
        @JvmStatic
        fun binaryAvailable(): Boolean = binaryPath().canExecute()

        internal fun binaryPath(): File {
            val override = System.getenv("OHTTP_REFERENCE_GATEWAY")
            if (override != null) return File(override)
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return File(dir, "interop/reference-gateway/target/release/ohttp-reference-gateway")
        }

        internal data class Startup(val keyConfigHex: String, val address: String)

        internal fun readGatewayStartup(process: Process): Startup {
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
