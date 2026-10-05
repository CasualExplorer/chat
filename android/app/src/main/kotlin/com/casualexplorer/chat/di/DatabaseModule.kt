package com.casualexplorer.chat.di

import android.content.Context
import androidx.room.Room
import com.casualexplorer.chat.core.ConversationStore
import com.casualexplorer.chat.data.db.ChatDao
import com.casualexplorer.chat.data.db.ChatDatabase
import com.casualexplorer.chat.data.db.RoomConversationStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {
    @Binds
    abstract fun conversationStore(impl: RoomConversationStore): ConversationStore

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db").build()

        @Provides
        fun chatDao(database: ChatDatabase): ChatDao = database.chatDao()
    }
}
