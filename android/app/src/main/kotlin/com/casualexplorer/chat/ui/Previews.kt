package com.casualexplorer.chat.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.window.core.layout.WindowSizeClass
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.ConversationSummary
import com.casualexplorer.chat.core.Usage
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.data.UserSettings
import java.time.LocalDate
import java.time.ZoneOffset

/** Sample states for previews and screenshot tests. Times are fixed, so they render the same every day. */
internal object SampleData {
    private val day = LocalDate.of(2026, 9, 28)

    private fun at(hour: Int, minute: Int) = day.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    val chat = ChatState(
        active = 1,
        providerNames = listOf("Anthropic", "OpenAI"),
        models = listOf("claude-sonnet-5-5", "gpt-5.6-luna"),
        efforts = listOf("medium", "high"),
        requestEfforts = listOf("medium", "high"),
        catalogs = listOf(emptyList(), emptyList()),
    )

    val conversation: ChatUiState = ChatUiState(
        chat = chat.copy(
            messages = listOf(
                UserMessage(1, "How do I reverse a list in Kotlin?", at(10, 41)),
                AssistantMessage(
                    id = 2,
                    provider = "OpenAI",
                    model = "gpt-5.6-luna",
                    text = "Use `reversed()`, which returns a new list:\n\n" +
                        "```kotlin\nval numbers = listOf(1, 2, 3)\nprintln(numbers.reversed()) // [3, 2, 1]\n```\n\n" +
                        "For a **mutable** list, `reverse()` works in place.",
                    thinking = "The user wants to reverse a list. `reversed()` is the idiomatic answer.",
                    usage = Usage(input = 1_234, output = 56, context = 1_290),
                    pending = false,
                    startMs = at(10, 41),
                    elapsedMs = 2_300,
                    thinkStartMs = 1,
                    thinkForMs = 800,
                ),
                UserMessage(3, "And in Go?", at(10, 43)),
                AssistantMessage(
                    id = 4,
                    provider = "Anthropic",
                    model = "claude-sonnet-5-5",
                    pending = false,
                    startMs = at(10, 43),
                    failure = "Anthropic API error 401 (authentication_error): invalid x-api-key " +
                        "(check the Anthropic API key in Settings)",
                ),
            ),
            usage = Usage(input = 1_234, output = 56, context = 1_290),
            conversationId = 3,
            conversations = listOf(
                ConversationSummary(3, "How do I reverse a list in Kotlin?", at(10, 43)),
                ConversationSummary(2, "Plan a weekend in Lisbon", at(9, 15)),
                ConversationSummary(1, "Explain the borrow checker", at(8, 2)),
            ),
        ),
        hasKey = true,
    )

    val noKey = ChatUiState(chat = chat, hasKey = false)

    val settings = UserSettings(openaiKey = "sk-test", anthropicBaseUrl = "https://gateway.example/anthropic")

    val settingsText = SettingsText(openaiKey = settings.openaiKey, anthropicBaseUrl = settings.anthropicBaseUrl)
}

/** The chat screen with no-op callbacks, for previews and screenshots. */
@Composable
internal fun ChatScreenSample(
    uiState: ChatUiState,
    input: String = "",
    onToggleThinking: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    wide: Boolean = false,
) =
    ChatScreen(
        uiState = uiState,
        input = TextFieldValue(input),
        onInputChange = {},
        onSend = {},
        onStop = {},
        onRetry = onRetry,
        onHistoryPrevious = { false },
        onHistoryNext = { false },
        onHistoryEscape = { false },
        onSelectModel = { _, _ -> },
        onSelectEffort = {},
        onNewChat = {},
        onToggleThinking = onToggleThinking,
        onUserMessageShown = {},
        onOpenSettings = {},
        wide = wide,
    )

@DevicePreviews
@Composable
private fun DevicesPreview() = ChatTheme(dynamicColor = false) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    ChatScreenSample(SampleData.conversation, wide = wide)
}

@ThemePreviews
@Composable
private fun ConversationPreview() = ChatTheme(dynamicColor = false) {
    ChatScreenSample(SampleData.conversation, input = "And in Rust?")
}

@Preview(name = "No key")
@Composable
private fun NoKeyPreview() = ChatTheme(dynamicColor = false) { ChatScreenSample(SampleData.noKey) }

@ThemePreviews
@Composable
private fun SettingsPreview() = ChatTheme(dynamicColor = false) {
    SampleSettingsScreen()
}

/** The settings screen with [SampleData.settings], for previews and screenshot tests. */
@Composable
internal fun SampleSettingsScreen() = SettingsScreen(
    settings = SampleData.settings,
    text = SampleData.settingsText,
    onApiKeyChange = { _, _ -> },
    onBaseUrlChange = { _, _ -> },
    onThemeChange = {},
    onDynamicColorChange = {},
    onBack = {},
)
