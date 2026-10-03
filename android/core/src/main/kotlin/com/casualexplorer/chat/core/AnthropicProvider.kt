package com.casualexplorer.chat.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** The Anthropic API's own address. */
const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"

/** The most output a reply may use, before any lower limit the model reports. */
const val ANTHROPIC_MAX_TOKENS = 64_000L

/** What a model accepts of the request [AnthropicProvider.stream] sends. */
internal data class AnthropicLimits(
    /** Most output tokens; 0 if unknown. */
    val maxTokens: Long = 0,
    /** The effort levels it supports, lowest first. */
    val efforts: List<String> = emptyList(),
)

/** Talks to the Anthropic Messages API with raw HTTP and server-sent events. */
class AnthropicProvider(
    override var model: String,
    override var effort: String,
    private val apiKey: () -> String,
    /** Where the API is served; [ANTHROPIC_BASE_URL] unless set otherwise. */
    @Volatile var baseUrl: String = ANTHROPIC_BASE_URL,
    client: OkHttpClient = defaultHttpClient,
    retry: RetryPolicy = RetryPolicy(),
) : Provider {
    override val name = "Anthropic"

    private val http = Http(name, "request-id", client, retry)

    // What listModels learned about each model, which runs while replies may
    // be streaming.
    @Volatile
    internal var limits: Map<String, AnthropicLimits> = emptyMap()

    private fun headers(betas: Boolean) = buildMap {
        put("x-api-key", apiKey())
        put("anthropic-version", "2023-06-01")
        if (betas) put("anthropic-beta", ANTHROPIC_BETAS.joinToString(","))
    }

    /**
     * Lists the models from Claude 4.6 on that support what every request asks
     * for: adaptive thinking, an effort level and compaction. It also records
     * each one's output limit and effort levels for [stream], since not every
     * model supports every level.
     */
    override suspend fun listModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val found = mutableMapOf<String, AnthropicLimits>()
        var after = ""
        do {
            var url = "$baseUrl/v1/models?limit=100"
            if (after.isNotEmpty()) url += "&after_id=$after"
            val page = http.getJson(url, headers(betas = false))
            val data = page.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val id = m.optString("id")
                val caps = m.optJSONObject("capabilities") ?: JSONObject()
                if (!anthropicSupportedVersion(id) || !anthropicChatModel(caps)) continue
                models += ModelInfo(id, m.optLong("max_input_tokens"))
                found[id] = AnthropicLimits(m.optLong("max_tokens"), anthropicEfforts(caps.optJSONObject("effort")))
            }
            after = page.optString("last_id")
        } while (page.optBoolean("has_more") && after.isNotEmpty())
        limits = found
        return models
    }

    /**
     * The max_tokens and effort to send for the current model: the defaults,
     * lowered to what the model was listed as supporting. An unsupported
     * effort falls back to the highest supported level below it, or else the
     * lowest the model has.
     */
    internal fun requestLimits(): Pair<Long, String> {
        var maxTokens = ANTHROPIC_MAX_TOKENS
        var effort = effort
        val lim = limits[model] ?: return maxTokens to effort
        if (lim.maxTokens > 0) maxTokens = minOf(maxTokens, lim.maxTokens)
        if (lim.efforts.isNotEmpty() && effort !in lim.efforts) {
            val want = EFFORTS.indexOf(effort)
            var chosen = lim.efforts[0]
            for (e in lim.efforts) {
                if (EFFORTS.indexOf(e) < want) chosen = e
            }
            effort = chosen
        }
        return maxTokens to effort
    }

    override val requestEffort: String get() = requestLimits().second

    override fun stream(history: List<Turn>): Flow<StreamEvent> {
        val model = model
        val (maxTokens, effort) = requestLimits()
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", anthropicMessages(history))
            .put("output_config", JSONObject().put("effort", effort))
            // Thinking is always on; "summarized" returns a readable summary of
            // it (the default, "omitted", streams empty thinking blocks).
            .put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
            // Cache the conversation so far; the breakpoint moves forward with
            // each turn, so earlier turns are read from the cache.
            .put("cache_control", JSONObject().put("type", "ephemeral"))
            // Once the input passes the default 150k-token trigger, the API
            // summarizes the earlier turns into a compaction block. Later
            // requests replay the reply as usual and the API drops what came
            // before the block.
            .put(
                "context_management",
                JSONObject().put("edits", JSONArray().put(JSONObject().put("type", "compact_20260112"))),
            )
            // If a safety classifier declines the request, let the API retry it
            // on Anthropic's recommended fallback model instead of failing.
            .put("fallbacks", "default")
            .put("stream", true)

        return channelFlow {
            val message = MessageAccumulator()
            var thought = false // a thinking summary has been streamed
            // The SDK sends beta requests with ?beta=true.
            http.postSse("$baseUrl/v1/messages?beta=true", headers(betas = true), body) { sse ->
                val event = JSONObject(sse.data)
                if (event.optString("type") == "error") {
                    val error = event.optJSONObject("error") ?: JSONObject()
                    val detail = error.optString("message").ifEmpty { error.optString("type") }
                    throw StreamError("Anthropic stream error: $detail")
                }
                message.accumulate(event)
                when (event.optString("type")) {
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block")
                        if (block?.optString("type") == "thinking" && thought) send(StreamEvent.Thinking("\n\n"))
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: return@postSse
                        when (delta.optString("type")) {
                            "text_delta" -> send(StreamEvent.Delta(delta.optString("text")))
                            "thinking_delta" -> {
                                val text = delta.optString("thinking")
                                if (text.isNotEmpty()) {
                                    thought = true
                                    send(StreamEvent.Thinking(text))
                                }
                            }
                        }
                    }
                }
            }
            if (!message.started) throw StreamError("Anthropic stream ended without a message")

            val notes = mutableListOf<String>()
            for (block in message.content) {
                if (block.optString("type") == "compaction") {
                    notes += "Earlier messages were summarized to fit the context window."
                }
            }
            when (message.stopReason) {
                "refusal" -> {
                    var msg = "Claude declined this request"
                    val category = message.stopDetails?.optString("category").orEmpty()
                    if (category.isNotEmpty() && category != "null") msg += " ($category)"
                    throw StreamError(msg)
                }
                "max_tokens" -> notes += "Reply cut off: reached the max_tokens limit."
                "model_context_window_exceeded" ->
                    notes += "Reply cut off: the conversation filled the model's context window."
            }
            send(
                StreamEvent.Done(
                    Turn(Role.Assistant, messageText(message.content), anthropicMessage = replayParam(message.content)),
                    notes.joinToString(" "),
                    anthropicUsage(message.usage),
                ),
            )
        }.catch { e ->
            e.rethrowIfCancellation()
            emit(StreamEvent.Failed(e))
        }
    }

    companion object {
        val ANTHROPIC_BETAS = listOf("server-side-fallback-2026-07-01", "compact-2026-01-12")
    }
}

