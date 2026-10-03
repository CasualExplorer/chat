package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A provider that replies with a script of events, and records what it was sent. */
private class FakeProvider(override val name: String, override var model: String) : Provider {
    override var effort = "medium"
    var script: List<StreamEvent> = emptyList()
    var hang = false
    val sent = mutableListOf<List<Turn>>()

    override fun stream(history: List<Turn>): Flow<StreamEvent> = flow {
        sent += history
        script.forEach { emit(it) }
        if (hang) awaitCancellation()
    }

    override suspend fun listModels() = listOf(ModelInfo(model, 1000))
}

class ChatSessionTest {
    private fun CoroutineScope.session(vararg providers: Provider) = ChatSession(providers.toList(), 0, this, debounceMs = 1)

    private suspend fun ChatSession.awaitIdle() {
        yield()
        state.first { !it.streaming }
    }

    @Test
    fun replyIsStreamedIntoHistory() = runBlocking {
        val p = FakeProvider("A", "m")
        p.script = listOf(
            StreamEvent.Thinking("hmm"),
            StreamEvent.Delta("Hel"),
            StreamEvent.Delta("lo"),
            StreamEvent.Done(Turn(Role.Assistant, "Hello"), usage = Usage(10, 5, 15)),
        )
        val s = session(p)
        assertTrue(s.submit("  hi  "))
        s.awaitIdle()
        val reply = assertIs<AssistantMessage>(s.state.value.messages.last())
        assertEquals("Hello", reply.text)
        assertEquals("hmm", reply.thinking)
        assertFalse(reply.pending)
        assertEquals(Usage(10, 5, 15), s.state.value.usage)
        assertEquals(listOf("hi", "Hello"), s.turns.map { it.text })
        assertEquals(-1, s.state.value.contextPercent, "no catalog yet: percent unknown")
    }

    @Test
    fun failedReplyKeepsItsText() = runBlocking {
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Delta("partial"), StreamEvent.Failed(StreamError("Boom")))
        val s = session(p)
        s.submit("hi")
        s.awaitIdle()
        val reply = assertIs<AssistantMessage>(s.state.value.messages.last())
        assertEquals("Boom", reply.failure)
        assertEquals("partial", reply.text)
        assertEquals(listOf("hi", "partial"), s.turns.map { it.text }, "the partial text is sent with later turns")
    }

    @Test
    fun failedReplyWithoutTextPutsTheMessageBack() = runBlocking {
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Thinking("…"), StreamEvent.Failed(StreamError("Boom")))
        val s = session(p)
        var restored: String? = null
        val collector = launch { restored = s.restoredInput.first() }
        yield()
        s.submit("resend me")
        s.awaitIdle()
        collector.join()
        assertEquals("resend me", restored)
        assertTrue(s.turns.isEmpty(), "the message is left out of later requests")
        assertEquals("Boom", (s.state.value.messages.last() as AssistantMessage).failure)
    }

    @Test
    fun cancelKeepsPartialTextAndShowsCanceled() = runBlocking {
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Delta("so far"))
        p.hang = true
        val s = session(p)
        s.submit("hi")
        s.state.first { (it.messages.lastOrNull() as? AssistantMessage)?.text == "so far" }
        s.cancel()
        s.awaitIdle()
        val reply = s.state.value.messages.last() as AssistantMessage
        assertTrue(reply.canceled)
        assertEquals("", reply.failure)
        assertEquals(listOf("hi", "so far"), s.turns.map { it.text })
    }

    @Test
    fun modelPickerSwitchesProviderAndKeepsConversation() = runBlocking {
        val a = FakeProvider("A", "a1")
        val b = FakeProvider("B", "b1")
        a.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "from A"), usage = Usage(1, 1, 2)))
        b.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "from B")))
        val s = session(a, b)
        s.submit("one")
        s.awaitIdle()
        assertNull(s.selectModel(1, "b2"))
        assertEquals(1, s.state.value.active)
        assertEquals("b2", b.model)
        assertNull(s.state.value.usage, "switching model clears usage")
        s.submit("two")
        s.awaitIdle()
        assertEquals(listOf("one", "from A", "two"), b.sent.single().map { it.text })
        assertEquals("b2", (s.state.value.messages.last() as AssistantMessage).model)
    }

    @Test
    fun eachProviderKeepsItsEffort() = runBlocking {
        val a = FakeProvider("A", "a")
        val b = FakeProvider("B", "b")
        val s = session(a, b)
        s.selectEffort("max")
        s.selectModel(1, "b")
        s.selectEffort("low")
        assertEquals(listOf("max", "low"), s.state.value.efforts)
        s.selectModel(0, "a")
        assertEquals("max", s.state.value.efforts[s.state.value.active])
    }

    @Test
    fun changesWaitForTheReply() = runBlocking {
        val p = FakeProvider("A", "m")
        p.hang = true
        val s = session(p)
        s.submit("hi")
        yield()
        assertTrue(s.state.value.streaming)
        assertTrue(s.newChat()!!.startsWith("Wait for the reply"))
        assertTrue(s.selectModel(0, "x")!!.startsWith("Wait for the reply"))
        assertTrue(s.selectEffort("low")!!.startsWith("Wait for the reply"))
        assertFalse(s.submit("again"))
        s.cancel()
        s.awaitIdle()
        assertNull(s.newChat())
        assertTrue(s.state.value.messages.isEmpty())
        assertTrue(s.turns.isEmpty())
    }

    @Test
    fun contextPercentUsesTheListedWindow() = runBlocking {
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "x"), usage = Usage(800, 50, 850)))
        val s = session(p)
        s.refreshModels()
        s.state.first { it.catalogs.first().isNotEmpty() }
        s.submit("hi")
        s.awaitIdle()
        assertEquals(85, s.state.value.contextPercent)
        assertTrue(s.state.value.contextPercent > CONTEXT_WARN_PERCENT)
    }

    @Test
    fun promptHistorySteps() {
        val h = PromptHistory()
        h.add("one")
        h.add("two")
        assertEquals("two", h.previous("draft"))
        assertEquals("one", h.previous("ignored"))
        assertNull(h.previous("x"))
        assertEquals("two", h.next())
        assertEquals("draft", h.next())
        assertNull(h.next())
        h.previous("draft")
        assertEquals("draft", h.escape())
        assertNull(h.escape())
    }
}

class ModelChoicesTest {
    @Test
    fun pickerOffersCurrentThenListedOrKnownModels() {
        val state = ChatState(
            active = 1,
            providerNames = listOf("Anthropic", "OpenAI"),
            models = listOf("claude-sonnet-5-5", "gpt-5.6-luna"),
            catalogs = listOf(emptyList(), listOf(ModelInfo("gpt-6-astra"), ModelInfo("gpt-5.6-luna"))),
        )
        assertEquals(
            listOf(
                ModelChoice(0, "claude-sonnet-5-5", false),
                ModelChoice(0, "claude-opus-5-5", false),
                ModelChoice(1, "gpt-5.6-luna", true),
                ModelChoice(1, "gpt-6-astra", false),
            ),
            state.modelChoices(),
        )
    }
}
