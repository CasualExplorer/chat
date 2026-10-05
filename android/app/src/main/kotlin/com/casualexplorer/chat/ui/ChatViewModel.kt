package com.casualexplorer.chat.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.data.ApiProvider
import com.casualexplorer.chat.data.ChatRepository
import com.casualexplorer.chat.data.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A snackbar message: a string resource and its arguments, which the screen resolves. */
@Immutable
data class SnackbarMessage(@StringRes val text: Int, val args: List<String> = emptyList())

/** Everything the chat screen draws. */
@Immutable
data class ChatUiState(
    val chat: ChatState = ChatState(),
    /** Whether the active provider has an API key. */
    val hasKey: Boolean = false,
    /** The replies whose whole thinking summary is shown. */
    val expandedThinking: Set<Long> = emptySet(),
    /** A short message for the snackbar, until [ChatViewModel.userMessageShown]. */
    val userMessage: SnackbarMessage? = null,
    /** The device has no network that reaches the internet. */
    val offline: Boolean = false,
)

/**
 * The chat screen's state and actions. The draft is kept in
 * [SavedStateHandle], so it survives the process being stopped in the
 * background.
 */
@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    networkMonitor: NetworkMonitor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** What is typed in the input. */
    var draft by savedStateHandle.saveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
        private set

    private val expanded = MutableStateFlow(emptySet<Long>())
    private val userMessage = MutableStateFlow<SnackbarMessage?>(null)

    private val online: StateFlow<Boolean> = networkMonitor.isOnline.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val uiState: StateFlow<ChatUiState> =
        combine(chat.state, chat.settings, expanded, userMessage, online) { state, settings, exp, msg, isOnline ->
            ChatUiState(
                chat = state,
                hasKey = settings?.hasKey(ApiProvider.fromIndex(state.active)) == true,
                expandedThinking = exp,
                userMessage = msg,
                offline = !isOnline,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(chat.state.value))

    init {
        // A message whose reply failed before any text goes back in the
        // input, if that is empty. The session keeps it until this runs, so
        // it isn't lost while another screen is showing.
        viewModelScope.launch {
            chat.restoredInput.filterNotNull().collect { text ->
                if (draft.text.isBlank()) draft = TextFieldValue(text, TextRange(text.length))
                chat.restoredInputHandled()
            }
        }
    }

    fun onDraftChange(value: TextFieldValue) {
        // Editing leaves the prompt history.
        if (value.text != draft.text) chat.prompts.edited(value.text)
        draft = value
    }

    /** Sends the draft, which is cleared once it is sent. */
    fun send() {
        val state = chat.state.value
        if (draft.text.isBlank() || state.streaming) return
        if (chat.settings.value?.hasKey(ApiProvider.fromIndex(state.active)) != true) {
            userMessage.value = SnackbarMessage(R.string.add_key, listOf(state.providerNames.getOrElse(state.active) { "" }))
            return
        }
        if (!online.value) {
            userMessage.value = SnackbarMessage(R.string.offline_send)
            return
        }
        // Cleared first: a reply can fail before submit returns, and its
        // message only comes back into an empty input.
        val sent = draft
        draft = TextFieldValue()
        if (!chat.submit(sent.text)) draft = sent
    }

    fun stop() = chat.cancel()

    /**
     * Sends again the message of the last reply, if that failed or was
     * stopped; the failed attempt is left out of later requests. If the
     * message is back in the input, it is taken out.
     */
    fun retry() {
        val state = chat.state.value
        val last = state.messages.lastOrNull() as? AssistantMessage ?: return
        if (state.streaming || !last.failed) return
        val question = state.messages.getOrNull(state.messages.size - 2) as? UserMessage ?: return
        if (chat.settings.value?.hasKey(ApiProvider.fromIndex(state.active)) != true) {
            userMessage.value = SnackbarMessage(R.string.add_key, listOf(state.providerNames.getOrElse(state.active) { "" }))
            return
        }
        if (!online.value) {
            userMessage.value = SnackbarMessage(R.string.offline_send)
            return
        }
        val before = draft
        if (draft.text.trim() == question.text) draft = TextFieldValue()
        if (!chat.retry()) draft = before
    }

    /** Shows the previous message sent; false if there is none. */
    fun historyPrevious(): Boolean {
        val text = chat.prompts.previous(draft.text) ?: return false
        draft = TextFieldValue(text, TextRange(0))
        return true
    }

    /** Shows the next message sent, or the draft past the newest; false if not in the history. */
    fun historyNext(): Boolean {
        val text = chat.prompts.next() ?: return false
        draft = TextFieldValue(text, TextRange(text.length))
        return true
    }

    /** Goes back to the draft; false if no past message is shown. */
    fun historyEscape(): Boolean {
        val text = chat.prompts.escape() ?: return false
        draft = TextFieldValue(text, TextRange(text.length))
        return true
    }

    // The repository refuses these while a reply streams.

    /** Switches model or provider; the choice is remembered for the next start. */
    fun selectModel(provider: ApiProvider, model: String) {
        if (!chat.selectModel(provider, model)) userMessage.value = SnackbarMessage(R.string.wait_switch_model)
    }

    /** Sets the active provider's reasoning effort; it is remembered for the next start. */
    fun selectEffort(effort: String) {
        if (!chat.selectEffort(effort)) userMessage.value = SnackbarMessage(R.string.wait_change_effort)
    }

    fun newChat() {
        if (!chat.newChat()) userMessage.value = SnackbarMessage(R.string.wait_new_chat) else expanded.value = emptySet()
    }

    fun openConversation(id: Long) {
        if (!chat.openConversation(id)) userMessage.value = SnackbarMessage(R.string.wait_open_chat) else expanded.value = emptySet()
    }

    fun deleteConversation(id: Long) {
        if (!chat.deleteConversation(id)) userMessage.value = SnackbarMessage(R.string.wait_delete_chat)
    }

    fun toggleThinking(replyId: Long) {
        expanded.update { if (replyId in it) it - replyId else it + replyId }
    }

    fun userMessageShown() {
        userMessage.value = null
    }
}
