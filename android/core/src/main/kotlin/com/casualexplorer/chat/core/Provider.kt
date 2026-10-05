package com.casualexplorer.chat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

enum class Role { User, Assistant }

/**
 * One message in the conversation. Assistant turns also keep the producing
 * provider's native output so it can be replayed to that provider unchanged
 * (Anthropic thinking blocks, OpenAI reasoning items); the other provider
 * receives just [text].
 */
class Turn(
    val role: Role,
    val text: String,
    internal val anthropicMessage: JSONObject? = null,
    internal val openaiItems: JSONArray? = null,
)

/**
 * Sent by a [Provider] while a reply streams. The flow ends after [Done] or
 * [Failed]; cancelling the collector cancels the request.
 */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent

    /** Reasoning summary text, shown dimmed above the reply. */
    data class Thinking(val text: String) : StreamEvent

    /** [note] is shown under a completed reply, e.g. when it was cut off. */
    class Done(val turn: Turn, val note: String = "", val usage: Usage? = null) : StreamEvent

    class Failed(val error: Throwable) : StreamEvent
}

/** The tokens a reply used. */
data class Usage(
    /** Sent, including any read from or written to the cache. */
    val input: Long,
    /** Generated, including reasoning. */
    val output: Long,
    /**
     * The size of the conversation the model saw, with the reply: roughly how
     * full its context window now is.
     */
    val context: Long,
)

/** A model a provider serves. [contextWindow] is 0 if unknown. */
data class ModelInfo(val id: String, val contextWindow: Long = 0)

interface Provider {
    val name: String

    /** Neither [model] nor [effort] may be changed while a reply is streaming. */
    var model: String
    var effort: String

    /**
     * The effort the next request will send, which is lower than [effort]
     * when the model doesn't support that.
     */
    val requestEffort: String get() = effort

    /**
     * Sends the conversation (ending with the new user turn) and streams the
     * assistant's reply until it completes, fails, or the collector is
     * cancelled.
     */
    fun stream(history: List<Turn>): Flow<StreamEvent>

    /** The chat models the API serves, newest first. */
    suspend fun listModels(): List<ModelInfo>
}

/** The reasoning effort levels both providers accept, lowest first. */
val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

/**
 * Whether the version major.minor, as matched from a model ID (minor may be
 * empty), is at least wantMajor.wantMinor.
 */
fun versionAtLeast(major: String, minor: String?, wantMajor: Int, wantMinor: Int): Boolean {
    val maj = major.toIntOrNull() ?: return false
    val mnr = minor?.toIntOrNull() ?: 0 // a missing minor is 0
    return maj > wantMajor || maj == wantMajor && mnr >= wantMinor
}

/**
 * How long streamed text is gathered before the UI is told, as Crush does:
 * one redraw per window rather than one per token.
 */
const val STREAM_DEBOUNCE_MS = 33L

/**
 * Merges the Delta and Thinking events that arrive within [windowMs] of each
 * other into one, keeping their order. Done and Failed are passed on at once,
 * after any text gathered before them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<StreamEvent>.coalesce(windowMs: Long = STREAM_DEBOUNCE_MS): Flow<StreamEvent> = flow {
    coroutineScope {
        val input = this@coalesce.buffer(Channel.UNLIMITED).produceIn(this)
        var pending: StreamEvent? = null // only Delta or Thinking
        var deadline = 0L
        while (true) {
            val held = pending
            val result = if (held == null) {
                input.receiveCatching()
            } else {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) {
                    null
                } else {
                    select {
                        input.onReceiveCatching { it }
                        onTimeout(wait) { null }
                    }
                }
            }
            if (result == null) { // the window passed
                emit(held!!)
                pending = null
                continue
            }
            val event = result.getOrNull()
            if (event == null) { // input finished
                if (held != null) emit(held)
                result.exceptionOrNull()?.let { throw it }
                return@coroutineScope
            }
            pending = when {
                event is StreamEvent.Delta && held is StreamEvent.Delta -> StreamEvent.Delta(held.text + event.text)
                event is StreamEvent.Thinking && held is StreamEvent.Thinking -> StreamEvent.Thinking(held.text + event.text)
                event is StreamEvent.Delta || event is StreamEvent.Thinking -> {
                    if (held != null) emit(held) // the other kind of text: keep the order
                    deadline = System.currentTimeMillis() + windowMs
                    event
                }
                else -> {
                    if (held != null) emit(held)
                    emit(event)
                    null
                }
            }
            if (held == null && pending != null) deadline = System.currentTimeMillis() + windowMs
        }
    }
}

/** An error response from a provider's API. */
class ApiException(
    val provider: String,
    val status: Int,
    /** The error type the body names, e.g. "invalid_request_error". */
    val type: String,
    val detail: String,
    val requestId: String,
) : Exception("$provider API error $status")

/**
 * Turns an error into a short message for the reply's banner. API errors
 * name the status, the API's message and the request id, which support asks
 * for.
 */
fun describeError(error: Throwable): String {
    if (error is CancellationException) return "Cancelled."
    if (error is ApiException) {
        var msg = when (error.provider) {
            "Anthropic" -> buildString {
                append("Anthropic API error ${error.status} (${error.type.ifEmpty { "error" }})")
                if (error.detail.isNotEmpty()) append(": ${error.detail}")
            }
            else -> "${error.provider} API error ${error.status}: ${error.detail}"
        }
        if (error.status == 401) msg += " (check the ${error.provider} API key in Settings)"
        if (error.requestId.isNotEmpty()) msg += " [request ${error.requestId}]"
        return msg
    }
    return error.message ?: error.toString()
}
