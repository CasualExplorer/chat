package com.casualexplorer.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * What the activity needs before the first screen: the theme and whether
 * there is an API key yet. As in Now in Android, it keeps the launch screen
 * up until the settings load.
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    // Eagerly: the launch screen reads the value before anything collects it.
    val uiState: StateFlow<MainActivityUiState> = settingsRepository.settings
        .map<UserSettings, MainActivityUiState> { MainActivityUiState.Success(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainActivityUiState.Loading)
}

sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState

    data class Success(val settings: UserSettings) : MainActivityUiState
}
