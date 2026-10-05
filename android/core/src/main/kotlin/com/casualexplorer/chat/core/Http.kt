package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.random.Random

/** One server-sent event: its `event:` name and its joined `data:` lines. */
data class SseEvent(val event: String, val data: String)

/**
 * Reads server-sent events from [reader], calling [onEvent] for each. Lines
 * are `field: value`; a blank line dispatches the event; comments (lines
 * starting with ':') are skipped. Multiple data lines are joined with '\n'.
 */
suspend fun readSse(reader: BufferedReader, onEvent: suspend (SseEvent) -> Unit) {
    var event = ""
    val data = StringBuilder()
    var hasData = false
    suspend fun dispatch() {
        if (hasData) onEvent(SseEvent(event.ifEmpty { "message" }, data.toString()))
        event = ""
        data.setLength(0)
        hasData = false
    }
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            dispatch()
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }
    dispatch()
}

/**
 * When to retry a request, as the Go SDKs do by default: up to [maxRetries]
 * more attempts after a connection error, or a 408, 409, 429 or 5xx response
 * (an `x-should-retry` header overrides the status). The wait is what
 * `retry-after-ms` or `retry-after` asks for, or else 0.5 s doubling per
 * attempt up to 8 s, less up to a quarter of it as jitter.
 *
 * Only getting a response is retried: once a reply streams, a broken
 * connection ends it.
 */
class RetryPolicy(
    val maxRetries: Int = 2,
    /**
     * The longest `retry-after` that is waited for; a longer one ends the
     * request instead. Null waits for any (the Anthropic SDK); the OpenAI SDK
     * stops past two minutes.
     */
    private val maxRetryAfterMs: Long? = null,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun shouldRetry(status: Int, headers: Headers): Boolean {
        when (headers["x-should-retry"]) {
            "true" -> return true
            "false" -> return false
        }
        return status == 408 || status == 409 || status == 429 || status >= 500
    }

    /**
     * How long to wait before retry number [attempt] + 1 (counting from 0),
     * after a response with [headers], or after a connection error if null.
     * Null means not to retry.
     */
    fun delayMs(attempt: Int, headers: Headers?): Long? {
        if (headers != null) {
            retryAfterMs(headers)?.let { wait ->
                if (maxRetryAfterMs != null && wait > maxRetryAfterMs) return null
                return maxOf(wait, 0)
            }
        }
        val backoff = min(500L shl min(attempt, 20), 8_000L)
        return backoff - random.nextLong(maxOf(backoff / 4, 1))
    }

    private fun retryAfterMs(headers: Headers): Long? {
        headers["retry-after-ms"]?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it.toLong() }
        val value = headers["retry-after"] ?: return null
        value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return (it * 1000).toLong() }
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** The client every provider shares unless given another: one connection pool. */
val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    // A reasoning model can think for minutes before its first token.
    .readTimeout(10, TimeUnit.MINUTES)
    .build()

private val JSON = "application/json".toMediaType()

/**
 * JSON over HTTPS for one provider, with [retry]. Cancelling the calling
 * coroutine cancels the call, which also ends a read in progress.
 */
internal class Http(
    private val provider: String,
    /** The header carrying a request id, which error messages include. */
    private val requestIdHeader: String,
    private val client: OkHttpClient,
    private val retry: RetryPolicy,
    /** Where bodies are read; the read blocks. */
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Sends [body] and calls [onEvent] for each server-sent event of the reply. */
    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        onEvent: suspend (SseEvent) -> Unit,
    ) {
        val request = request(url, headers)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        execute(request) { response -> response.body.charStream().buffered().use { readSse(it, onEvent) } }
    }

    suspend fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val request = request(url, headers).header("Accept", "application/json").get().build()
        return execute(request) { JSONObject(it.body.string()) }
    }

    private fun request(url: String, headers: Map<String, String>) =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }

    /** Sends [request], retrying as [retry] says, and passes a successful response to [read]. */
    private suspend fun <T> execute(request: Request, read: suspend (Response) -> T): T {
        var attempt = 0
        while (true) {
            val call = client.newCall(request)
            val response = try {
                call.await()
            } catch (e: IOException) {
                val wait = if (attempt < retry.maxRetries) retry.delayMs(attempt, null) else null
                if (wait == null) throw e
                attempt++
                delay(wait)
                continue
            }
            if (response.isSuccessful) return readBody(call, response, read)
            val retryable = attempt < retry.maxRetries && retry.shouldRetry(response.code, response.headers)
            val wait = if (retryable) retry.delayMs(attempt, response.headers) else null
            if (wait == null) throw apiException(response)
            response.close()
            attempt++
            delay(wait)
        }
    }

    /**
     * Reads the body on [ioDispatcher]. A blocked read doesn't notice
     * cancellation, so cancelling the caller cancels the call, which fails
     * the read.
     */
    private suspend fun <T> readBody(call: Call, response: Response, read: suspend (Response) -> T): T =
        response.use {
            coroutineScope {
                val reading = async(ioDispatcher) {
                    try {
                        read(response)
                    } catch (e: IOException) {
                        // Cancelling the call broke the read: that is the
                        // cancellation, not a failure to report.
                        if (call.isCanceled()) throw CancellationException("Request cancelled", e)
                        throw e
                    }
                }
                try {
                    reading.await()
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                }
            }
        }

    private fun apiException(response: Response): ApiException = response.use {
        val text = try {
            response.body.string()
        } catch (_: IOException) {
            ""
        }
        var type = ""
        var message = ""
        try {
            val error = JSONObject(text).optJSONObject("error")
            if (error != null) {
                type = error.optString("type")
                message = error.optString("message")
            }
        } catch (_: Exception) {
        }
        if (message.isEmpty()) message = response.message
        ApiException(provider, response.code, type, message, response.header(requestIdHeader).orEmpty())
    }
}

/** Enqueues the call and waits for its response; cancelling the caller cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { cancel() }
}

/** Rethrows cancellation, so `catch (e: Exception)` blocks don't swallow it. */
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
