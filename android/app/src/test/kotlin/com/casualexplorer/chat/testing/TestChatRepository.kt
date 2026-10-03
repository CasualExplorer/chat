package com.casualexplorer.chat.testing

import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.data.SessionChatRepository
import com.casualexplorer.chat.data.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A chat repository over [session], with fixed [settings] and nothing saved. */
class TestChatRepository(
    override val session: ChatSession,
    override val settings: StateFlow<UserSettings?> = MutableStateFlow(UserSettings()),
) : SessionChatRepository()
