package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.Usage
import com.casualexplorer.chat.core.UserMessage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the message views, recorded by `recordRoborazziDebug` and
 * checked by `verifyRoborazziDebug`. The references live in
 * `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: these tests don't need the app's Hilt graph.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class MessageViewsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun conversation() {
        composeRule.setContent {
            ChatTheme {
                Column(
                    Modifier.fillMaxSize().background(Palette.BgBase).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    UserMessageView(UserMessage(1, "How do I reverse a list in Kotlin?"))
                    AssistantMessageView(
                        AssistantMessage(
                            id = 2,
                            provider = "OpenAI",
                            model = "gpt-5.6-luna",
                            text = "Use `reversed()`, which returns a new list:\n\n" +
                                "```kotlin\nval numbers = listOf(1, 2, 3)\nprintln(numbers.reversed())\n```",
                            thinking = "The user wants to **reverse a list**. `reversed()` is the idiomatic answer.",
                            usage = Usage(input = 1_234, output = 56, context = 1_290),
                            pending = false,
                            elapsedMs = 2_300,
                            thinkStartMs = 1,
                            thinkForMs = 800,
                        ),
                        expanded = false,
                        onToggleThinking = {},
                        onCopy = {},
                    )
                    UserMessageView(UserMessage(3, "And in place?"))
                    AssistantMessageView(
                        AssistantMessage(
                            id = 4,
                            provider = "Anthropic",
                            model = "claude-sonnet-5-5",
                            pending = false,
                            failure = "Anthropic API error 401 (authentication_error): invalid x-api-key " +
                                "(check the Anthropic API key in Settings)",
                        ),
                        expanded = false,
                        onToggleThinking = {},
                        onCopy = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/conversation.png")
    }
}
