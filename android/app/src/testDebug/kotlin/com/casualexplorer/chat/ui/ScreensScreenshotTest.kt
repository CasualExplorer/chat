package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of whole screens, from sample state, in light and dark and at
 * twice the font size. Dynamic colour is off, so they don't depend on a
 * wallpaper. The references live in `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreensScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            // The layout direction is set here: Robolectric's ldrtl qualifier
            // doesn't reach Compose.
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                ChatTheme(darkTheme = dark, dynamicColor = false, content = content)
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun conversation() = capture("chat_conversation") {
        ChatScreenSample(SampleData.conversation, input = "And in Rust?")
    }

    @Test
    fun conversationDark() = capture("chat_conversation_dark", dark = true) {
        ChatScreenSample(SampleData.conversation, input = "And in Rust?")
    }

    @Test
    fun conversationLargeFont() = capture("chat_conversation_font_2x", fontScale = 2f) {
        ChatScreenSample(SampleData.conversation)
    }

    @Test
    fun noKey() = capture("chat_empty_no_key") { ChatScreenSample(SampleData.noKey) }

    @Test
    fun settings() = capture("settings") { SampleSettingsScreen() }

    @Test
    fun settingsDark() = capture("settings_dark", dark = true) {
        SampleSettingsScreen()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletShowsTheChatsBeside() = capture("chat_tablet") {
        ChatScreenSample(SampleData.conversation, input = "And in Rust?", wide = true)
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp-xxhdpi")
    fun foldable() = capture("chat_foldable") { ChatScreenSample(SampleData.conversation) }

    @Test
    fun rightToLeft() = capture("chat_rtl", direction = LayoutDirection.Rtl) { ChatScreenSample(SampleData.conversation) }
}