/** A failure reported in a stream that already started, shown as-is. */
class StreamError(message: String) : Exception(message)

/**
 * Builds the reply from the stream's events, as the SDK's Accumulate does:
 * content blocks start in index order and their deltas are appended in place.
 */
internal class MessageAccumulator {
    var started = false
    val content = mutableListOf<JSONObject>()
    var stopReason = ""
    var stopDetails: JSONObject? = null
    var usage = JSONObject()

    fun accumulate(event: JSONObject) {
        when (event.optString("type")) {
            "message_start" -> {
                started = true
                val message = event.optJSONObject("message") ?: JSONObject()
                content.clear()
                message.optJSONArray("content")?.let { for (i in 0 until it.length()) content += it.getJSONObject(i) }
                usage = message.optJSONObject("usage") ?: JSONObject()
            }
            "message_delta" -> {
                val delta = event.optJSONObject("delta") ?: JSONObject()
                stopReason = delta.optString("stop_reason").takeUnless { delta.isNull("stop_reason") }.orEmpty()
                stopDetails = delta.optJSONObject("stop_details")
                // Every usage count here is a cumulative whole-message total,
                // so it overwrites rather than adds.
                val u = event.optJSONObject("usage") ?: return
                val keys = u.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!u.isNull(key)) usage.put(key, u.get(key))
                }
            }
            "content_block_start" -> {
                val index = event.optInt("index")
                check(index == content.size) {
                    "received content_block_start for content block at index $index, expected index ${content.size}"
                }
                content += JSONObject(event.getJSONObject("content_block").toString())
            }
            "content_block_delta" -> {
                val index = event.optInt("index")
                check(index in content.indices) { "received content_block_delta for unknown content block $index" }
                val block = content[index]
                val delta = event.optJSONObject("delta") ?: return
                fun append(field: String, value: String) = block.put(field, block.optString(field) + value)
                when (delta.optString("type")) {
                    "text_delta" -> append("text", delta.optString("text"))
                    "thinking_delta" -> append("thinking", delta.optString("thinking"))
                    "signature_delta" -> append("signature", delta.optString("signature"))
                    "citations_delta" -> {
                        val citations = block.optJSONArray("citations") ?: JSONArray().also { block.put("citations", it) }
                        delta.optJSONObject("citation")?.let { citations.put(it) }
                    }
                    "compaction_delta" -> {
                        block.put("content", delta.opt("content") ?: JSONObject.NULL)
                        block.put("encrypted_content", delta.opt("encrypted_content") ?: JSONObject.NULL)
                    }
                }
            }
        }
    }
}

