package com.casualexplorer.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.InMemoryConversationStore
import com.casualexplorer.chat.core.ModelInfo
import com.casualexplorer.chat.core.Provider
import com.casualexplorer.chat.core.StreamEvent
import com.casualexplorer.chat.core.Turn
import com.casualexplorer.chat.testing.TestChatRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopReplyOnLeaveTest {
    /** Writes "Hel" and then nothing more, until stopped. */
    private class SlowProvider : Provider {
        override val name = "Anthropic"
        override var model = "claude-test"
        override var effort = "medium"

        override fun stream(history: List<Turn>): Flow<StreamEvent> = flow {
            emit(StreamEvent.Delta("Hel"))
            awaitCancellation()
        }

        override suspend fun listModels() = emptyList<ModelInfo>()
    }

    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("not used")
    }

    @Test
    fun leavingTheAppStopsTheReplyAndKeepsWhatArrived() = runTest {
        val session = ChatSession(listOf(SlowProvider()), 0, InMemoryConversationStore(), backgroundScope, debounceMs = 0)
        assertTrue(session.submit("hi"))
        session.state.first { s -> (s.messages.lastOrNull() as? AssistantMessage)?.text == "Hel" }

        StopReplyOnLeave { TestChatRepository(session) }.onStop(owner)

        val ended = session.state.first { !it.streaming && (it.messages.last() as? AssistantMessage)?.pending == false }
        val reply = ended.messages.last() as AssistantMessage
        assertTrue(reply.canceled)
        assertEquals("Hel", reply.text)
    }

    @Test
    fun leavingWithNoReplyStreamingDoesNothing() = runTest {
        val session = ChatSession(listOf(SlowProvider()), 0, InMemoryConversationStore(), backgroundScope, debounceMs = 0)
        StopReplyOnLeave { TestChatRepository(session) }.onStop(owner)
        assertEquals(false, session.state.value.streaming)
    }
}
