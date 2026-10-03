package com.casualexplorer.chat.ui

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
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.data.ChatRepository
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

/** Everything the chat screen draws. */
@Immutable
data class ChatUiState(
    val chat: ChatState = ChatState(),
    /** Whether the active provider has an API key. */
    val hasKey: Boolean = false,
    /** The replies whose whole thinking summary is shown. */
    val expandedThinking: Set<Long> = emptySet(),
    /** A short message for the snackbar, until [ChatViewModel.userMessageShown]. */
    val userMessage: String? = null,
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val session = chat.session

    /** What is typed in the input. */
    var draft by savedStateHandle.saveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
        private set

    private val expanded = MutableStateFlow(emptySet<Long>())
    private val userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatUiState> = combine(session.state, chat.settings, expanded, userMessage) { state, settings, exp, msg ->
        ChatUiState(state, settings?.hasKey(state.active) == true, exp, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(session.state.value))

    init {
        // A message whose reply failed before any text goes back in the
        // input, if that is empty. The session keeps it until this runs, so
        // it isn't lost while another screen is showing.
        viewModelScope.launch {
            session.restoredInput.filterNotNull().collect { text ->
                if (draft.text.isBlank()) draft = TextFieldValue(text, TextRange(text.length))
                session.restoredInputHandled()
            }
        }
    }

    fun onDraftChange(value: TextFieldValue) {
        // Editing leaves the prompt history.
        if (value.text != draft.text) session.prompts.edited(value.text)
        draft = value
    }

    /** Sends the draft, which is cleared once it is sent. */
    fun send() {
        val state = session.state.value
        if (draft.text.isBlank() || state.streaming) return
        if (chat.settings.value?.hasKey(state.active) != true) {
            userMessage.value = "Add your ${state.providerNames.getOrElse(state.active) { "" }} API key in Settings."
            return
        }
        // Cleared first: a reply can fail before submit returns, and its
        // message only comes back into an empty input.
        val sent = draft
        draft = TextFieldValue()
        if (!session.submit(sent.text)) draft = sent
    }

    fun stop() = session.cancel()

    /**
     * Sends again the message of the last reply, if that failed or was
     * stopped, as if typed again. If the message is back in the input, it
     * is taken out.
     */
    fun retry() {
        val state = session.state.value
        val last = state.messages.lastOrNull() as? AssistantMessage ?: return
        if (state.streaming || !last.failed) return
        val question = state.messages.getOrNull(state.messages.size - 2) as? UserMessage ?: return
        if (chat.settings.value?.hasKey(state.active) != true) {
            userMessage.value = "Add your ${state.providerNames.getOrElse(state.active) { "" }} API key in Settings."
            return
        }
        val before = draft
        if (draft.text.trim() == question.text) draft = TextFieldValue()
        if (!session.submit(question.text)) draft = before
    }

    /** Shows the previous message sent; false if there is none. */
    fun historyPrevious(): Boolean {
        val text = session.prompts.previous(draft.text) ?: return false
        draft = TextFieldValue(text, TextRange(0))
        return true
    }

    /** Shows the next message sent, or the draft past the newest; false if not in the history. */
    fun historyNext(): Boolean {
        val text = session.prompts.next() ?: return false
        draft = TextFieldValue(text, TextRange(text.length))
        return true
    }

    /** Goes back to the draft; false if no past message is shown. */
    fun historyEscape(): Boolean {
        val text = session.prompts.escape() ?: return false
        draft = TextFieldValue(text, TextRange(text.length))
        return true
    }

    fun selectModel(provider: Int, model: String) {
        session.selectModel(provider, model)?.let { userMessage.value = it }
    }

    fun selectEffort(effort: String) {
        session.selectEffort(effort)?.let { userMessage.value = it }
    }

    fun newChat() {
        val warning = session.newChat()
        if (warning != null) userMessage.value = warning else expanded.value = emptySet()
    }

    fun toggleThinking(replyId: Long) {
        expanded.update { if (replyId in it) it - replyId else it + replyId }
    }

    fun userMessageShown() {
        userMessage.value = null
    }
}
