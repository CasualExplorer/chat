package com.casualexplorer.chat.data

import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.AnthropicProvider
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.ConversationStore
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.OpenAIProvider
import com.casualexplorer.chat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The conversation and the settings the providers use, for the screens. */
interface ChatRepository {
    val session: ChatSession

    /** The saved settings, or null until they have loaded and the providers use them. */
    val settings: StateFlow<UserSettings?>

    /**
     * Switches to [model] of [provider] and remembers both for the next
     * start. It returns false, changing nothing, while a reply streams.
     */
    fun selectModel(provider: Int, model: String): Boolean

    /** Sets the active provider's reasoning effort and remembers it; false while a reply streams. */
    fun selectEffort(effort: String): Boolean
}

/** How long the keys and servers must stay unchanged, as they are typed in Settings, before models are listed again. */
const val MODELS_REFRESH_DELAY_MS = 1_000L

/**
 * The providers and the conversation, for the life of the process, so a
 * reply keeps streaming (and is saved) while the screen changes.
 *
 * The providers take their keys and servers from the settings as they
 * change. The provider, models and efforts are applied when the settings
 * first load; after that the chat changes them, through [selectModel] and
 * [selectEffort], which save them too.
 */
@OptIn(FlowPreview::class)
@Singleton
class DefaultChatRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    store: ConversationStore,
    @ApplicationScope private val scope: CoroutineScope,
) : ChatRepository {
    /** The latest settings, which the providers read their keys from on each request. */
    @Volatile
    private var current = UserSettings()

    private val anthropic = AnthropicProvider(current.anthropicModel, current.anthropicEffort, { current.anthropicKey })
    private val openai = OpenAIProvider(current.openaiModel, current.openaiEffort, { current.openaiKey })
    private val providers = listOf(anthropic, openai)

    override val session = ChatSession(providers, current.activeIndex, store, scope)

    private val _settings = MutableStateFlow<UserSettings?>(null)
    override val settings: StateFlow<UserSettings?> = _settings

    init {
        scope.launch {
            var first = true
            settingsRepository.settings.collect { s ->
                current = s
                anthropic.baseUrl = s.anthropicBaseUrl.ifEmpty { ANTHROPIC_BASE_URL }
                openai.baseUrl = s.openaiBaseUrl.ifEmpty { OPENAI_BASE_URL }
                if (first) {
                    first = false
                    anthropic.model = s.anthropicModel
                    anthropic.effort = s.anthropicEffort
                    openai.model = s.openaiModel
                    openai.effort = s.openaiEffort
                    // Makes the saved provider active and publishes the rest.
                    session.selectModel(s.activeIndex, providers[s.activeIndex].model)
                }
                // Only now, so nothing is sent before the providers have their keys.
                _settings.value = s
            }
        }
        // Lists the models once the settings load, and again when the keys
        // or servers change and then stay unchanged for a moment.
        scope.launch {
            _settings.filterNotNull()
                .map { listOf(it.anthropicKey, it.openaiKey, it.anthropicBaseUrl, it.openaiBaseUrl) }
                .distinctUntilChanged()
                .debounce(MODELS_REFRESH_DELAY_MS)
                .collect { if (current.hasKey(0) || current.hasKey(1)) session.refreshModels() }
        }
    }

    override fun selectModel(provider: Int, model: String): Boolean {
        if (session.selectModel(provider, model) != null) return false
        scope.launch {
            settingsRepository.setModel(provider, model)
            settingsRepository.setActiveProvider(provider)
        }
        return true
    }

    override fun selectEffort(effort: String): Boolean {
        if (session.selectEffort(effort) != null) return false
        val provider = session.active
        scope.launch { settingsRepository.setEffort(provider, effort) }
        return true
    }
}
