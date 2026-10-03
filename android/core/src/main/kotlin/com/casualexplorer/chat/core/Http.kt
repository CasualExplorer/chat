package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

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
 * A minimal JSON-over-HTTPS client on [HttpURLConnection]. Requests run on
 * the IO dispatcher; cancelling the calling coroutine disconnects the
 * connection, which unblocks any read in progress.
 */
internal class Http(
    private val provider: String,
    /** The header carrying a request id, which error messages include. */
    private val requestIdHeader: String,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 10 * 60_000,
) {
    /** Sends [body] and calls [onEvent] for each server-sent event of the reply. */
    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        onEvent: suspend (SseEvent) -> Unit,
    ) = withConnection(url) { conn ->
        conn.requestMethod = "POST"
        conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        checkStatus(conn)
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { readSse(it, onEvent) }
    }

    suspend fun getJson(url: String, headers: Map<String, String>): JSONObject = withConnection(url) { conn ->
        conn.requestMethod = "GET"
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Accept", "application/json")
        checkStatus(conn)
        JSONObject(conn.inputStream.readText())
    }

    private suspend fun <T> withConnection(url: String, block: suspend (HttpURLConnection) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        // The request runs outside the caller's job: a read blocked on the
        // socket doesn't notice cancellation, and on some JVMs disconnect()
        // waits for that read to return. So a cancelled caller returns at
        // once, and the connection is closed from another thread.
        val work = requests.async {
            try {
                block(conn)
            } finally {
                conn.disconnect()
            }
        }
        try {
            return work.await()
        } catch (e: CancellationException) {
            work.cancel()
            thread(isDaemon = true, name = "http-disconnect") { conn.disconnect() }
            throw e
        }
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status in 200..299) return
        val text = try {
            conn.errorStream?.readText().orEmpty()
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
        if (message.isEmpty()) message = conn.responseMessage.orEmpty()
        throw ApiException(provider, status, type, message, conn.getHeaderField(requestIdHeader).orEmpty())
    }
}

/** Where requests run; see [Http.withConnection]. */
private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun InputStream.readText(): String = bufferedReader(Charsets.UTF_8).use { it.readText() }

/** Rethrows cancellation, so `catch (e: Exception)` blocks don't swallow it. */
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
