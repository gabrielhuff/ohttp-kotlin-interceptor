package io.github.gabrielhuff.ohttp.internal

import io.github.gabrielhuff.ohttp.internal.Ohttp.KeyConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class KeyConfigManagerTest {

    private lateinit var server: MockWebServer
    private val hits = AtomicInteger(0)

    @Volatile
    private var served: ByteArray = ByteArray(0)

    @BeforeEach
    fun setUp() {
        hits.set(0)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/ohttp-keys")
                    .setBody(okio.Buffer().write(served))
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // Parseable "application/ohttp-keys" bytes — no real crypto needed, the manager only parses.
    private fun keyConfigBytes(keyId: Int): ByteArray =
        KeyConfig.serializeKeys(
            listOf(KeyConfig(keyId, 0x0020, ByteArray(32) { it.toByte() }, listOf(KeyConfig.SymmetricAlgorithmPair(0x0001, 0x0001)))),
        )

    private fun manager(default: ByteArray? = null) =
        KeyConfigManager(server.url("/keys"), OkHttpClient(), default)

    @Test
    fun `an unparseable default is ignored and the key is fetched instead`() {
        served = keyConfigBytes(keyId = 7)
        val manager = manager(default = byteArrayOf(1, 2, 3))

        assertEquals(7, manager.get().keyId)
        assertEquals(1, hits.get())
    }

    @Test
    fun `a parseable default is used without any fetch`() {
        served = keyConfigBytes(keyId = 7) // would be served, but must not be reached
        val manager = manager(default = keyConfigBytes(keyId = 9))

        assertEquals(9, manager.get().keyId)
        assertEquals(0, hits.get())
    }

    @Test
    fun `concurrent first-use calls coalesce into a single fetch`() {
        served = keyConfigBytes(keyId = 1)
        val manager = manager()

        val threadCount = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val keyIds = ConcurrentLinkedQueue<Int>()
        repeat(threadCount) {
            thread {
                start.await()
                keyIds.add(manager.get().keyId)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS), "threads did not finish")

        assertEquals(1, hits.get(), "expected a single coalesced fetch")
        assertEquals(threadCount, keyIds.size)
        assertTrue(keyIds.all { it == 1 })
    }

    @Test
    fun `refresh forces a refetch and picks up rotated bytes`() {
        served = keyConfigBytes(keyId = 1)
        val manager = manager()
        assertEquals(1, manager.get().keyId)
        assertEquals(1, hits.get())

        served = keyConfigBytes(keyId = 2)
        manager.refresh()

        assertEquals(2, manager.get().keyId)
        assertEquals(2, hits.get()) // get() fetched once, refresh() forced a second
    }
}
