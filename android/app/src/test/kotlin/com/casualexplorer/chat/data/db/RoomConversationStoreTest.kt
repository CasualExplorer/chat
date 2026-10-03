package com.casualexplorer.chat.data.db

import android.app.Application
import androidx.room.Room
import com.casualexplorer.chat.core.MessageRecord
import com.casualexplorer.chat.core.ReplyStatus
import com.casualexplorer.chat.core.Role
import com.casualexplorer.chat.core.Usage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomConversationStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = RoomConversationStore(db.chatDao())

    @After
    fun close() = db.close()

    @Test
    fun messagesRoundTripWithEveryField() = runTest {
        val c = store.createConversation("Hello", 10)
        val reply = MessageRecord(
            conversationId = c,
            role = Role.Assistant,
            text = "Hi",
            createdAt = 12,
            inHistory = true,
            provider = "Anthropic",
            model = "claude-sonnet-5-5",
            thinking = "hmm",
            note = "Reply cut off.",
            usage = Usage(100, 20, 120),
            status = ReplyStatus.Done,
            elapsedMs = 2300,
            thinkForMs = 800,
            anthropicReplay = """{"role":"assistant","content":[{"type":"thinking","signature":"sig"}]}""",
        )
        val userId = store.insert(MessageRecord(conversationId = c, role = Role.User, text = "Hello", createdAt = 11))
        val replyId = store.insert(reply)

        val stored = store.messages(c).first()
        assertEquals(listOf(userId, replyId), stored.map { it.id })
        assertEquals(reply.copy(id = replyId), stored[1])
        assertNull(stored[0].usage)
    }

    @Test
    fun updatesAreSeenByTheFlow() = runTest {
        val c = store.createConversation("t", 1)
        val pending = MessageRecord(conversationId = c, role = Role.Assistant, text = "", createdAt = 1, inHistory = false, status = ReplyStatus.Pending)
        val id = store.insert(pending)
        assertEquals(listOf(id), store.pendingReplies().map { it.id })

        store.update(pending.copy(id = id, text = "done", status = ReplyStatus.Failed, failure = "Boom", inHistory = true))
        val updated = store.messages(c).first().single()
        assertEquals("done", updated.text)
        assertEquals(ReplyStatus.Failed, updated.status)
        assertEquals("Boom", updated.failure)
        assertEquals(emptyList<MessageRecord>(), store.pendingReplies())
    }

    @Test
    fun theLatestConversationIsTheOneLastAddedTo() = runTest {
        assertNull(store.latestConversationId())
        val old = store.createConversation("old", 1)
        val new = store.createConversation("new", 2)
        assertEquals(new, store.latestConversationId())
        store.insert(MessageRecord(conversationId = old, role = Role.User, text = "again", createdAt = 3))
        assertEquals(old, store.latestConversationId())
        assertEquals(emptyList<MessageRecord>(), store.messages(new).first())
    }
}
