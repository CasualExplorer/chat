package com.casualexplorer.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.casualexplorer.chat.core.AnthropicProvider
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.OpenAIProvider
import com.casualexplorer.chat.core.PromptHistory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the conversation for the life of the app's task. Nothing is written
 * to disk: the conversation is lost when the process ends, as in the
 * terminal app.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    private val providers = listOf(
        AnthropicProvider(settings.anthropicModel, settings.anthropicEffort, { settings.anthropicKey }),
        OpenAIProvider(settings.openaiModel, settings.openaiEffort, { settings.openaiKey }),
    )

    private val session = ChatSession(
        providers,
        if (settings.startProvider == "anthropic") 0 else 1,
        viewModelScope,
    )

    val state: StateFlow<ChatState> = session.state

    /** A message that failed before any reply text, to put back in an empty input. */
    val restoredInput: SharedFlow<String> = session.restoredInput

    val prompts: PromptHistory get() = session.prompts

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for the snackbar: warnings and missing keys. */
    val notices: SharedFlow<String> = _notices

    init {
        refreshModels()
    }

    /** Lists each provider's models, for those with a key. */
    fun refreshModels() {
        if (hasKey(0) || hasKey(1)) session.refreshModels()
    }

    fun hasKey(provider: Int) = when (provider) {
        0 -> settings.anthropicKey.isNotEmpty()
        else -> settings.openaiKey.isNotEmpty()
    }

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

    fun saveKeys(anthropic: String, openai: String) {
        settings.setKeys(anthropic, openai)
        refreshModels()
    }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }
}
