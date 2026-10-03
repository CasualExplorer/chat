package com.casualexplorer.chat.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun blocks(json: String): List<JSONObject> {
    val a = JSONArray(json)
    return (0 until a.length()).map { a.getJSONObject(it) }
}

class ProviderTest {
    @Test
    fun replyTextIncludesRefusal() {
        val response = JSONObject(
            """{"output": [
                {"type": "reasoning", "id": "rs_1", "summary": []},
                {"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
                    {"type": "refusal", "refusal": "I can't help with that."}
                ]}
            ]}""",
        )
        assertEquals("I can't help with that.", replyText(response))
    }

    @Test
    fun emptyAssistantTurnIsSkipped() {
        val history = listOf(
            Turn(Role.User, "one"),
            Turn(Role.Assistant, ""), // e.g. a reply cut off before any text
            Turn(Role.User, "two"),
        )
        assertEquals(2, anthropicMessages(history).length())
        assertEquals(2, openaiInput(history).length())
    }

    @Test
    fun replayParamDropsDeclinedModelsBlocksBeforeFallback() {
        val content = blocks(
            """[
                {"type": "thinking", "thinking": "declined", "signature": "s1"},
                {"type": "text", "text": "Before. "},
                {"type": "fallback", "from": {"model": "a"}, "to": {"model": "b"}},
                {"type": "thinking", "thinking": "fallback model", "signature": "s2"},
                {"type": "text", "text": "After."}
            ]""",
        )
        val got = blocks(replayParam(content).getJSONArray("content").toString()).map {
            when (val type = it.getString("type")) {
                "text" -> "text:" + it.getString("text")
                "thinking" -> "thinking:" + it.getString("thinking")
                else -> error("unexpected block kept: $type")
            }
        }
        assertEquals(listOf("text:Before. ", "thinking:fallback model", "text:After."), got)
    }

    @Test
    fun replayParamKeepsCompactionBlock() {
        val content = blocks(
            """[
                {"type": "compaction", "content": "summary", "encrypted_content": "enc", "signature": "sig"},
                {"type": "text", "text": "Reply."}
            ]""",
        )
        val replayed = replayParam(content).getJSONArray("content")
        assertEquals(2, replayed.length())
        val compaction = replayed.getJSONObject(0)
        assertEquals("compaction", compaction.getString("type"))
        assertEquals("sig", compaction.getString("signature"))
        assertEquals("enc", compaction.getString("encrypted_content"))
        assertEquals("summary", compaction.getString("content"))
    }

    @Test
    fun replayParamKeepsAFailedCompactionsNulls() {
        val content = blocks("""[{"type": "compaction", "content": null, "signature": null}]""")
        val compaction = replayParam(content).getJSONArray("content").getJSONObject(0)
        assertTrue(compaction.has("content") && compaction.isNull("content"))
        assertTrue(compaction.has("signature") && compaction.isNull("signature"))
    }

    @Test
    fun openAIInputStartsAtLatestCompaction() {
        val response = JSONObject(
            """{"output": [
                {"type": "compaction", "id": "cmp_1", "encrypted_content": "enc"},
                {"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
                    {"type": "output_text", "text": "Reply.", "annotations": []}
                ]}
            ]}""",
        )
        val history = listOf(
            Turn(Role.User, "old"),
            Turn(Role.Assistant, "old reply"),
            Turn(Role.User, "long"),
            Turn(Role.Assistant, "Reply.", openaiItems = replayItems(response)),
            Turn(Role.User, "next"),
        )
        val input = openaiInput(history)
        assertEquals(3, input.length(), "want compaction, reply and next: $input")
        val first = input.getJSONObject(0)
        assertEquals("compaction", first.getString("type"))
        assertEquals("enc", first.getString("encrypted_content"))
        assertEquals("cmp_1", first.getString("id"))
    }

    @Test
    fun openAIModelListKeepsChatModels() {
        val now = LocalDate.of(2026, 10, 1)
        val cases = mapOf(
            "gpt-6-astra" to true,
            "gpt-6.1-sol" to true,
            "gpt-5.6-sol" to true,
            "gpt-10" to true,
            "gpt-5.5" to false, // before 5.6
            "gpt-5.4-mini" to false,
            "gpt-5" to false,
            "o3" to false,
            "gpt-4o-mini" to false, // no reasoning
            "gpt-3.5-turbo" to false,
            "gpt-6-sol-2026-09-01" to false, // a dated snapshot
            "gpt-4-0613" to false,
            "gpt-6-audio-preview" to false,
            "gpt-6-realtime" to false,
            "gpt-6-image" to false,
            "text-embedding-3-small" to false,
            "dall-e-3" to false,
            "whisper-1" to false,
            "omni-moderation-latest" to false,
        )
        for ((id, want) in cases) assertEquals(want, isOpenAIChatModel(id, null, now), "isOpenAIChatModel($id)")
        assertFalse(isOpenAIChatModel("gpt-5.6-terra", now.minusMonths(1), now), "a model past its shutdown date was kept")
    }

