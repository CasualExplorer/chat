package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

// The message state follows Crush's chat items (github.com/charmbracelet/crush,
// internal/ui/chat), Copyright 2025-2026 Charmbracelet, Inc., used under
// FSL-1.1-MIT.

/** One message in the conversation as the UI shows it. */
sealed interface ChatMessage {
    val id: Long
}

data class UserMessage(override val id: Long, val text: String) : ChatMessage

/**
 * A reply: the reasoning summary, the reply, any note, and a footer naming the
 * model. Until the reply's text starts it shows the spinner.
 */
data class AssistantMessage(
    override val id: Long,
    /** Who is replying. */
    val provider: String,
    val model: String,
    val text: String = "",
    /** Reasoning summary, streamed before the reply. */
    val thinking: String = "",
    val note: String = "",
    /** Once the reply completes, if the provider reported it. */
    val usage: Usage? = null,
    /** The reply hasn't completed yet. */
    val pending: Boolean = true,
    /** The reply was stopped; any text that arrived is kept. */
    val canceled: Boolean = false,
    /** Why the reply failed; any text that arrived is kept. */
    val failure: String = "",
    /** When the request was sent. */
    val startMs: Long = 0,
    /** Set once the reply completes. */
    val elapsedMs: Long = 0,
    /** First thinking event. */
    val thinkStartMs: Long = 0,
    /** Set once the reply text starts. */
    val thinkForMs: Long = 0,
) : ChatMessage {
    /** The reply shows the spinner until its text starts. */
    val spinning: Boolean get() = pending && text.isBlank()

    /** The reply ended without completing. */
    val failed: Boolean get() = canceled || failure.isNotEmpty()

    /** Records how long the model thought, once its reply begins. */
    internal fun endThinking(now: Long) =
        if (thinkStartMs != 0L && thinkForMs == 0L) copy(thinkForMs = maxOf(now - thinkStartMs, 1)) else this
}

/** Everything the chat screen draws, as one immutable snapshot. */
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val active: Int = 0,
    val streaming: Boolean = false,
    /** The last completed reply's usage, cleared when the model changes. */
    val usage: Usage? = null,
    val providerNames: List<String> = emptyList(),
    /** Each provider's model. */
    val models: List<String> = emptyList(),
    /** Each provider's chosen reasoning effort. */
    val efforts: List<String> = emptyList(),
    /** The effort each provider's next request sends, lowered to what its model supports. */
    val requestEfforts: List<String> = emptyList(),
    /** The models each provider listed; empty until (or if) listing works. */
    val catalogs: List<List<ModelInfo>> = emptyList(),
) {
    /** The current model's context window, if its provider reported it. */
    val contextWindow: Long
        get() = catalogs.getOrNull(active)?.firstOrNull { it.id == models.getOrNull(active) }?.contextWindow ?: 0

    /** How full the context window is after the last reply, or -1 if that isn't known. */
    val contextPercent: Int
        get() {
            val window = contextWindow
            val u = usage ?: return -1
            if (window <= 0) return -1
            return (u.context * 100 / window).toInt()
        }
}

/**
 * Models the picker offers for each provider, besides the one it was started
 * with, until the provider has listed its own.
 */
val KNOWN_MODELS = mapOf(
    "Anthropic" to listOf("claude-opus-5-5", "claude-sonnet-5-5"),
    "OpenAI" to listOf("gpt-6-astra", "gpt-6.1-sol"),
)

/** A model the picker offers, and the provider serving it. */
data class ModelChoice(val provider: Int, val model: String, val current: Boolean)

/**
 * What the model picker offers: each provider's current model, then the
 * models it listed or else the ones known here.
 */
fun ChatState.modelChoices(): List<ModelChoice> = providerNames.indices.flatMap { i ->
    val current = models[i]
    val listed = catalogs.getOrNull(i).orEmpty()
    val others = if (listed.isNotEmpty()) listed.map { it.id } else KNOWN_MODELS[providerNames[i]].orEmpty()
    (listOf(current) + others.filter { it != current }).mapIndexed { j, m -> ModelChoice(i, m, i == active && j == 0) }
}

/** Past this share of the context window, a warning sign shows by it. */
const val CONTEXT_WARN_PERCENT = 80

/**
 * The conversation: its history, the providers and which is active, and the
 * reply streaming, if any. It runs on [scope], whose dispatcher must be a
 * single thread (the UI's).
 */
