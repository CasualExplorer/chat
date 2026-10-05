package com.casualexplorer.chat.data

import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.AnthropicProvider
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.ConversationStore
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.OpenAIProvider
import com.casualexplorer.chat.di.ApplicationScope
import com.casualexplorer.chat.di.ChatDispatchers
import com.casualexplorer.chat.di.Dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation, for the screens: its state as a Flow, and the actions on
 * it. The actions that return a Boolean return false, changing nothing,
 * while a reply streams.
 */
interface ChatRepository {
    /** Everything the chat screen draws. */
    val state: StateFlow<ChatState>

    /** The saved settings, or null until they have loaded and the providers use them. */
    val settings: StateFlow<UserSettings?>

    /**
     * The message of a reply that failed before any text arrived, to put
     * back in the input. It stays until [restoredInputHandled].
     */
    val restoredInput: StateFlow<String?>

    fun restoredInputHandled()

    /** Sends [text] and streams the reply; false if it is blank or a reply streams. */
    fun submit(text: String): Boolean

    /** Sends the last message again if its reply failed or was stopped. */
    fun retry(): Boolean

    /** Stops the reply that is streaming, if any. */
    fun cancel()

    fun newChat(): Boolean

    fun openConversation(id: Long): Boolean

    fun deleteConversation(id: Long): Boolean

    /** Switches to [model] of [provider] and remembers both for the next start. */
    fun selectModel(provider: ApiProvider, model: String): Boolean

    /** Sets the active provider's reasoning effort and remembers it. */
    fun selectEffort(effort: String): Boolean

    /** The messages sent this session, for Up and Down in the input. */
    val prompts: PromptHistoryNavigator
}

/**
 * Steps through the messages sent this session. Each step returns the text
 * to show, or null when there is nowhere to go.
 */
interface PromptHistoryNavigator {
    /** The previous (older) message; [current] is kept as the draft. */
    fun previous(current: String): String?

    /** The next (newer) message, or the draft past the newest. */
    fun next(): String?

    /** Goes back to the draft, if a past message is shown. */
    fun escape(): String?

    /** Editing the input leaves the history. */
    fun edited(text: String)
}

/**
 * A [ChatRepository] over a [ChatSession], which subclasses supply. Model and
 * effort changes are passed on to [modelSelected] and [effortSelected], to be
 * remembered.
 */
abstract class SessionChatRepository : ChatRepository {
    protected abstract val session: ChatSession

    protected open fun modelSelected(provider: ApiProvider, model: String) {}

    protected open fun effortSelected(provider: ApiProvider, effort: String) {}

    override val state: StateFlow<ChatState> get() = session.state

    override val restoredInput: StateFlow<String?> get() = session.restoredInput

    override fun restoredInputHandled() = session.restoredInputHandled()

    override fun submit(text: String) = session.submit(text)

    override fun retry() = session.retry()

    override fun cancel() = session.cancel()

    // The session's refusals are English sentences from the terminal app;
    // the screens show their own strings instead.

    override fun newChat() = session.newChat() == null

    override fun openConversation(id: Long) = session.openConversation(id) == null

    override fun deleteConversation(id: Long) = session.deleteConversation(id) == null

    override fun selectModel(provider: ApiProvider, model: String): Boolean {
        if (session.selectModel(provider.index, model) != null) return false
        modelSelected(provider, model)
        return true
    }

    override fun selectEffort(effort: String): Boolean {
        if (session.selectEffort(effort) != null) return false
        effortSelected(ApiProvider.fromIndex(session.active), effort)
        return true
    }

    override val prompts: PromptHistoryNavigator = object : PromptHistoryNavigator {
        override fun previous(current: String) = session.prompts.previous(current)

        override fun next() = session.prompts.next()

        override fun escape() = session.prompts.escape()

        override fun edited(text: String) = session.prompts.edited(text)
    }
}

/** How long the keys and servers must stay unchanged, as they are typed in Settings, before models are listed again. */
const val MODELS_REFRESH_DELAY_MS = 1_000L

/**
 * The providers and the conversation, for the life of the process, so a
 * reply keeps streaming (and is saved) while the screen changes.
 *
 * The settings are the source of truth for the keys and servers, which the
 * providers read on each request. The provider, models and efforts are
 * applied when the settings first load; after that the session holds them
 * while the app runs, and the chat changes them through [selectModel] and
 * [selectEffort], which save them too, so the app reopens with them.
 */
@OptIn(FlowPreview::class)
@Singleton
class DefaultChatRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    store: ConversationStore,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(ChatDispatchers.IO) ioDispatcher: CoroutineDispatcher,
) : SessionChatRepository() {
    /** The latest settings, which the providers read their keys and servers from on each request. */
    @Volatile
    private var current = UserSettings()

    private val anthropic = AnthropicProvider(
        current.anthropicModel,
        current.anthropicEffort,
        apiKey = { current.anthropicKey },
        baseUrlOf = { current.anthropicBaseUrl.ifEmpty { ANTHROPIC_BASE_URL } },
        ioDispatcher = ioDispatcher,
    )
    private val openai = OpenAIProvider(
        current.openaiModel,
        current.openaiEffort,
        apiKey = { current.openaiKey },
        baseUrlOf = { current.openaiBaseUrl.ifEmpty { OPENAI_BASE_URL } },
        ioDispatcher = ioDispatcher,
    )
    private val providers = listOf(anthropic, openai)

    override val session = ChatSession(providers, current.activeProvider.index, store, scope)

    private val loaded = MutableStateFlow<UserSettings?>(null)
    override val settings: StateFlow<UserSettings?> = loaded.asStateFlow()

    init {
        scope.launch {
            var first = true
            settingsRepository.settings.collect { s ->
                current = s
                if (first) {
                    first = false
                    anthropic.model = s.anthropicModel
                    anthropic.effort = s.anthropicEffort
                    openai.model = s.openaiModel
                    openai.effort = s.openaiEffort
                    // Makes the saved provider active and publishes the rest.
                    val active = s.activeProvider.index
                    session.selectModel(active, providers[active].model)
                }
                // Only now, so nothing is sent before the providers have their keys.
                loaded.value = s
            }
        }
        // Lists the models once the settings load, and again when the keys
        // or servers change and then stay unchanged for a moment.
        scope.launch {
            loaded.filterNotNull()
                .map { s -> ApiProvider.entries.flatMap { listOf(s.key(it), s.baseUrl(it)) } }
                .distinctUntilChanged()
                .debounce(MODELS_REFRESH_DELAY_MS)
                .collect { if (current.hasAnyKey) session.refreshModels() }
        }
    }

    override fun modelSelected(provider: ApiProvider, model: String) {
        scope.launch {
            settingsRepository.setModel(provider, model)
            settingsRepository.setActiveProvider(provider)
        }
    }

    override fun effortSelected(provider: ApiProvider, effort: String) {
        scope.launch { settingsRepository.setEffort(provider, effort) }
    }
}
