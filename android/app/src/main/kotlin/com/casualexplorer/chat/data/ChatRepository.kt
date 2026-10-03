package com.casualexplorer.chat.data

import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.AnthropicProvider
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.ConversationStore
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.OpenAIProvider
import com.casualexplorer.chat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The conversation and the settings the providers use, for the screens. */
interface ChatRepository {
    val session: ChatSession

    /** The saved settings, or null until they have loaded. */
    val settings: StateFlow<UserSettings?>
}

/**
 * The providers and the conversation, for the life of the process, so a
 * reply keeps streaming (and is saved) when the screen goes away.
 *
 * The providers take their keys and servers from the settings as they
 * change. The start provider, models and efforts are applied once, when the
 * settings first load, as the terminal app applies its flags.
 */
@Singleton
class DefaultChatRepository @Inject constructor(
    settingsRepository: SettingsRepository,
    store: ConversationStore,
    @ApplicationScope scope: CoroutineScope,
) : ChatRepository {
    /** The latest settings, which the providers read their keys from on each request. */
    @Volatile
    private var current = UserSettings()

    private val anthropic = AnthropicProvider(current.anthropicModel, current.anthropicEffort, { current.anthropicKey })
    private val openai = OpenAIProvider(current.openaiModel, current.openaiEffort, { current.openaiKey })
    private val providers = listOf(anthropic, openai)

    override val session = ChatSession(providers, current.startIndex, store, scope)

    override val settings: StateFlow<UserSettings?> = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
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
        if (current.hasKey(0) || current.hasKey(1)) session.refreshModels()
    }
}