    @Test
    fun anthropicModelListStartsAt46() {
        val cases = mapOf(
            "claude-opus-4-6" to true,
            "claude-sonnet-4-6" to true,
            "claude-opus-5-5" to true,
            "claude-fable-5-1" to true,
            "claude-opus-5" to true,
            "claude-opus-4-10" to true,
            "claude-opus-4-6-20260205" to true,
            "claude-haiku-4-5-20251001" to false,
            "claude-opus-4-1-20250805" to false,
            "claude-opus-4-20250514" to false, // 4.0 with a date
            "claude-3-7-sonnet-20250219" to false,
        )
        for ((id, want) in cases) assertEquals(want, anthropicSupportedVersion(id), "anthropicSupportedVersion($id)")
    }

    @Test
    fun anthropicContextComesFromLastSamplingIteration() {
        val u = JSONObject(
            """{
                "input_tokens": 160000, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0, "output_tokens": 900,
                "iterations": [
                    {"type": "compaction", "input_tokens": 155000, "output_tokens": 3000},
                    {"type": "message", "input_tokens": 4000, "cache_read_input_tokens": 1000, "cache_creation_input_tokens": 0, "output_tokens": 900}
                ]
            }""",
        )
        assertEquals(Usage(160000, 900, 5900), anthropicUsage(u))
        u.remove("iterations")
        assertEquals(160900, anthropicUsage(u).context, "without iterations")
    }

    @Test
    fun coalesceMergesTextAndKeepsOrder() = runBlocking {
        val events = flowOf(
            StreamEvent.Thinking("a"), StreamEvent.Thinking("b"),
            StreamEvent.Delta("c"), StreamEvent.Delta("d"),
            StreamEvent.Thinking("e"),
            StreamEvent.Done(Turn(Role.Assistant, "cd")),
        )
        val got = events.coalesce(60 * 60 * 1000).toList().map {
            when (it) {
                is StreamEvent.Thinking -> "thinking:" + it.text
                is StreamEvent.Delta -> "delta:" + it.text
                is StreamEvent.Done -> "done"
                is StreamEvent.Failed -> "failed"
            }
        }
        assertEquals(listOf("thinking:ab", "delta:cd", "thinking:e", "done"), got)
    }

    @Test
    fun coalesceFlushesAfterWindow() = runBlocking {
        val input = Channel<StreamEvent>()
        val out = input.consumeAsFlow().coalesce(1).produceIn(this)
        input.send(StreamEvent.Delta("x"))
        val ev = withTimeout(5_000) { out.receive() }
        assertEquals(StreamEvent.Delta("x"), ev, "pending text not sent once the window passed")
        input.close()
        assertNull(withTimeout(5_000) { out.receiveCatching().getOrNull() }, "out not closed after in")
    }

    @Test
    fun coalesceStopsOnceCancelled() = runBlocking {
        val input = Channel<StreamEvent>() // never closed
        val received = mutableListOf<StreamEvent>()
        val job: Job = launch { input.consumeAsFlow().coalesce(60 * 60 * 1000).collect { received += it } }
        input.send(StreamEvent.Delta("held"))
        job.cancel()
        withTimeout(5_000) { job.join() } // must not block
        assertTrue(received.isEmpty(), "unexpected events $received")
    }

    @Test
    fun formatTokensShortens() {
        assertEquals("845", formatTokens(845))
        assertEquals("12.4K", formatTokens(12_400))
        assertEquals("12K", formatTokens(12_000))
        assertEquals("1.2M", formatTokens(1_234_567))
    }

    @Test
    fun formatDurationMatchesGo() {
        assertEquals("0s", formatDuration(0))
        assertEquals("2.3s", formatDuration(2_340))
        assertEquals("2s", formatDuration(1_960))
        assertEquals("1m2.5s", formatDuration(62_500))
        assertEquals("1h0m5s", formatDuration(3_605_000))
    }

    @Test
    fun describeErrorOfOtherErrors() {
        assertEquals("Cancelled.", describeError(kotlinx.coroutines.CancellationException("x")))
        assertEquals("boom", describeError(IllegalStateException("boom")))
    }
}
