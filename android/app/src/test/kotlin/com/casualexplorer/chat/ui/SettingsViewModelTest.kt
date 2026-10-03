package com.casualexplorer.chat.ui

import android.app.Application
import com.casualexplorer.chat.data.FakeSettingsRepository
import com.casualexplorer.chat.data.ThemeMode
import com.casualexplorer.chat.data.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val saved = UserSettings(openaiKey = "sk-old", anthropicBaseUrl = "https://a.example")

    @Test
    fun theFieldsStartFromWhatIsSaved() = runTest(main.dispatcher) {
        val vm = SettingsViewModel(FakeSettingsRepository(saved))
        vm.uiState.first { it is SettingsUiState.Loaded }
        assertEquals("sk-old", vm.text.openaiKey)
        assertEquals("https://a.example", vm.text.anthropicBaseUrl)
    }

    @Test
    fun eachChangeIsSavedAsItIsMade() = runTest(main.dispatcher) {
        val repo = FakeSettingsRepository(saved)
        val vm = SettingsViewModel(repo)
        vm.uiState.first { it is SettingsUiState.Loaded }

        vm.setApiKey(1, "sk-new ")
        assertEquals("the field keeps what was typed", "sk-new ", vm.text.openaiKey)
        assertEquals("sk-new", repo.flow.value.openaiKey)

        vm.setTheme(ThemeMode.Dark)
        vm.setDynamicColor(false)
        assertEquals(ThemeMode.Dark, repo.flow.value.theme)
        assertEquals(false, repo.flow.value.dynamicColor)
    }

    @Test
    fun anAddressBeingTypedIsSavedOnlyOnceValid() = runTest(main.dispatcher) {
        val repo = FakeSettingsRepository(saved)
        val vm = SettingsViewModel(repo)
        vm.uiState.first { it is SettingsUiState.Loaded }

        vm.setBaseUrl(0, "https://")
        assertEquals("https://", vm.text.anthropicBaseUrl)
        assertEquals("https://a.example", repo.flow.value.anthropicBaseUrl)

        vm.setBaseUrl(0, "https://b.example")
        assertEquals("https://b.example", repo.flow.value.anthropicBaseUrl)
    }
}
