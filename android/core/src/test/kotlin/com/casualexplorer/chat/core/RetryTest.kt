package com.casualexplorer.chat.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.headersOf
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The retry policy, which follows the Go SDKs' defaults. */
class RetryTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stopServers() = servers.forEach { it.stop(0) }

    /** A server that answers request number n (from 0) with [handler]; returns its URL and the request count. */
    private fun server(handler: (HttpExchange, Int) -> Unit): Pair<String, AtomicInteger> {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readBytes()
                handler(exchange, count.getAndIncrement())
            } finally {
                exchange.close()
            }
        }
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}" to count
    }

    private fun HttpExchange.respond(status: Int, body: String, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private val okText = "event: message_start\n" +
        """data: {"type":"message_start","message":{"role":"assistant","content":[],"usage":{}}}""" + "\n\n" +
        "event: content_block_start\n" +
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""" + "\n\n" +
        "event: content_block_delta\n" +
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""" + "\n\n" +
        "event: message_stop\n" +
        """data: {"type":"message_stop"}""" + "\n\n"

    private fun anthropic(url: String) = AnthropicProvider("m", "low", { "k" }, { url })

    private fun reply(p: Provider) = runBlocking { p.stream(listOf(Turn(Role.User, "hi"))).first { it !is StreamEvent.Delta } }

    @Test
    fun serverErrorsAreRetriedTwice() {
        val (url, count) = server { ex, n ->
            if (n < 2) ex.respond(503, "{}", "retry-after-ms" to "0") else ex.respond(200, okText)
        }
        assertIs<StreamEvent.Done>(reply(anthropic(url)))
        assertEquals(3, count.get())
    }

    @Test
    fun theThirdFailureIsReported() {
        val (url, count) = server { ex, _ ->
            ex.respond(529, """{"error":{"type":"overloaded_error","message":"Overloaded"}}""", "retry-after-ms" to "0")
        }
        val failed = assertIs<StreamEvent.Failed>(reply(anthropic(url)))
        assertEquals("Anthropic API error 529 (overloaded_error): Overloaded", describeError(failed.error))
        assertEquals(3, count.get(), "one request and two retries")
    }

    @Test
    fun clientErrorsAreNotRetried() {
        val (url, count) = server { ex, _ -> ex.respond(400, "{}") }
        assertIs<StreamEvent.Failed>(reply(anthropic(url)))
        assertEquals(1, count.get())
    }

    @Test
    fun retryableStatuses() {
        val policy = RetryPolicy()
        val none = headersOf()
        assertEquals(listOf(408, 409, 429, 500, 503), listOf(400, 401, 404, 408, 409, 429, 500, 503).filter { policy.shouldRetry(it, none) })
        assertTrue(policy.shouldRetry(400, headersOf("x-should-retry", "true")), "the header overrides the status")
        assertTrue(!policy.shouldRetry(503, headersOf("x-should-retry", "false")))
    }

    @Test
    fun shouldRetryHeaderIsHonored() {
        val (url, count) = server { ex, n ->
            if (n == 0) ex.respond(400, "{}", "x-should-retry" to "true", "retry-after-ms" to "0") else ex.respond(200, okText)
        }
        assertIs<StreamEvent.Done>(reply(anthropic(url)))
        assertEquals(2, count.get())
    }

    @Test
    fun backoffDoublesUpToEightSecondsLessJitter() {
        val policy = RetryPolicy(random = Random(1))
        for ((attempt, full) in listOf(0 to 500L, 1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 9 to 8_000L)) {
            val wait = policy.delayMs(attempt, null)!!
            assertTrue(wait in (full - full / 4)..full, "attempt $attempt waited $wait, want ${full * 3 / 4}..$full")
        }
    }

    @Test
    fun retryAfterHeaders() {
        val now = 1_700_000_000_000L
        val policy = RetryPolicy(now = { now })
        assertEquals(250, policy.delayMs(0, headersOf("retry-after-ms", "250")))
        assertEquals(1_500, policy.delayMs(0, headersOf("retry-after", "1.5")))
        assertEquals(250, policy.delayMs(0, headersOf("retry-after-ms", "250", "retry-after", "9")), "-ms is preferred")
        // RFC 1123 dates: 1_700_000_000 s is Tue, 14 Nov 2023 22:13:20 GMT.
        assertEquals(10_000, policy.delayMs(0, headersOf("retry-after", "Tue, 14 Nov 2023 22:13:30 GMT")))
        assertEquals(0, policy.delayMs(0, headersOf("retry-after", "Tue, 14 Nov 2023 22:13:10 GMT")), "a past date: at once")
        assertEquals(600_000, policy.delayMs(0, headersOf("retry-after", "600")), "Anthropic: no limit")
        assertNull(RetryPolicy(maxRetryAfterMs = 120_000).delayMs(0, headersOf("retry-after", "600")), "OpenAI: past 2 min, give up")
    }

    @Test
    fun cancellingEndsAHangingStream() {
        val release = CountDownLatch(1)
        val (url, _) = server { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write(okText.substringBefore("event: message_stop").toByteArray())
            ex.responseBody.flush()
            release.await(30, TimeUnit.SECONDS) // the reply never finishes
        }
        try {
            runBlocking {
                withTimeout(10_000) {
                    // first() cancels the stream once the text arrives, which
                    // must end the blocked read rather than wait for it.
                    val first = anthropic(url).stream(listOf(Turn(Role.User, "hi"))).first { it is StreamEvent.Delta }
                    assertEquals("ok", (first as StreamEvent.Delta).text)
                }
            }
        } finally {
            release.countDown()
        }
    }
}
