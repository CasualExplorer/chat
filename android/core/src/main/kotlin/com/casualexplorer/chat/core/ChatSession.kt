package com.casualexplorer.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

data class UserMessage(
    override val id: Long,
    val text: String,
    /** When it was sent. */
    val createdAt: Long = 0,
) : ChatMessage

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
    /** The conversation shown; null for a new chat not sent yet. */
    val conversationId: Long? = null,
    /** Every stored conversation, the most recent first. */
    val conversations: List<ConversationSummary> = emptyList(),
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

/** How often a streaming reply is saved, so most of it survives the app being closed. */
const val CHECKPOINT_MS = 1_000L

/** The failure shown on a reply that was still streaming when the app was closed. */
const val INTERRUPTED = "Interrupted: the app closed before the reply finished."

/**
 * The conversation: the providers and which is active, the messages, which
 * live in [store], and the reply streaming, if any. On creation it opens
 * the most recent conversation, after marking replies the app was closed
 * during as interrupted.
 *
 * It runs on [scope], whose dispatcher must be a single thread (the UI's).
 * Give it a scope that outlives the screen, so a reply keeps streaming
 * while the app is in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSession(
    private val providers: List<Provider>,
    active: Int,
    private val store: ConversationStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = STREAM_DEBOUNCE_MS,
    private val checkpointMs: Long = CHECKPOINT_MS,
) {
    /** Everything in the state but the messages. */
    private val config = MutableStateFlow(ChatState(active = active))

    init {
        // Before the state below is first built from it.
        publish { it }
    }

    /** The open conversation; null until the first message of a new chat. */
    private val conversationId = MutableStateFlow<Long?>(null)

    /**
     * The reply streaming, or the last one. It stands in for its stored
     * record while that is pending, so the screen follows the stream
     * without a write per token.
     */
    private val live = MutableStateFlow<AssistantMessage?>(null)

    val state: StateFlow<ChatState> = combine(
        config,
        // The id travels with its messages, so the state never pairs one
        // conversation's id with another's messages while switching.
        conversationId.flatMapLatest { id ->
            if (id == null) flowOf(null to emptyList()) else store.messages(id).map { id to it }
        },
        live,
        store.conversations(),
    ) { c, (id, records), reply, conversations ->
        c.copy(
            messages = records.map { r ->
                if (reply != null && r.id == reply.id && r.status == ReplyStatus.Pending) reply else r.toChatMessage()
            },
            conversationId = id,
            conversations = conversations,
        )
    }.stateIn(scope, SharingStarted.Eagerly, config.value)

    private val _restoredInput = MutableStateFlow<String?>(null)

    /**
     * The message of a reply that failed before any text arrived, which goes
     * back in the input to be resent if the input is empty. It stays until
     * [restoredInputHandled], so it isn't lost when no screen is showing.
     */
    val restoredInput: StateFlow<String?> = _restoredInput

    /** The screen has put [restoredInput] back, or decided not to. */
    fun restoredInputHandled() {
        _restoredInput.value = null
    }

    /** The messages sent this session, for Up and Down in the input. */
    val prompts = PromptHistory()

    private var job: Job? = null
    private var sentInput = ""

    /** Set once the user has chosen a conversation, so opening the latest doesn't override it. */
    private var chosen = false

    private val opening: Job = scope.launch {
        recoverInterrupted()
        val latest = store.latestConversationId()
        if (!chosen && latest != null) {
            conversationId.value = latest
            restoreUsage(latest)
        }
    }

    /** The open conversation as it will be sent with the next message. */
    suspend fun turns(): List<Turn> {
        opening.join()
        val id = conversationId.value ?: return emptyList()
        return store.messages(id).first().filter { it.inHistory }.map { it.toTurn() }
    }

    /** Copies the providers' settings into the state, after applying [change]. */
    private fun publish(change: (ChatState) -> ChatState) {
        config.update { current ->
            val s = change(current)
            s.copy(
                providerNames = providers.map { it.name },
                models = providers.map { it.model },
                efforts = providers.map { it.effort },
                requestEfforts = providers.map { it.requestEffort },
                catalogs = if (s.catalogs.size == providers.size) s.catalogs else providers.map { emptyList() },
            )
        }
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
        if (message.isEmpty() || config.value.streaming) return false
        val provider = providers[config.value.active]
        sentInput = text
        prompts.add(text)
        publish { it.copy(streaming = true) }
        job = scope.launch { stream(provider, provider.model, message, clock()) }
        return true
    }

    /** Stops the reply that is streaming. */
    fun cancel() {
        job?.cancel()
    }

    private suspend fun stream(provider: Provider, model: String, message: String, start: Long) {
        var user: MessageRecord? = null
        try {
            opening.join()
            val conversation = conversationId.value
                ?: store.createConversation(title(message), start).also { conversationId.value = it }
            chosen = true
            user = MessageRecord(conversationId = conversation, role = Role.User, text = message, createdAt = start)
                .let { it.copy(id = store.insert(it)) }
            val pending = AssistantMessage(0, provider.name, model, startMs = start)
            live.value = pending.copy(id = store.insert(replyRecord(pending, conversation, inHistory = false)))
            val history = store.messages(conversation).first().filter { it.inHistory }.map { it.toTurn() }

            var saved = clock()
            provider.stream(history).coalesce(debounceMs).collect { event ->
                val now = clock()
                when (event) {
                    is StreamEvent.Delta -> live.update { it?.endThinking(now)?.copy(text = it.text + event.text) }
                    is StreamEvent.Thinking -> live.update {
                        it?.copy(thinking = it.thinking + event.text, thinkStartMs = if (it.thinkStartMs == 0L) now else it.thinkStartMs)
                    }
                    is StreamEvent.Done -> {
                        val done = live.value!!.endThinking(now).copy(
                            text = event.turn.text,
                            note = event.note,
                            usage = event.usage,
                            pending = false,
                            elapsedMs = now - start,
                        )
                        live.value = done
                        store.update(replyRecord(done, conversation, inHistory = true, turn = event.turn))
                        if (event.usage != null) publish { it.copy(usage = event.usage) }
                    }
                    is StreamEvent.Failed -> fail(event.error, conversation, user)
                }
                if (event is StreamEvent.Delta || event is StreamEvent.Thinking) {
                    if (now - saved >= checkpointMs) {
                        saved = now
                        live.value?.let { store.update(replyRecord(it, conversation, inHistory = false)) }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                // Providers end with Done or Failed, except when cancelled.
                val reply = live.value
                if (reply != null && reply.pending && user != null) {
                    fail(CancellationException("Cancelled"), user.conversationId, user)
                }
                job = null
                publish { it.copy(streaming = false) }
            }
        }
    }

    /**
     * Ends the reply that is streaming with [error], as Crush does: the reply
     * stays in the chat, with the reason under any text that arrived. That
     * text joins the history, so the model sees what it said. A reply without
     * text leaves nothing to answer, so [user]'s message is taken out of the
     * history and put back in the input to be resent.
     */
    private suspend fun fail(error: Throwable, conversation: Long, user: MessageRecord) {
        val now = clock()
        val canceled = error is CancellationException
        val reply = live.value?.takeIf { it.pending } ?: return
        val failed = reply.endThinking(now).copy(
            pending = false,
            elapsedMs = now - reply.startMs,
            canceled = canceled,
            failure = if (canceled) "" else describeError(error),
        )
        live.value = failed
        val hasText = failed.text.isNotBlank()
        store.update(replyRecord(failed, conversation, inHistory = hasText))
        if (!hasText) {
            store.update(user.copy(inHistory = false))
            _restoredInput.value = sentInput
        }
    }

    /**
     * Ends the replies that were streaming when the app was last closed, by
     * the rules of [fail]: one with text keeps it in the history, and one
     * without takes its message out.
     */
    private suspend fun recoverInterrupted() {
        for (reply in store.pendingReplies()) {
            val hasText = reply.text.isNotBlank()
            store.update(reply.copy(status = ReplyStatus.Failed, failure = INTERRUPTED, inHistory = hasText))
            if (!hasText) {
                store.messages(reply.conversationId).first()
                    .lastOrNull { it.role == Role.User && it.id < reply.id }
                    ?.let { store.update(it.copy(inHistory = false)) }
            }
        }
    }

    /** Shows the context use of [conversation]'s last reply, if the active model sent it. */
    private suspend fun restoreUsage(conversation: Long) {
        val last = store.messages(conversation).first().lastOrNull { it.role == Role.Assistant && it.status == ReplyStatus.Done }
        val active = providers[config.value.active]
        if (last?.usage != null && last.provider == active.name && last.model == active.model) {
            publish { it.copy(usage = last.usage) }
        }
    }

    /**
     * Switches to [model] of provider [index]; the conversation carries over.
     * It returns a warning instead while a reply is streaming.
     */
    fun selectModel(index: Int, model: String): String? {
        if (config.value.streaming) return "Wait for the reply to finish (or tap Stop) before switching model."
        val p = providers[index]
        var clearUsage = false
        if (p.model != model) {
            p.model = model
            clearUsage = true
        }
        // The last reply's usage measured another model's context, and the
        // next request is sent in a different form; the next reply sets it.
        if (config.value.active != index) clearUsage = true
        publish { it.copy(active = index, usage = if (clearUsage) null else it.usage) }
        return null
    }

    /** Sets the active provider's reasoning effort. Each provider keeps its own. */
    fun selectEffort(effort: String): String? {
        if (config.value.streaming) return "Wait for the reply to finish (or tap Stop) before changing reasoning effort."
        providers[config.value.active].effort = effort
        publish { it }
        return null
    }

    /** Shows conversation [id]; the next message continues it. */
    fun openConversation(id: Long): String? {
        if (config.value.streaming) return "Wait for the reply to finish (or tap Stop) before opening another chat."
        chosen = true
        if (conversationId.value == id) return null
        conversationId.value = id
        publish { it.copy(usage = null) }
        scope.launch { restoreUsage(id) }
        return null
    }

    /** Deletes conversation [id] and its messages; deleting the open one starts a new chat. */
    fun deleteConversation(id: Long): String? {
        val open = conversationId.value == id
        if (open && config.value.streaming) return "Wait for the reply to finish (or tap Stop) before deleting this chat."
        if (open) newChat()
        scope.launch { store.deleteConversation(id) }
        return null
    }

    /** Starts a new conversation; the old one stays stored. */
    fun newChat(): String? {
        if (config.value.streaming) return "Wait for the reply to finish (or tap Stop) before starting a new chat."
        chosen = true
        conversationId.value = null
        publish { it.copy(usage = null) }
        return null
    }

    private companion object {
        /** A conversation's title: the start of its first message. */
        fun title(message: String): String {
            val line = message.lineSequence().first().trim()
            return if (line.length <= 80) line else line.take(79).trimEnd() + "…"
        }
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
