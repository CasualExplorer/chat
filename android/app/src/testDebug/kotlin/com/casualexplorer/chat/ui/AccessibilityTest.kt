package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs the Accessibility Test Framework's checks (touch target size,
 * contrast, labels, duplicate descriptions…) on each screen; an error fails
 * the test and names the element.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class AccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun check(dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent { ChatTheme(darkTheme = dark, dynamicColor = false, content = content) }
        composeRule.onRoot().checkRoboAccessibility()
    }

    @Test
    fun conversation() = check { ChatScreenSample(SampleData.conversation, input = "And in Rust?") }

    @Test
    fun conversationDark() = check(dark = true) { ChatScreenSample(SampleData.conversation) }

    @Test
    fun noKey() = check { ChatScreenSample(SampleData.noKey) }

    @Test
    fun settings() = check { SettingsScreen(SampleData.settings, onSave = {}, onBack = {}) }
}
