package com.casualexplorer.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.PromptHistory
import com.casualexplorer.chat.data.ChatRepository
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The chat and settings screens' view of [ChatRepository]. */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val session = chat.session

    val state: StateFlow<ChatState> = session.state

    /** The saved settings, or null until they have loaded. */
    val settings: StateFlow<UserSettings?> = chat.settings

    /** A message that failed before any reply text, to put back in an empty input. */
    val restoredInput: SharedFlow<String> = session.restoredInput

    val prompts: PromptHistory get() = session.prompts

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for the snackbar: warnings and missing keys. */
    val notices: SharedFlow<String> = _notices

    fun hasKey(provider: Int) = chat.hasKey(provider)

    /** Sends [text]; returns whether it was sent, so the input can be cleared. */
    fun submit(text: String): Boolean {
        val active = state.value.active
        if (text.isBlank() || state.value.streaming) return false
        if (!hasKey(active)) {
            notice("Add your ${state.value.providerNames[active]} API key in Settings.")
            return false
        }
        return session.submit(text)
    }

    fun cancel() = session.cancel()

    fun selectModel(provider: Int, model: String) = session.selectModel(provider, model)?.let(::notice)

    fun selectEffort(effort: String) = session.selectEffort(effort)?.let(::notice)

    fun newChat() = session.newChat()?.let(::notice)

    /** Saves [settings]; keys and servers apply at once, the defaults when the app next starts. */
    fun saveSettings(settings: UserSettings) {
        viewModelScope.launch { settingsRepository.save(settings) }
    }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }
}
