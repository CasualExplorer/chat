package com.casualexplorer.chat.data.db

import com.casualexplorer.chat.core.ConversationStore
import com.casualexplorer.chat.core.MessageRecord
import com.casualexplorer.chat.core.ReplyStatus
import com.casualexplorer.chat.core.Role
import com.casualexplorer.chat.core.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Keeps conversations in the app's Room database. */
class RoomConversationStore @Inject constructor(private val dao: ChatDao) : ConversationStore {
    override fun messages(conversationId: Long): Flow<List<MessageRecord>> =
        dao.messages(conversationId).map { list -> list.map { it.toRecord() } }

    override suspend fun latestConversationId(): Long? = dao.latestConversationId()

    override suspend fun createConversation(title: String, now: Long): Long =
        dao.insertConversation(ConversationEntity(title = title, createdAt = now, updatedAt = now))

    override suspend fun insert(record: MessageRecord): Long = dao.insertMessage(record.toEntity().copy(id = 0))

    override suspend fun update(record: MessageRecord) = dao.updateMessage(record.toEntity())

    override suspend fun pendingReplies(): List<MessageRecord> = dao.pendingMessages().map { it.toRecord() }
}

internal fun MessageEntity.toRecord() = MessageRecord(
    id = id,
    conversationId = conversationId,
    role = if (role == ROLE_USER) Role.User else Role.Assistant,
    text = text,
    createdAt = createdAt,
    inHistory = inHistory,
    provider = provider,
    model = model,
    thinking = thinking,
    note = note,
    usage = if (usageInput != null && usageOutput != null && usageContext != null) Usage(usageInput, usageOutput, usageContext) else null,
    status = ReplyStatus.entries.firstOrNull { it.name == status } ?: ReplyStatus.Done,
    failure = failure,
    elapsedMs = elapsedMs,
    thinkForMs = thinkForMs,
    anthropicReplay = anthropicReplay,
    openaiReplay = openaiReplay,
)

internal fun MessageRecord.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = if (role == Role.User) ROLE_USER else ROLE_ASSISTANT,
    text = text,
    createdAt = createdAt,
    inHistory = inHistory,
    provider = provider,
    model = model,
    thinking = thinking,
    note = note,
    usageInput = usage?.input,
    usageOutput = usage?.output,
    usageContext = usage?.context,
    status = status.name,
    failure = failure,
    elapsedMs = elapsedMs,
    thinkForMs = thinkForMs,
    anthropicReplay = anthropicReplay,
    openaiReplay = openaiReplay,
)

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
