package com.casualexplorer.chat.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Looks up a dotted path such as "messages.0.content.0.text", as gjson does. */
fun jsonPath(root: Any?, path: String): String {
    var node = root
    for (key in path.split('.')) {
        node = when (node) {
            is JSONObject -> node.opt(key)
            is JSONArray -> key.toIntOrNull()?.let { node.opt(it) }
            else -> null
        }
    }
    return node?.toString() ?: ""
}

/** Collects a stream and returns the error it ended with, if any. */
fun drain(events: Flow<StreamEvent>): Throwable? = runBlocking {
    events.toList().filterIsInstance<StreamEvent.Failed>().lastOrNull()?.error
}

class RequestTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stopServers() = servers.forEach { it.stop(0) }

    /** A local server that answers with [handler]; returns its base URL. */
    private fun server(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private class Captured {
        var body = JSONObject()
        var headers: Map<String, List<String>> = emptyMap()
        var uri = ""
    }

    /** Records one request and fails it, so a provider's request can be inspected without a real reply. */
    private fun captureServer(): Pair<String, Captured> {
        val captured = Captured()
        val url = server { ex ->
            captured.body = JSONObject(ex.requestBody.readBytes().decodeToString())
            captured.headers = ex.requestHeaders.mapKeys { it.key.lowercase() }
            captured.uri = ex.requestURI.toString()
            ex.responseHeaders.add("request-id", "req_test") // Anthropic
            ex.responseHeaders.add("x-request-id", "req_test") // OpenAI
            ex.respond(400, """{"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}}""")
        }
        return url to captured
    }

    @Test
    fun anthropicRequest() {
        val (url, captured) = captureServer()
        val p = AnthropicProvider("claude-test", "high", { "k" }, url)

        val err = drain(p.stream(listOf(Turn(Role.User, "hi")))) ?: fail("expected the stub's error")
        assertEquals("Anthropic API error 400 (invalid_request_error): nope [request req_test]", describeError(err))

        for ((path, want) in mapOf(
            "cache_control.type" to "ephemeral",
            "context_management.edits.0.type" to "compact_20260112",
            "output_config.effort" to "high",
            "thinking.type" to "adaptive",
            "thinking.display" to "summarized",
            "fallbacks" to "default",
            "messages.0.content.0.text" to "hi",
            "stream" to "true",
        )) {
            assertEquals(want, jsonPath(captured.body, path), path)
        }
        assertEquals(
            "server-side-fallback-2026-07-01,compact-2026-01-12",
            captured.headers["anthropic-beta"]?.joinToString(","),
        )
        assertEquals("k", captured.headers["x-api-key"]?.single())
        assertEquals("/v1/messages?beta=true", captured.uri, "the beta Messages API, as the Go SDK calls it")
    }

    @Test
    fun openAIRequest() {
        val (url, captured) = captureServer()
        val p = OpenAIProvider("gpt-test", "low", { "k" }, url)

        val err = drain(p.stream(listOf(Turn(Role.User, "hi")))) ?: fail("expected the stub's error")
        assertEquals("OpenAI API error 400: nope [request req_test]", describeError(err))

        for ((path, want) in mapOf(
            "store" to "false",
            "reasoning.effort" to "low",
            "reasoning.summary" to "auto",
            "prompt_cache_key" to p.cacheKey,
            "context_management.0.type" to "compaction",
            "context_management.0.compact_threshold" to "200000",
            "include.0" to "reasoning.encrypted_content",
            "input.0.content" to "hi",
        )) {
            assertEquals(want, jsonPath(captured.body, path), path)
        }
        assertEquals("Bearer k", captured.headers["authorization"]?.single())
        assertEquals("/v1/responses", captured.uri)
    }

    @Test
    fun unauthorizedNamesTheKeySetting() {
        val url = server { it.respond(401, """{"error": {"type": "authentication_error", "message": "invalid x-api-key"}}""") }
        val err = drain(AnthropicProvider("m", "low", { "bad" }, url).stream(listOf(Turn(Role.User, "hi"))))!!
        assertEquals(
            "Anthropic API error 401 (authentication_error): invalid x-api-key (check the Anthropic API key in Settings)",
            describeError(err),
        )
    }

    @Test
    fun anthropicListModelsLimitsRequests() {
        fun model(id: String, maxTokens: Int, compact: Boolean, vararg efforts: String): JSONObject {
            val effort = JSONObject().put("supported", efforts.isNotEmpty())
            for (e in EFFORTS) effort.put(e, JSONObject().put("supported", e in efforts))
            return JSONObject()
                .put("id", id).put("type", "model").put("display_name", id).put("created_at", "2026-01-01T00:00:00Z")
                .put("max_input_tokens", 200000).put("max_tokens", maxTokens)
                .put(
                    "capabilities",
                    JSONObject()
                        .put("thinking", JSONObject().put("supported", true).put("types", JSONObject().put("adaptive", JSONObject().put("supported", true))))
                        .put("effort", effort)
                        .put("context_management", JSONObject().put("supported", compact).put("compact_20260112", JSONObject().put("supported", compact))),
                )
        }
        var body = JSONObject()
        val url = server { ex ->
            if (ex.requestURI.path == "/v1/models") {
                val data = JSONArray()
                    .put(model("claude-sonnet-4-6", 32000, true, "low", "medium", "high"))
                    .put(model("claude-opus-4-7", 8192, false)) // no compaction
                    .put(model("claude-opus-4-1", 32000, true, "low", "medium", "high")) // too old
                ex.respond(200, JSONObject().put("has_more", false).put("data", data).toString())
                return@server
            }
            body = JSONObject(ex.requestBody.readBytes().decodeToString())
            ex.respond(400, """{"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}}""")
        }

        val p = AnthropicProvider("claude-sonnet-4-6", "max", { "k" }, url)
        val models = runBlocking { p.listModels() }
        assertEquals(listOf(ModelInfo("claude-sonnet-4-6", 200000)), models, "want only claude-sonnet-4-6")
        assertEquals("high", p.requestEffort, "max lowered to high")

        drain(p.stream(listOf(Turn(Role.User, "hi"))))
        assertEquals("32000", jsonPath(body, "max_tokens"), "the model's max_tokens")
        assertEquals("high", jsonPath(body, "output_config.effort"), "max lowered to high")

        // A model that wasn't listed gets the defaults.
        p.model = "claude-unlisted"
        drain(p.stream(listOf(Turn(Role.User, "hi"))))
        assertEquals(ANTHROPIC_MAX_TOKENS.toString(), jsonPath(body, "max_tokens"))
        assertEquals("max", jsonPath(body, "output_config.effort"))
    }

    @Test
    fun anthropicListModelsFollowsPages() {
        val url = server { ex ->
            val after = ex.requestURI.query.orEmpty().contains("after_id=a")
            val caps = """{"thinking": {"types": {"adaptive": {"supported": true}}}, "effort": {"supported": true, "high": {"supported": true}},
                "context_management": {"compact_20260112": {"supported": true}}}"""
            val id = if (after) "claude-opus-5-5" else "claude-sonnet-5-5"
            ex.respond(200, """{"has_more": ${!after}, "last_id": "a", "data": [{"id": "$id", "max_input_tokens": 1000000, "capabilities": $caps}]}""")
        }
        val models = runBlocking { AnthropicProvider("m", "high", { "k" }, url).listModels() }
        assertEquals(listOf("claude-sonnet-5-5", "claude-opus-5-5"), models.map { it.id })
    }

    @Test
    fun anthropicRequestLimitsEffortFallback() {
        val p = AnthropicProvider("m", "low", { "k" })
        p.limits = mapOf("m" to AnthropicLimits(efforts = listOf("high", "max")))
        assertEquals("high", p.requestLimits().second, "low on a model from high up: its lowest, high")
        p.effort = "xhigh"
        assertEquals("high", p.requestLimits().second, "xhigh: the next lower supported, high")
    }

    private fun sse(vararg events: Pair<String, String>) =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    @Test
    fun anthropicStreamsThinkingTextAndUsage() {
        val url = server { ex ->
            ex.respond(
                200,
                sse(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":1}}}""",
                    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Pondering."}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"More."}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                    "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"compaction","content":null}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"compaction_delta","content":"summary","encrypted_content":"enc"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":2}""",
                    "ping" to """{"type":"ping"}""",
                    "content_block_start" to """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"Hello"}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":" there."}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":3}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"max_tokens","stop_sequence":null,"stop_details":null},"usage":{"output_tokens":42}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
                "text/event-stream",
            )
        }
        val events = runBlocking { AnthropicProvider("m", "high", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))).toList() }
        val done = assertIs<StreamEvent.Done>(events.last())
        assertEquals(
            listOf("Pondering.", "\n\n", "More."),
            events.filterIsInstance<StreamEvent.Thinking>().map { it.text },
        )
        assertEquals("Hello there.", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertEquals("Hello there.", done.turn.text)
        assertEquals(
            "Earlier messages were summarized to fit the context window. Reply cut off: reached the max_tokens limit.",
            done.note,
        )
        assertEquals(Usage(15, 42, 57), done.usage)

        val replay = assertNotNull(done.turn.anthropicMessage)
        assertEquals("assistant", jsonPath(replay, "role"))
        assertEquals("Pondering.", jsonPath(replay, "content.0.thinking"))
        assertEquals("sig", jsonPath(replay, "content.0.signature"))
        assertEquals("summary", jsonPath(replay, "content.2.content"))
        assertEquals("enc", jsonPath(replay, "content.2.encrypted_content"))
        assertEquals("Hello there.", jsonPath(replay, "content.3.text"))
        // The replay is what the next request sends, unchanged.
        assertEquals(replay.toString(), anthropicMessages(listOf(Turn(Role.User, "hi"), done.turn)).getJSONObject(1).toString())
    }

    @Test
    fun anthropicRefusalIsAnError() {
        val url = server { ex ->
            ex.respond(
                200,
                sse(
                    "message_start" to """{"type":"message_start","message":{"role":"assistant","content":[],"usage":{}}}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}},"usage":{"output_tokens":0}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
                "text/event-stream",
            )
        }
        val err = drain(AnthropicProvider("m", "high", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))))
        assertEquals("Claude declined this request (cyber)", err?.let(::describeError))
    }

    @Test
    fun anthropicStreamErrorEvent() {
        val url = server { ex ->
            ex.respond(200, sse("error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""), "text/event-stream")
        }
        val err = drain(AnthropicProvider("m", "high", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))))
        assertEquals("Anthropic stream error: Overloaded", err?.let(::describeError))
    }

    @Test
    fun openAIStreamsSummaryTextAndUsage() {
        val response = """{"id":"resp_1","status":"completed","output":[
            {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"a"}],"encrypted_content":"enc"},
            {"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hi!","annotations":[]}]}
            ],"usage":{"input_tokens":20,"output_tokens":7,"total_tokens":27}}""".replace("\n", "")
        val url = server { ex ->
            ex.respond(
                200,
                sse(
                    "response.created" to """{"type":"response.created"}""",
                    "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added"}""",
                    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","delta":"Plan"}""",
                    "response.reasoning_summary_part.added" to """{"type":"response.reasoning_summary_part.added"}""",
                    "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","delta":"Act"}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Hi"}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"!"}""",
                    "response.completed" to """{"type":"response.completed","response":$response}""",
                ),
                "text/event-stream",
            )
        }
        val events = runBlocking { OpenAIProvider("gpt", "low", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))).toList() }
        assertEquals(listOf("Plan", "\n\n", "Act"), events.filterIsInstance<StreamEvent.Thinking>().map { it.text })
        val done = assertIs<StreamEvent.Done>(events.last())
        assertEquals("Hi!", done.turn.text)
        assertEquals(Usage(20, 7, 27), done.usage)
        val items = assertNotNull(done.turn.openaiItems)
        assertEquals("reasoning", jsonPath(items, "0.type"))
        assertEquals("enc", jsonPath(items, "0.encrypted_content"))
        assertEquals("message", jsonPath(items, "1.type"))
    }

    @Test
    fun openAIFailedResponse() {
        val url = server { ex ->
            ex.respond(
                200,
                sse("response.failed" to """{"type":"response.failed","response":{"error":{"code":"server_error","message":"Boom"}}}"""),
                "text/event-stream",
            )
        }
        val err = drain(OpenAIProvider("gpt", "low", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))))
        assertEquals("OpenAI response failed: Boom", err?.let(::describeError))
    }

    @Test
    fun cancellingTheCollectorStopsTheRequest() = runBlocking {
        val url = server { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\n\n".toByteArray())
            ex.responseBody.flush()
            Thread.sleep(10_000) // never finishes on its own
        }
        val started = System.currentTimeMillis()
        // first() cancels the request once the first delta arrives; the read
        // blocked on the open connection must not hold it up.
        val first = withTimeout(5_000) {
            OpenAIProvider("gpt", "low", { "k" }, url).stream(listOf(Turn(Role.User, "hi"))).first()
        }
        assertEquals(StreamEvent.Delta("x"), first)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }
}
