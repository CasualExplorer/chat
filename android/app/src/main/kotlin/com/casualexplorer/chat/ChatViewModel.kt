package com.casualexplorer.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.AnthropicProvider
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.OpenAIProvider
import com.casualexplorer.chat.core.PromptHistory
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the conversation for the life of the app's task. The providers take
 * their keys and servers from the settings as they change; the start
 * provider, models and efforts are applied once, when the settings first
 * load, as the terminal app applies its flags.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    /** The latest settings, which the providers read their keys from on each request. */
    @Volatile
    private var current = UserSettings()

    private val anthropic = AnthropicProvider(current.anthropicModel, current.anthropicEffort, { current.anthropicKey })
    private val openai = OpenAIProvider(current.openaiModel, current.openaiEffort, { current.openaiKey })
    private val providers = listOf(anthropic, openai)

    private val session = ChatSession(providers, current.startIndex, viewModelScope)

    val state: StateFlow<ChatState> = session.state

    /** The saved settings, or null until they have loaded. */
    val settings: StateFlow<UserSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A message that failed before any reply text, to put back in an empty input. */
    val restoredInput: SharedFlow<String> = session.restoredInput

    val prompts: PromptHistory get() = session.prompts

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for the snackbar: warnings and missing keys. */
    val notices: SharedFlow<String> = _notices

    init {
        viewModelScope.launch {
            var first = true
            settingsRepository.settings.collect { s ->
                val keysChanged = s.anthropicKey != current.anthropicKey || s.openaiKey != current.openaiKey
                val serversChanged = s.anthropicBaseUrl != current.anthropicBaseUrl || s.openaiBaseUrl != current.openaiBaseUrl
                current = s
                anthropic.baseUrl = s.anthropicBaseUrl.ifEmpty { ANTHROPIC_BASE_URL }
                openai.baseUrl = s.openaiBaseUrl.ifEmpty { OPENAI_BASE_URL }
                if (first) {
                    first = false
                    anthropic.model = s.anthropicModel
                    anthropic.effort = s.anthropicEffort
                    openai.model = s.openaiModel
                    openai.effort = s.openaiEffort
                    // Makes the start provider active and publishes the rest.
                    session.selectModel(s.startIndex, providers[s.startIndex].model)
                    refreshModels()
                } else if (keysChanged || serversChanged) {
                    refreshModels()
                }
            }
        }
    }

    /** Lists each provider's models, for those with a key. */
    private fun refreshModels() {
        if (hasKey(0) || hasKey(1)) session.refreshModels()
    }

    fun hasKey(provider: Int) = current.hasKey(provider)

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
