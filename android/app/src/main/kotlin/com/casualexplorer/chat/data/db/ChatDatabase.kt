package com.casualexplorer.chat.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The start of the first message. */
    val title: String,
    val createdAt: Long,
    /** When a message was last added. */
    val updatedAt: Long,
)

/** One message; the columns after [inHistory] are for replies. See [com.casualexplorer.chat.core.MessageRecord]. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("status")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val createdAt: Long,
    val inHistory: Boolean,
    val provider: String,
    val model: String,
    val thinking: String,
    val note: String,
    val usageInput: Long?,
    val usageOutput: Long?,
    val usageContext: Long?,
    /** A [com.casualexplorer.chat.core.ReplyStatus] name. */
    val status: String,
    val failure: String,
    val elapsedMs: Long,
    val thinkForMs: Long,
    val anthropicReplay: String?,
    val openaiReplay: String?,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    fun messages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT id FROM conversations ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun latestConversationId(): Long?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteMessages(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationOnly(id: Long)

    /** Removes a conversation with its messages (explicitly, not relying on the foreign key's cascade). */
    @Transaction
    suspend fun deleteConversation(id: Long) {
        deleteMessages(id)
        deleteConversationOnly(id)
    }

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessageOnly(message: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touchConversation(id: Long, at: Long)

    /** Adds [message] and marks its conversation as just used. */
    @Transaction
    suspend fun insertMessage(message: MessageEntity): Long {
        val id = insertMessageOnly(message)
        touchConversation(message.conversationId, message.createdAt)
        return id
    }

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE status = 'Pending' ORDER BY id")
    suspend fun pendingMessages(): List<MessageEntity>
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