class ChatSession(
    private val providers: List<Provider>,
    active: Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = STREAM_DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(ChatState(active = active))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _restoredInput = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * The message of a reply that failed before any text arrived, which goes
     * back in the input to be resent if the input is empty.
     */
    val restoredInput: SharedFlow<String> = _restoredInput

    /** The messages sent this session, for Up and Down in the input. */
    val prompts = PromptHistory()

    private val history = ArrayList<Turn>()
    private var job: Job? = null
    private var sentInput = ""
    private var nextId = 1L

    init {
        publish { it }
    }

    /** The conversation as it will be sent with the next message. */
    val turns: List<Turn> get() = history.toList()

    /** Copies the providers' settings into the state, after applying [change]. */
    private fun publish(change: (ChatState) -> ChatState) {
        val s = change(_state.value)
        _state.value = s.copy(
            providerNames = providers.map { it.name },
            models = providers.map { it.model },
            efforts = providers.map { it.effort },
            requestEfforts = providers.map { it.requestEffort },
            catalogs = if (s.catalogs.size == providers.size) s.catalogs else providers.map { emptyList() },
        )
    }

    /** Asks every provider for its models. Failures are not shown: the picker falls back to the models it knows. */
    fun refreshModels() {
        providers.forEachIndexed { i, p ->
            scope.launch {
                val models = try {
                    p.listModels()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return@launch
                }
                publish { s -> s.copy(catalogs = s.catalogs.toMutableList().also { it[i] = models }) }
            }
        }
    }

    /**
     * Sends [text] to the active provider and streams the reply. It returns
     * false, sending nothing, if the text is blank or a reply is streaming.
     */
    fun submit(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty() || _state.value.streaming) return false
        val provider = providers[_state.value.active]

        history += Turn(Role.User, message)
        sentInput = text
        prompts.add(text)
        val reply = AssistantMessage(nextId + 1, provider.name, provider.model, startMs = clock())
        val user = UserMessage(nextId, message)
        nextId += 2
        publish { it.copy(messages = it.messages + user + reply, streaming = true) }

        val events = provider.stream(history.toList()).coalesce(debounceMs)
        job = scope.launch {
            try {
                events.collect { handle(it) }
            } finally {
                withContext(NonCancellable) {
                    // Providers end with Done or Failed, except when cancelled.
                    if (lastAssistant()?.pending == true) fail(CancellationException("Cancelled"))
                    job = null
                    publish { it.copy(streaming = false) }
                }
            }
        }
        return true
    }

    /** Stops the reply that is streaming. */
    fun cancel() {
        job?.cancel()
    }

    private fun handle(event: StreamEvent) {
        val now = clock()
        when (event) {
            is StreamEvent.Delta -> updateLast { it.endThinking(now).copy(text = it.text + event.text) }
            is StreamEvent.Thinking -> updateLast {
                it.copy(thinking = it.thinking + event.text, thinkStartMs = if (it.thinkStartMs == 0L) now else it.thinkStartMs)
            }
            is StreamEvent.Done -> {
                history += event.turn
                updateLast {
                    it.endThinking(now).copy(
                        text = event.turn.text,
                        note = event.note,
                        usage = event.usage,
                        pending = false,
                        elapsedMs = now - it.startMs,
                    )
                }
                if (event.usage != null) publish { it.copy(usage = event.usage) }
            }
            is StreamEvent.Failed -> fail(event.error)
        }
    }

    /**
     * Ends the reply that is streaming with [error], as Crush does: the reply
     * stays in the chat, with the reason under any text that arrived. That
     * text joins the history, so the model sees what it said. A reply without
     * text leaves nothing to answer, so the message is taken out of the
     * history and put back in the input to be resent.
     */
    private fun fail(error: Throwable) {
        val now = clock()
        val canceled = error is CancellationException
        val last = updateLast {
            it.endThinking(now).copy(
                pending = false,
                elapsedMs = now - it.startMs,
                canceled = canceled,
                failure = if (canceled) "" else describeError(error),
            )
        } ?: return
        if (last.text.isNotBlank()) {
            history += Turn(Role.Assistant, last.text)
            return
        }
        if (history.isNotEmpty()) history.removeAt(history.size - 1)
        _restoredInput.tryEmit(sentInput)
    }

    private fun lastAssistant(): AssistantMessage? = _state.value.messages.lastOrNull() as? AssistantMessage

    private fun updateLast(change: (AssistantMessage) -> AssistantMessage): AssistantMessage? {
        val last = lastAssistant() ?: return null
        val updated = change(last)
        publish { it.copy(messages = it.messages.dropLast(1) + updated) }
        return updated
    }

    /**
     * Switches to [model] of provider [index]; the conversation carries over.
     * It returns a warning instead while a reply is streaming.
     */
    fun selectModel(index: Int, model: String): String? {
        if (_state.value.streaming) return "Wait for the reply to finish (or tap Stop) before switching model."
        val p = providers[index]
        var clearUsage = false
        if (p.model != model) {
            p.model = model
            clearUsage = true
        }
        // The last reply's usage measured another model's context, and the
        // next request is sent in a different form; the next reply sets it.
        if (_state.value.active != index) clearUsage = true
        publish { it.copy(active = index, usage = if (clearUsage) null else it.usage) }
        return null
    }

    /** Sets the active provider's reasoning effort. Each provider keeps its own. */
    fun selectEffort(effort: String): String? {
        if (_state.value.streaming) return "Wait for the reply to finish (or tap Stop) before changing reasoning effort."
        providers[_state.value.active].effort = effort
        publish { it }
        return null
    }

    /** Clears the conversation. */
    fun newChat(): String? {
        if (_state.value.streaming) return "Wait for the reply to finish (or tap Stop) before starting a new chat."
        history.clear()
        publish { it.copy(messages = emptyList(), usage = null) }
        return null
    }
}

/** Shortens a token count: 845, 12.4K, 1.2M. */
fun formatTokens(n: Long): String {
    val s = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
        else -> return n.toString()
    }
    return s.replaceFirst(".0", "")
}

/** A duration as Go prints one rounded to 100ms: "850ms" becomes "0.9s", then "2.3s", "1m2.3s", "1h0m5s". */
fun formatDuration(ms: Long): String {
    val tenths = (ms + 50) / 100
    if (tenths == 0L) return "0s"
    val hours = tenths / 36_000
    val minutes = tenths / 600 % 60
    val secTenths = tenths % 600
    val seconds = if (secTenths % 10 == 0L) "${secTenths / 10}s" else "${secTenths / 10}.${secTenths % 10}s"
    return when {
        hours > 0 -> "${hours}h${minutes}m$seconds"
        minutes > 0 -> "${minutes}m$seconds"
        else -> seconds
    }
}

/** How long a request has been running, for the spinner: "12s", "3m 4s", "1h 2m". */
fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours >= 1 -> "${hours}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun formatEffort(effort: String): String = when (effort) {
    "xhigh" -> "X-High"
    else -> effort.replaceFirstChar { it.uppercase() }
}
