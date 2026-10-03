package com.casualexplorer.chat.data

import app.cash.turbine.test
import com.casualexplorer.chat.core.InMemoryConversationStore
import com.casualexplorer.chat.testing.TestSettingsRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    private fun TestScope.repository(settings: SettingsRepository) = DefaultChatRepository(settings, InMemoryConversationStore(), backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun theSavedProviderModelAndEffortAreUsedFromTheStart() = runTest {
        val settings = TestSettingsRepository(
            UserSettings(activeProvider = ApiProvider.Anthropic, anthropicModel = "claude-x", anthropicEffort = "high"),
        )
        val repo = repository(settings)
        repo.settings.filterNotNull().first()
        val state = repo.state.first { it.active == ApiProvider.Anthropic.index && it.models.firstOrNull() == "claude-x" }
        assertEquals("high", state.efforts[0])
    }

    @Test
    fun aModelAndEffortPickedInTheChatAreRemembered() = runTest {
        val settings = TestSettingsRepository()
        val repo = repository(settings)
        repo.settings.filterNotNull().first()

        assertTrue(repo.selectModel(ApiProvider.Anthropic, "claude-y"))
        // Straight after the switch: the effort belongs to the provider just picked.
        assertTrue(repo.selectEffort("max"))

        val saved = settings.flow.first { it.anthropicEffort == "max" }
        assertEquals(ApiProvider.Anthropic, saved.activeProvider)
        assertEquals("claude-y", saved.anthropicModel)
        assertEquals(UserSettings.DEFAULT_EFFORT, saved.openaiEffort)
    }

    @Test
    fun settingsAreReportedOnlyOnceTheProvidersHaveThem() = runTest {
        val repo = repository(TestSettingsRepository(UserSettings(openaiKey = "sk-oa")))
        repo.settings.test {
            assertNull(awaitItem())
            assertEquals("sk-oa", awaitItem()?.openaiKey)
        }
    }
}
