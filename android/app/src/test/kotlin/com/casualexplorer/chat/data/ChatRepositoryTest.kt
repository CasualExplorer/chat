package com.casualexplorer.chat.data

import com.casualexplorer.chat.core.InMemoryConversationStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    @Test
    fun theSavedProviderModelAndEffortAreUsedFromTheStart() = runTest {
        val settings = FakeSettingsRepository(
            UserSettings(activeProvider = UserSettings.ANTHROPIC, anthropicModel = "claude-x", anthropicEffort = "high"),
        )
        val repo = DefaultChatRepository(settings, InMemoryConversationStore(), backgroundScope)
        repo.settings.filterNotNull().first()
        val state = repo.session.state.first { it.active == 0 && it.models.firstOrNull() == "claude-x" }
        assertEquals("high", state.efforts[0])
    }

    @Test
    fun aModelAndEffortPickedInTheChatAreRemembered() = runTest {
        val settings = FakeSettingsRepository()
        val repo = DefaultChatRepository(settings, InMemoryConversationStore(), backgroundScope)
        repo.settings.filterNotNull().first()

        assertTrue(repo.selectModel(0, "claude-y"))
        // Straight after the switch: the effort belongs to the provider just picked.
        assertTrue(repo.selectEffort("max"))

        val saved = settings.flow.first { it.anthropicEffort == "max" }
        assertEquals(UserSettings.ANTHROPIC, saved.activeProvider)
        assertEquals("claude-y", saved.anthropicModel)
        assertEquals(UserSettings.DEFAULT_EFFORT, saved.openaiEffort)
    }

    @Test
    fun settingsAreReportedOnlyOnceTheProvidersHaveThem() = runTest {
        val settings = FakeSettingsRepository(UserSettings(openaiKey = "sk-oa"))
        val repo = DefaultChatRepository(settings, InMemoryConversationStore(), backgroundScope)
        assertEquals(null, repo.settings.value)
        assertEquals("sk-oa", repo.settings.filterNotNull().first().openaiKey)
    }
}
