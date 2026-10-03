package com.casualexplorer.chat.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.ThemeMode
import com.casualexplorer.chat.data.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Loaded(val settings: UserSettings) : SettingsUiState
}

/** What is typed in the settings' text fields; providers are numbered 0 Anthropic, 1 OpenAI. */
@Immutable
data class SettingsText(
    val anthropicKey: String = "",
    val openaiKey: String = "",
    val anthropicBaseUrl: String = "",
    val openaiBaseUrl: String = "",
) {
    fun key(provider: Int) = if (provider == 0) anthropicKey else openaiKey

    fun baseUrl(provider: Int) = if (provider == 0) anthropicBaseUrl else openaiBaseUrl
}

/**
 * Settings, each saved as soon as it changes, as Now in Android's settings
 * are. The text fields are kept here as typed (a stored value is trimmed or
 * waits until it is a valid address), so the field never jumps while the
 * store catches up, and the keys never go into saved instance state.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    var text by mutableStateOf(SettingsText())
        private set

    private val textLoaded = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            text = SettingsText(s.anthropicKey, s.openaiKey, s.anthropicBaseUrl, s.openaiBaseUrl)
            textLoaded.value = true
        }
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(settingsRepository.settings, textLoaded) { settings, loaded ->
            if (loaded) SettingsUiState.Loaded(settings) else SettingsUiState.Loading
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState.Loading)

    fun setApiKey(provider: Int, key: String) {
        text = if (provider == 0) text.copy(anthropicKey = key) else text.copy(openaiKey = key)
        viewModelScope.launch { settingsRepository.setApiKey(provider, key) }
    }

    fun setBaseUrl(provider: Int, url: String) {
        text = if (provider == 0) text.copy(anthropicBaseUrl = url) else text.copy(openaiBaseUrl = url)
        viewModelScope.launch { settingsRepository.setBaseUrl(provider, url) }
    }

    fun setTheme(theme: ThemeMode) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }
}
