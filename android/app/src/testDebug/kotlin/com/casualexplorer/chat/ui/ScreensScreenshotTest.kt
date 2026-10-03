package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.Usage
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.data.UserSettings
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of whole screens, from state alone; the references live in `src/test/screenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreensScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chat = ChatState(
        active = 1,
        providerNames = listOf("Anthropic", "OpenAI"),
        models = listOf("claude-sonnet-5-5", "gpt-5.6-luna"),
        efforts = listOf("medium", "high"),
        requestEfforts = listOf("medium", "high"),
        catalogs = listOf(emptyList(), emptyList()),
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ChatTheme(content) }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Chat(uiState: ChatUiState, input: String = "") = ChatScreen(
        uiState = uiState,
        input = TextFieldValue(input),
        onInputChange = {},
        onSend = {},
        onStop = {},
        onHistoryPrevious = { false },
        onHistoryNext = { false },
        onHistoryEscape = { false },
        onSelectModel = { _, _ -> },
        onSelectEffort = {},
        onNewChat = {},
        onToggleThinking = {},
        onUserMessageShown = {},
        onOpenSettings = {},
    )

    @Test
    fun chatWithAConversation() = capture("chat_conversation") {
        Chat(
            ChatUiState(
                chat = chat.copy(
                    messages = listOf(
                        UserMessage(1, "What is the capital of France?"),
                        AssistantMessage(
                            id = 2,
                            provider = "OpenAI",
                            model = "gpt-5.6-luna",
                            text = "**Paris.** It has been the capital since 987.",
                            usage = Usage(input = 900, output = 40, context = 940),
                            pending = false,
                            elapsedMs = 1_800,
                        ),
                    ),
                    usage = Usage(input = 900, output = 40, context = 940),
                ),
                hasKey = true,
            ),
            input = "And of Italy?",
        )
    }

    @Test
    fun chatWithoutAKey() = capture("chat_empty_no_key") {
        Chat(ChatUiState(chat = chat, hasKey = false))
    }

    @Test
    fun settings() = capture("settings") {
        SettingsScreen(
            UserSettings(openaiKey = "sk-test", anthropicBaseUrl = "https://gateway.example/anthropic"),
            onSave = {},
            onBack = {},
        )
    }
}
