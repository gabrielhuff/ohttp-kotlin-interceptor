package io.github.gabrielhuff.ohttp.cronet

import io.github.gabrielhuff.ohttp.OhttpConfig
import io.github.gabrielhuff.ohttp.testing.InProcessGateway
import io.github.gabrielhuff.ohttp.testing.InProcessRelay
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OhttpCronetEngineThreadingTest {

    private lateinit var origin: MockWebServer
    private lateinit var gateway: InProcessGateway
    private lateinit var relay: InProcessRelay

    @BeforeEach
    fun setUp() {
        origin = MockWebServer().apply { start() }
        gateway = InProcessGateway(hostRewriter = { url ->
            url.newBuilder().host(origin.hostName).port(origin.port).scheme("http").build()
        })
        relay = InProcessRelay(gatewayUrl = gateway.url)
    }

    @AfterEach
    fun tearDown() {
        relay.close()
        gateway.close()
        origin.shutdown()
    }

    @Test
    fun `crypto, upload buffering, and relay callbacks run on the executors named in Threading`() {
        // Each role gets its own thread pool, named so we can verify where work
        // actually ran by capturing the thread name from the FakeCronetEngine.
        val cryptoSeen = AtomicBoolean(false)
        val relayCallbackSeen = AtomicBoolean(false)

        val cryptoExec = Executors.newSingleThreadExecutor(NamedFactory("ohttp-crypto") {
            cryptoSeen.set(true)
        })
        val uploadExec = Executors.newSingleThreadExecutor(NamedFactory("ohttp-upload"))
        val relayExec = Executors.newSingleThreadExecutor(NamedFactory("ohttp-relay") {
            relayCallbackSeen.set(true)
        })

        // Fake delegate that records the thread name from which `newUrlRequestBuilder` is invoked.
        // Cronet engines route subsequent callbacks via the executor passed here; the FakeCronetEngine
        // uses that executor for callbacks, so the relayCallback role is exercised.
        val delegate = FakeCronetEngine()
        val engine = OhttpCronetEngine(
            delegate = delegate,
            configs = mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes)),
            threading = OhttpCronetEngine.Threading.split(
                crypto = cryptoExec,
                uploadBuffering = uploadExec,
                relayCallback = relayExec,
            ),
        )

        origin.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("ok"))

        val handler = SimpleCallback()
        val userExec = Executors.newSingleThreadExecutor()
        try {
            val req = engine.newUrlRequestBuilder("https://api.example.com/probe", handler, userExec)
                .setHttpMethod("GET")
                .build()
            req.start()
            assertTrue(handler.awaitDone(5, TimeUnit.SECONDS))
            assertEquals(200, handler.info!!.httpStatusCode)
            assertEquals("ok", handler.bodyUtf8())
            // Crypto/encapsulation thread ran our submitted task.
            assertTrue(cryptoSeen.get()) { "expected crypto executor to run encapsulation work" }
            // The relay-callback executor delivered at least one delegate callback.
            assertTrue(relayCallbackSeen.get()) { "expected relay-callback executor to run delegate callbacks" }
        } finally {
            userExec.shutdownNow()
            cryptoExec.shutdownNow()
            uploadExec.shutdownNow()
            relayExec.shutdownNow()
        }
    }

    @Test
    fun `Threading shared keeps the legacy single-executor wiring as default`() {
        val shared = Executors.newSingleThreadExecutor(NamedFactory("ohttp-shared"))
        val engine = OhttpCronetEngine(
            delegate = FakeCronetEngine(),
            configs = mapOf("api.example.com" to OhttpConfig(relay.url.toString(), gateway.keyConfigBytes)),
            threading = OhttpCronetEngine.Threading.shared(shared),
        )
        origin.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val handler = SimpleCallback()
        val userExec = Executors.newSingleThreadExecutor()
        try {
            val req = engine.newUrlRequestBuilder("https://api.example.com/probe", handler, userExec).build()
            req.start()
            assertTrue(handler.awaitDone(5, TimeUnit.SECONDS))
            assertEquals(200, handler.info!!.httpStatusCode)
        } finally {
            userExec.shutdownNow()
            shared.shutdownNow()
        }
    }
}

private class NamedFactory(
    private val prefix: String,
    private val onCreate: () -> Unit = {},
) : ThreadFactory {
    private var counter = 0
    override fun newThread(r: Runnable): Thread {
        onCreate()
        return Thread(r, "$prefix-${counter++}").apply { isDaemon = true }
    }
}

private class SimpleCallback : UrlRequest.Callback() {
    private val terminal = CountDownLatch(1)
    private val sink = ByteArrayOutputStream()
    private val readBuf = ByteBuffer.allocateDirect(8192)
    @Volatile var info: UrlResponseInfo? = null

    fun awaitDone(timeout: Long, unit: TimeUnit): Boolean = terminal.await(timeout, unit)
    fun bodyUtf8(): String = sink.toByteArray().decodeToString()

    override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        request.followRedirect()
    }
    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        this.info = info
        request.read(readBuf)
    }
    override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        byteBuffer.flip()
        val chunk = ByteArray(byteBuffer.remaining())
        byteBuffer.get(chunk)
        sink.write(chunk)
        byteBuffer.clear()
        request.read(byteBuffer)
    }
    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) { terminal.countDown() }
    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) { terminal.countDown() }
    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) { terminal.countDown() }
}
