package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
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
    /**
     * Runs [block] with a scope for sessions that is cancelled at the end,
     * since a session keeps collecting its store, and a store.
     */
    private fun sessionTest(block: suspend CoroutineScope.(scope: CoroutineScope, store: InMemoryConversationStore) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            try {
                block(scope, InMemoryConversationStore())
            } finally {
                scope.cancel()
            }
        }

    private fun CoroutineScope.session(store: ConversationStore, vararg providers: Provider, checkpointMs: Long = CHECKPOINT_MS) =
        ChatSession(providers.toList(), 0, store, this, debounceMs = 1, checkpointMs = checkpointMs)

    /** Waits for the reply that makes [count] messages to end. */
    private suspend fun ChatSession.awaitReply(count: Int = 2): AssistantMessage = state.first { s ->
        !s.streaming && s.messages.size == count && (s.messages.last() as? AssistantMessage)?.pending == false
    }.messages.last() as AssistantMessage

    @Test
    fun replyIsStreamedIntoHistory() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(
            StreamEvent.Thinking("hmm"),
            StreamEvent.Delta("Hel"),
            StreamEvent.Delta("lo"),
            StreamEvent.Done(Turn(Role.Assistant, "Hello"), usage = Usage(10, 5, 15)),
        )
        val s = scope.session(store, p)
        assertTrue(s.submit("  hi  "))
        val reply = s.awaitReply()
        assertEquals("Hello", reply.text)
        assertEquals("hmm", reply.thinking)
        assertEquals(Usage(10, 5, 15), s.state.value.usage)
        assertEquals(listOf("hi", "Hello"), s.turns().map { it.text })
        assertEquals(listOf("hi"), p.sent.single().map { it.text }, "the request carries the message, not the pending reply")
        assertEquals(-1, s.state.value.contextPercent, "no catalog yet: percent unknown")
    }

    @Test
    fun failedReplyKeepsItsText() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Delta("partial"), StreamEvent.Failed(StreamError("Boom")))
        val s = scope.session(store, p)
        s.submit("hi")
        val reply = s.awaitReply()
        assertEquals("Boom", reply.failure)
        assertEquals("partial", reply.text)
        assertEquals(listOf("hi", "partial"), s.turns().map { it.text }, "the partial text is sent with later turns")
    }

    @Test
    fun failedReplyWithoutTextPutsTheMessageBack() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Thinking("…"), StreamEvent.Failed(StreamError("Boom")))
        val s = scope.session(store, p)
        s.submit("resend me")
        assertEquals("Boom", s.awaitReply().failure)
        assertEquals("resend me", s.restoredInput.value, "kept until a screen handles it")
        s.restoredInputHandled()
        assertNull(s.restoredInput.value)
        assertTrue(s.turns().isEmpty(), "the message is left out of later requests")
        assertEquals(2, s.state.value.messages.size, "but both stay in the chat")
    }

    @Test
    fun cancelKeepsPartialTextAndShowsCanceled() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Delta("so far"))
        p.hang = true
        val s = scope.session(store, p)
        s.submit("hi")
        s.state.first { (it.messages.lastOrNull() as? AssistantMessage)?.text == "so far" }
        s.cancel()
        val reply = s.awaitReply()
        assertTrue(reply.canceled)
        assertEquals("", reply.failure)
        assertEquals(listOf("hi", "so far"), s.turns().map { it.text })
    }

    @Test
    fun modelPickerSwitchesProviderAndKeepsConversation() = sessionTest { scope, store ->
        val a = FakeProvider("A", "a1")
        val b = FakeProvider("B", "b1")
        a.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "from A"), usage = Usage(1, 1, 2)))
        b.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "from B")))
        val s = scope.session(store, a, b)
        s.submit("one")
        s.awaitReply()
        assertNull(s.selectModel(1, "b2"))
        assertEquals(1, s.state.first { it.active == 1 }.active)
        assertEquals("b2", b.model)
        assertNull(s.state.value.usage, "switching model clears usage")
        s.submit("two")
        assertEquals("from B", s.awaitReply(4).text)
        assertEquals(listOf("one", "from A", "two"), b.sent.single().map { it.text })
        assertEquals("b2", (s.state.value.messages.last() as AssistantMessage).model)
    }

    @Test
    fun eachProviderKeepsItsEffort() = sessionTest { scope, store ->
        val a = FakeProvider("A", "a")
        val b = FakeProvider("B", "b")
        val s = scope.session(store, a, b)
        s.selectEffort("max")
        s.selectModel(1, "b")
        s.selectEffort("low")
        assertEquals(listOf("max", "low"), s.state.first { it.efforts == listOf("max", "low") }.efforts)
        s.selectModel(0, "a")
        assertEquals("max", s.state.first { it.active == 0 }.efforts[0])
    }

    @Test
    fun changesWaitForTheReply() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.hang = true
        val s = scope.session(store, p)
        s.submit("hi")
        s.state.first { it.streaming }
        assertTrue(s.newChat()!!.startsWith("Wait for the reply"))
        assertTrue(s.selectModel(0, "x")!!.startsWith("Wait for the reply"))
        assertTrue(s.selectEffort("low")!!.startsWith("Wait for the reply"))
        assertFalse(s.submit("again"))
        s.cancel()
        s.awaitReply()
        assertNull(s.newChat())
        s.state.first { it.messages.isEmpty() }
        assertTrue(s.turns().isEmpty())
    }

    @Test
    fun contextPercentUsesTheListedWindow() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "x"), usage = Usage(800, 50, 850)))
        val s = scope.session(store, p)
        s.refreshModels()
        s.state.first { it.catalogs.first().isNotEmpty() }
        s.submit("hi")
        s.awaitReply()
        val state = s.state.first { it.usage != null }
        assertEquals(85, state.contextPercent)
        assertTrue(state.contextPercent > CONTEXT_WARN_PERCENT)
    }

    @Test
    fun theConversationAndItsReplayPayloadOutliveTheSession() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        val replay = JSONObject().put("role", "assistant").put("content", "signed thinking")
        p.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "Hello", anthropicMessage = replay), usage = Usage(1, 2, 3)))
        val first = scope.session(store, p)
        first.submit("hi")
        first.awaitReply()

        // As after the app restarts: a new session over the same store.
        val again = scope.session(store, p)
        val state = again.state.first { it.messages.size == 2 }
        assertEquals("Hello", (state.messages.last() as AssistantMessage).text)
        assertEquals(Usage(1, 2, 3), again.state.first { it.usage != null }.usage, "the last reply's context use, same model")
        val turns = again.turns()
        assertEquals(replay.toString(), turns.last().anthropicMessage.toString(), "replayed to the provider unchanged")
    }

    @Test
    fun repliesTheAppWasClosedDuringAreMarkedInterrupted() = sessionTest { scope, store ->
        val c = store.createConversation("t", 1)
        val keptUser = store.insert(MessageRecord(conversationId = c, role = Role.User, text = "one", createdAt = 1))
        val partial = store.insert(
            MessageRecord(conversationId = c, role = Role.Assistant, text = "half", createdAt = 2, inHistory = false, status = ReplyStatus.Pending),
        )
        val droppedUser = store.insert(MessageRecord(conversationId = c, role = Role.User, text = "two", createdAt = 3))
        val empty = store.insert(
            MessageRecord(conversationId = c, role = Role.Assistant, text = "", createdAt = 4, inHistory = false, status = ReplyStatus.Pending),
        )

        val s = scope.session(store, FakeProvider("A", "m"))
        val replies = s.state.first { st -> st.messages.size == 4 && st.messages.none { it is AssistantMessage && it.pending } }
            .messages.filterIsInstance<AssistantMessage>()
        assertEquals(listOf(INTERRUPTED, INTERRUPTED), replies.map { it.failure })
        assertEquals(listOf("one", "half"), s.turns().map { it.text }, "text that arrived is kept; a message with no reply is left out")
        val byId = store.all.associateBy { it.id }
        assertTrue(byId.getValue(keptUser).inHistory && byId.getValue(partial).inHistory)
        assertFalse(byId.getValue(droppedUser).inHistory || byId.getValue(empty).inHistory)
    }

    @Test
    fun newChatStartsAnotherConversationAndKeepsTheOld() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "ok")))
        val s = scope.session(store, p)
        s.submit("first chat")
        s.awaitReply()
        assertNull(s.newChat())
        s.state.first { it.messages.isEmpty() }
        s.submit("second chat")
        s.awaitReply(2)
        assertEquals(listOf("second chat", "ok"), s.turns().map { it.text })
        assertEquals(2, store.all.map { it.conversationId }.distinct().size)
        assertEquals(listOf("second chat"), p.sent.last().map { it.text }, "the new chat starts empty")
    }

    @Test
    fun aStreamingReplyIsSavedAsItArrives() = sessionTest { scope, store ->
        val p = FakeProvider("A", "m")
        p.script = listOf(StreamEvent.Delta("so far"))
        p.hang = true
        val s = scope.session(store, p, checkpointMs = 0)
        s.submit("hi")
        withTimeout(5_000) {
            while (store.all.none { it.role == Role.Assistant && it.text == "so far" && it.status == ReplyStatus.Pending }) delay(5)
        }
        s.cancel()
        s.awaitReply()
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
