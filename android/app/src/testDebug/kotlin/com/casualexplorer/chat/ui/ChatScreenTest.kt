package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A phone-sized screen: Robolectric's default is small enough to push the
// thinking header out of view.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun reply(id: Long, text: String = "", thinking: String = "", pending: Boolean = false, failure: String = "") =
        AssistantMessage(id, "OpenAI", "gpt-5.6-luna", text = text, thinking = thinking, pending = pending, failure = failure, thinkForMs = 800)

    @Test
    fun sendIsEnabledOnlyWithText() {
        var input by mutableStateOf("")
        var sent = 0
        composeRule.setContent {
            ChatTheme(dynamicColor = false) {
                ChatScreen(
                    uiState = SampleData.noKey.copy(hasKey = true),
                    input = androidx.compose.ui.text.input.TextFieldValue(input),
                    onInputChange = {}, onSend = { sent++ }, onStop = {}, onRetry = {},
                    onHistoryPrevious = { false }, onHistoryNext = { false }, onHistoryEscape = { false },
                    onSelectModel = { _, _ -> }, onSelectEffort = {}, onNewChat = {}, onToggleThinking = {},
                    onUserMessageShown = {}, onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        input = "Hello"
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun stopReplacesSendWhileAReplyStreams() {
        var stopped = 0
        val streaming = SampleData.conversation.copy(
            chat = SampleData.chat.copy(
                streaming = true,
                messages = listOf(UserMessage(1, "Hi"), reply(2, text = "Hel", pending = true)),
            ),
        )
        composeRule.setContent {
            ChatTheme(dynamicColor = false) {
                ChatScreen(
                    uiState = streaming,
                    input = androidx.compose.ui.text.input.TextFieldValue(""),
                    onInputChange = {}, onSend = {}, onStop = { stopped++ }, onRetry = {},
                    onHistoryPrevious = { false }, onHistoryNext = { false }, onHistoryEscape = { false },
                    onSelectModel = { _, _ -> }, onSelectEffort = {}, onNewChat = {}, onToggleThinking = {},
                    onUserMessageShown = {}, onOpenSettings = {},
                )
            }
        }
        composeRule.onAllNodesWithContentDescription("Send").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Stop the reply").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun aLongThinkingSummaryExpandsWhenItsHeaderIsTapped() {
        val thinking = (1..15).joinToString("\n\n") { "Step $it." }
        var expanded by mutableStateOf(emptySet<Long>())
        composeRule.setContent {
            ChatTheme(dynamicColor = false) {
                ChatScreenSample(
                    SampleData.conversation.copy(
                        chat = SampleData.chat.copy(messages = listOf(UserMessage(1, "Hi"), reply(2, text = "Done.", thinking = thinking))),
                        expandedThinking = expanded,
                    ),
                    onToggleThinking = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                )
            }
        }
        composeRule.onNodeWithText("… 19 earlier lines").assertExists()
        composeRule.onNodeWithText("Thought for 0.8s").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("tapping the header toggles the reply's thinking", setOf(2L), expanded)
        composeRule.onAllNodesWithText("earlier lines", substring = true).assertCountEquals(0)
    }

    @Test
    fun onlyTheFailedLastReplyOffersRetry() {
        var retried = 0
        composeRule.setContent {
            ChatTheme(dynamicColor = false) {
                ChatScreenSample(
                    SampleData.conversation.copy(
                        chat = SampleData.chat.copy(
                            messages = listOf(
                                UserMessage(1, "One"),
                                reply(2, failure = "Earlier failure"),
                                UserMessage(3, "Two"),
                                reply(4, failure = "Boom"),
                            ),
                        ),
                    ),
                    onRetry = { retried++ },
                )
            }
        }
        composeRule.onAllNodesWithText("Retry").assertCountEquals(1)
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theDrawerOpensAndDeletesChats() {
        val opened = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val withChats = SampleData.conversation.copy(
            chat = SampleData.conversation.chat.copy(
                conversationId = 7,
                conversations = listOf(
                    com.casualexplorer.chat.core.ConversationSummary(7, "Reversing lists", 2),
                    com.casualexplorer.chat.core.ConversationSummary(3, "Trip to Lisbon", 1),
                ),
            ),
        )
        composeRule.setContent {
            ChatTheme(dynamicColor = false) {
                ChatScreen(
                    uiState = withChats,
                    input = androidx.compose.ui.text.input.TextFieldValue(""),
                    onInputChange = {}, onSend = {}, onStop = {}, onRetry = {},
                    onHistoryPrevious = { false }, onHistoryNext = { false }, onHistoryEscape = { false },
                    onSelectModel = { _, _ -> }, onSelectEffort = {}, onNewChat = {}, onToggleThinking = {},
                    onUserMessageShown = {}, onOpenSettings = {},
                    onOpenConversation = { opened += it },
                    onDeleteConversation = { deleted += it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Show chats").performClick()
        composeRule.onNodeWithText("Trip to Lisbon").performClick()
        assertEquals(listOf(3L), opened)

        composeRule.onNodeWithContentDescription("Show chats").performClick()
        composeRule.onAllNodesWithContentDescription("Delete chat")[1].performClick()
        composeRule.onNodeWithText("Delete this chat?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(3L), deleted)
    }
}
