package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The OpenAI API's own address. */
const val OPENAI_BASE_URL = "https://api.openai.com"

/** The input size, in tokens, at which the API compacts the conversation server-side. */
const val OPENAI_COMPACT_THRESHOLD = 200_000

/** Talks to the OpenAI Responses API with raw HTTP and server-sent events. */
class OpenAIProvider(
    override var model: String,
    override var effort: String,
    private val apiKey: () -> String,
    /** Where the API is served, read for each request; [OPENAI_BASE_URL] unless set otherwise. */
    private val baseUrlOf: () -> String = { OPENAI_BASE_URL },
    client: OkHttpClient = defaultHttpClient,
    // The OpenAI SDK doesn't wait for a retry-after past two minutes.
    retry: RetryPolicy = RetryPolicy(maxRetryAfterMs = 120_000),
    /** Where response bodies are read, which blocks. */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Provider {
    override val name = "OpenAI"

    /** Routes this session's requests to the same prompt cache. */
    val cacheKey = "chat-" + randomText()

    private val http = Http(name, "x-request-id", client, retry, ioDispatcher)

    private fun headers() = mapOf("Authorization" to "Bearer ${apiKey()}")

    /**
     * Lists the chat models from GPT-5.6 on, which all reason. The API lists
     * every model the key can use, including embedding, audio and image
     * models, and doesn't report context windows or effort levels.
     */
    override suspend fun listModels(): List<ModelInfo> {
        val data = http.getJson("${baseUrlOf()}/v1/models", headers()).optJSONArray("data") ?: JSONArray()
        val today = LocalDate.now()
        val found = (0 until data.length()).map { data.getJSONObject(it) }.filter {
            isOpenAIChatModel(it.optString("id"), shutdownDate(it), today)
        }
        return found.sortedByDescending { it.optLong("created") }.map { ModelInfo(it.optString("id")) }
    }

    override fun stream(history: List<Turn>): Flow<StreamEvent> {
        // The conversation lives in this process, so nothing is stored
        // server-side. Encrypted reasoning items are requested so they can be
        // passed back on the next turn, as OpenAI recommends for stateless use.
        val body = JSONObject()
            .put("model", model)
            .put("input", openaiInput(history))
            .put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("reasoning", JSONObject().put("effort", effort).put("summary", "auto"))
            .put("prompt_cache_key", cacheKey)
            // Past the threshold the API compacts the conversation into a
            // compaction item, which replayItems keeps for later turns.
            .put(
                "context_management",
                JSONArray().put(JSONObject().put("type", "compaction").put("compact_threshold", OPENAI_COMPACT_THRESHOLD)),
            )
            .put("stream", true)

        return channelFlow {
            var final: JSONObject? = null
            var note = ""
            var thought = false // a reasoning summary has been streamed
            http.postSse("${baseUrlOf()}/v1/responses", headers(), body) { sse ->
                if (sse.data == "[DONE]") return@postSse
                val event = JSONObject(sse.data)
                when (event.optString("type")) {
                    "response.output_text.delta", "response.refusal.delta" ->
                        send(StreamEvent.Delta(event.optString("delta")))
                    "response.reasoning_summary_part.added" -> if (thought) send(StreamEvent.Thinking("\n\n"))
                    "response.reasoning_summary_text.delta" -> {
                        thought = true
                        send(StreamEvent.Thinking(event.optString("delta")))
                    }
                    "response.completed" -> final = event.optJSONObject("response")
                    "response.incomplete" -> {
                        final = event.optJSONObject("response")
                        note = "Reply cut off"
                        val reason = final?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                        if (reason.isNotEmpty()) note += ": $reason"
                        note += "."
                    }
                    "response.failed" -> {
                        var msg = "OpenAI response failed"
                        val e = event.optJSONObject("response")?.optJSONObject("error")
                        val message = e?.optString("message").orEmpty()
                        val code = e?.optString("code").orEmpty()
                        if (message.isNotEmpty()) {
                            msg += ": $message"
                        } else if (code.isNotEmpty()) {
                            msg += ": $code"
                        }
                        throw StreamError(msg)
                    }
                    "error" -> throw StreamError("OpenAI stream error: " + event.optString("message"))
                }
            }
            val response = final ?: throw StreamError("OpenAI stream ended without a completed response")

            val output = response.optJSONArray("output") ?: JSONArray()
            for (i in 0 until output.length()) {
                if (output.getJSONObject(i).optString("type") == "compaction") {
                    note = "Earlier messages were summarized to fit the context window. $note".trim()
                }
            }
            val usage = response.optJSONObject("usage") ?: JSONObject()
            send(
                StreamEvent.Done(
                    Turn(Role.Assistant, replyText(response), openaiItems = replayItems(response)),
                    note,
                    Usage(usage.optLong("input_tokens"), usage.optLong("output_tokens"), usage.optLong("total_tokens")),
                ),
            )
        }.catch { e ->
            e.rethrowIfCancellation()
            emit(StreamEvent.Failed(e))
        }
    }
}

private fun randomText(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val random = SecureRandom()
    return String(CharArray(26) { alphabet[random.nextInt(alphabet.length)] })
}

private fun shutdownDate(model: JSONObject): LocalDate? {
    if (model.isNull("shutdown_date")) return null
    return try {
        LocalDate.parse(model.optString("shutdown_date").take(10))
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The version of a GPT model: "gpt-5.6-sol" is 5.6, "gpt-6-astra" 6.0. */
private val openAIChatModel = Regex("""^gpt-(\d+)(?:\.(\d+))?(?:-|$)""")

/** Dated snapshots of a model, which the undated alias already covers. */
private val openAISnapshot = Regex("""-\d{4}(-\d{2}-\d{2})?$""")
private val openAINotChat = listOf("audio", "realtime", "transcribe", "tts", "image", "search", "instruct", "embedding")

internal fun isOpenAIChatModel(id: String, shutdown: LocalDate?, today: LocalDate): Boolean {
    val v = openAIChatModel.find(id) ?: return false
    if (!versionAtLeast(v.groupValues[1], v.groups[2]?.value, 5, 6) || openAISnapshot.containsMatchIn(id)) return false
    if (shutdown != null && !shutdown.isAfter(today)) return false
    return openAINotChat.none { it in id }
}

/**
 * Converts the conversation into Responses API input items. Assistant turns
 * from the other provider that have no text (e.g. a reply cut off while
 * thinking) are skipped, since there is nothing to show the model. Items
 * before the latest compaction item are dropped: it already carries them,
 * and OpenAI recommends pruning them to keep requests small.
 */
internal fun openaiInput(history: List<Turn>): JSONArray {
    val input = mutableListOf<JSONObject>()
    for (turn in history) {
        when {
            turn.openaiItems != null -> for (i in 0 until turn.openaiItems.length()) input += turn.openaiItems.getJSONObject(i)
            turn.role == Role.User -> input += JSONObject().put("role", "user").put("content", turn.text)
            turn.text.isNotEmpty() -> input += JSONObject().put("role", "assistant").put("content", turn.text)
        }
    }
    val start = maxOf(input.indexOfLast { it.optString("type") == "compaction" }, 0)
    return JSONArray(input.subList(start, input.size))
}

/**
 * The reply's visible text. Unlike the SDK's output_text it includes
 * refusals, which are streamed to the screen like ordinary text.
 */
internal fun replyText(response: JSONObject): String {
    val text = StringBuilder()
    val output = response.optJSONArray("output") ?: return ""
    for (i in 0 until output.length()) {
        val item = output.getJSONObject(i)
        if (item.optString("type") != "message") continue
        val content = item.optJSONArray("content") ?: continue
        for (j in 0 until content.length()) {
            val c = content.getJSONObject(j)
            when (c.optString("type")) {
                "output_text" -> text.append(c.optString("text"))
                "refusal" -> text.append(c.optString("refusal"))
            }
        }
    }
    return text.toString()
}

/**
 * Converts a response's output into input items for later turns, keeping
 * reasoning and compaction items (with their encrypted content) alongside
 * the message. Messages and reasoning go back as they came, as the SDK's
 * ToParam does.
 */
internal fun replayItems(response: JSONObject): JSONArray {
    val items = JSONArray()
    val output = response.optJSONArray("output") ?: return items
    for (i in 0 until output.length()) {
        val item = output.getJSONObject(i)
        when (item.optString("type")) {
            "message", "reasoning" -> items.put(JSONObject(item.toString()))
            "compaction" -> items.put(
                JSONObject()
                    .put("type", "compaction")
                    .put("id", item.optString("id"))
                    .put("encrypted_content", item.optString("encrypted_content")),
            )
        }
    }
    return items
}