/**
 * Totals a reply's tokens. The context size comes from the last sampling
 * iteration: after a compaction the top-level counts include the closed
 * context, and a compaction iteration's own counts are only the cost of
 * summarizing.
 */
internal fun anthropicUsage(u: JSONObject): Usage {
    val input = u.optLong("input_tokens") + u.optLong("cache_read_input_tokens") + u.optLong("cache_creation_input_tokens")
    val output = u.optLong("output_tokens")
    var context = input + output
    val iterations = u.optJSONArray("iterations")
    if (iterations != null) {
        for (i in iterations.length() - 1 downTo 0) {
            val it = iterations.optJSONObject(i) ?: continue
            val type = it.optString("type")
            if (type == "message" || type == "fallback_message") {
                context = it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") +
                    it.optLong("cache_creation_input_tokens") + it.optLong("output_tokens")
                break
            }
        }
    }
    return Usage(input, output, context)
}

/**
 * Converts the conversation into Messages API params. Assistant turns from
 * the other provider that have no text (e.g. a reply cut off while
 * reasoning) are skipped: the API rejects empty text blocks, and it merges
 * the consecutive user turns that skipping leaves behind.
 */
internal fun anthropicMessages(history: List<Turn>): JSONArray {
    val messages = JSONArray()
    for (turn in history) {
        when {
            turn.anthropicMessage != null -> messages.put(turn.anthropicMessage)
            turn.role == Role.User -> messages.put(textMessage("user", turn.text))
            turn.text.isNotEmpty() -> messages.put(textMessage("assistant", turn.text))
        }
    }
    return messages
}

private fun textMessage(role: String, text: String) = JSONObject()
    .put("role", role)
    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

internal fun messageText(content: List<JSONObject>): String =
    content.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }

/**
 * Converts a reply into the form it is sent back in on later turns. Content
 * is kept as-is (thinking blocks must be echoed unchanged) except after a
 * mid-reply server-side fallback, where blocks the declined model produced
 * before the fallback marker other than text must be dropped.
 */
internal fun replayParam(content: List<JSONObject>): JSONObject {
    val lastFallback = content.indexOfLast { it.optString("type") == "fallback" }
    val kept = JSONArray()
    content.forEachIndexed { i, block ->
        when (block.optString("type")) {
            "fallback" -> return@forEachIndexed
            "thinking", "redacted_thinking", "tool_use" -> if (i < lastFallback) return@forEachIndexed
        }
        kept.put(blockParam(block))
    }
    return JSONObject().put("role", "assistant").put("content", kept)
}

/** A response content block as a request param, keeping the fields the API takes back. */
private fun blockParam(block: JSONObject): JSONObject {
    fun pick(vararg fields: String): JSONObject {
        val p = JSONObject().put("type", block.optString("type"))
        for (f in fields) if (block.has(f)) p.put(f, block.get(f))
        return p
    }
    return when (block.optString("type")) {
        "text" -> pick("text", "citations")
        "thinking" -> pick("thinking", "signature")
        "redacted_thinking" -> pick("data")
        // A failed compaction has null content and signature, which must stay
        // null rather than be dropped.
        "compaction" -> pick("content", "encrypted_content", "signature", "tool_changes")
        else -> JSONObject(block.toString()) // a block type this client doesn't model goes back as it came
    }
}

/**
 * Matches the version in a model ID: "claude-opus-4-6" is 4.6, and
 * "claude-opus-4-20250514" is 4.0 followed by a date.
 */
private val anthropicVersion = Regex("""^claude-[a-z]+-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$""")

internal fun anthropicSupportedVersion(id: String): Boolean {
    val v = anthropicVersion.find(id) ?: return false
    return versionAtLeast(v.groupValues[1], v.groups[2]?.value, 4, 6)
}

private fun JSONObject?.supported(vararg path: String): Boolean {
    var o = this ?: return false
    for (p in path) o = o.optJSONObject(p) ?: return false
    return o.optBoolean("supported")
}

internal fun anthropicChatModel(caps: JSONObject): Boolean =
    caps.supported("thinking", "types", "adaptive") && caps.supported("effort") &&
        caps.supported("context_management", "compact_20260112") &&
        anthropicEfforts(caps.optJSONObject("effort")).isNotEmpty()

/** The effort levels a model supports, lowest first. */
internal fun anthropicEfforts(effort: JSONObject?): List<String> = EFFORTS.filter { effort.supported(it) }
