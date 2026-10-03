package com.casualexplorer.chat.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** How a reply ended, or that it hasn't yet. */
enum class ReplyStatus { Pending, Done, Canceled, Failed }

/**
 * A message as the store keeps it. A user message uses the fields up to
 * [inHistory]; a reply uses the rest too.
 */
data class MessageRecord(
    val id: Long = 0,
    val conversationId: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    /**
     * Whether the message is sent with later requests. A reply that failed
     * before any text isn't, and neither is the message it answered.
     */
    val inHistory: Boolean = true,
    val provider: String = "",
    val model: String = "",
    val thinking: String = "",
    val note: String = "",
    val usage: Usage? = null,
    val status: ReplyStatus = ReplyStatus.Done,
    val failure: String = "",
    val elapsedMs: Long = 0,
    val thinkForMs: Long = 0,
    /** The reply as the Anthropic API returned it (a JSON message), to replay it unchanged. */
    val anthropicReplay: String? = null,
    /** The reply as the OpenAI API returned it (JSON input items), to replay it unchanged. */
    val openaiReplay: String? = null,
)

/**
 * Where conversations are kept. The app keeps them in Room; tests and
 * previews use [InMemoryConversationStore].
 */
interface ConversationStore {
    /** The conversation's messages in the order they were added, as they change. */
    fun messages(conversationId: Long): Flow<List<MessageRecord>>

    /** The conversation most recently added to, if there is one. */
    suspend fun latestConversationId(): Long?

    suspend fun createConversation(title: String, now: Long): Long

    /** Adds [record] (its id is ignored) and returns its id. */
    suspend fun insert(record: MessageRecord): Long

    suspend fun update(record: MessageRecord)

    /** Replies still marked pending, e.g. because the app was closed while they streamed. */
    suspend fun pendingReplies(): List<MessageRecord>
}

/** A [ConversationStore] in memory. */
class InMemoryConversationStore : ConversationStore {
    private data class Conversation(val id: Long, val title: String, val updatedAt: Long)

    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val records = MutableStateFlow<List<MessageRecord>>(emptyList())
    private var nextId = 1L

    /** Every message, for tests. */
    val all: List<MessageRecord> get() = records.value

    override fun messages(conversationId: Long): Flow<List<MessageRecord>> =
        records.map { all -> all.filter { it.conversationId == conversationId } }

    override suspend fun latestConversationId(): Long? =
        conversations.value.maxWithOrNull(compareBy({ it.updatedAt }, { it.id }))?.id

    override suspend fun createConversation(title: String, now: Long): Long {
        val id = nextId++
        conversations.update { it + Conversation(id, title, now) }
        return id
    }

    override suspend fun insert(record: MessageRecord): Long {
        val id = nextId++
        records.update { it + record.copy(id = id) }
        conversations.update { all -> all.map { if (it.id == record.conversationId) it.copy(updatedAt = record.createdAt) else it } }
        return id
    }

    override suspend fun update(record: MessageRecord) {
        records.update { all -> all.map { if (it.id == record.id) record else it } }
    }

    override suspend fun pendingReplies(): List<MessageRecord> = records.value.filter { it.status == ReplyStatus.Pending }
}

/** The message as the chat shows it. */
fun MessageRecord.toChatMessage(): ChatMessage = when (role) {
    Role.User -> UserMessage(id, text)
    Role.Assistant -> AssistantMessage(
        id = id,
        provider = provider,
        model = model,
        text = text,
        thinking = thinking,
        note = note,
        usage = usage,
        pending = status == ReplyStatus.Pending,
        canceled = status == ReplyStatus.Canceled,
        failure = failure,
        startMs = createdAt,
        elapsedMs = elapsedMs,
        thinkForMs = thinkForMs,
    )
}

/** The message as a turn of the conversation sent to a provider. */
fun MessageRecord.toTurn(): Turn = Turn(
    role,
    text,
    anthropicMessage = anthropicReplay?.let(::JSONObject),
    openaiItems = openaiReplay?.let(::JSONArray),
)

/**
 * [message] as stored in [conversationId]. [turn] is the completed reply's
 * turn, whose replay payload is kept.
 */
fun replyRecord(message: AssistantMessage, conversationId: Long, inHistory: Boolean, turn: Turn? = null) = MessageRecord(
    id = message.id,
    conversationId = conversationId,
    role = Role.Assistant,
    text = message.text,
    createdAt = message.startMs,
    inHistory = inHistory,
    provider = message.provider,
    model = message.model,
    thinking = message.thinking,
    note = message.note,
    usage = message.usage,
    status = when {
        message.pending -> ReplyStatus.Pending
        message.canceled -> ReplyStatus.Canceled
        message.failure.isNotEmpty() -> ReplyStatus.Failed
        else -> ReplyStatus.Done
    },
    failure = message.failure,
    elapsedMs = message.elapsedMs,
    thinkForMs = message.thinkForMs,
    anthropicReplay = turn?.anthropicMessage?.toString(),
    openaiReplay = turn?.openaiItems?.toString(),
)
