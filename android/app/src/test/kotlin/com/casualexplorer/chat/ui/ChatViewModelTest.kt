package com.casualexplorer.chat.ui

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatSession
import com.casualexplorer.chat.core.InMemoryConversationStore
import com.casualexplorer.chat.core.ModelInfo
import com.casualexplorer.chat.core.Provider
import com.casualexplorer.chat.core.Role
import com.casualexplorer.chat.core.StreamError
import com.casualexplorer.chat.core.StreamEvent
import com.casualexplorer.chat.core.Turn
import com.casualexplorer.chat.data.ChatRepository
import com.casualexplorer.chat.data.NetworkMonitor
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.UserSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sets the main dispatcher, which viewModelScope uses, for each test. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Replies with [script]; waits for [gate] first, if set, and hangs at the end if [hang]. */
private class ScriptedProvider(
    var script: List<StreamEvent> = emptyList(),
    var gate: CompletableDeferred<Unit>? = null,
    var hang: Boolean = false,
    override val requestEffort: String = "medium",
) : Provider {
    override val name = "Anthropic"
    override var model = "claude-test"
    override var effort = "medium"

    override fun stream(history: List<Turn>): Flow<StreamEvent> = flow {
        gate?.await()
        script.forEach { emit(it) }
        if (hang) awaitCancellation()
    }

    override suspend fun listModels() = emptyList<ModelInfo>()
}

private class FakeChatRepository(override val session: ChatSession, override val settings: StateFlow<UserSettings?>) : ChatRepository

/** Settings in memory, shared with [FakeChatRepository]. */
private class FakeSettingsRepository(val flow: MutableStateFlow<UserSettings?>) : SettingsRepository {
    override val settings: Flow<UserSettings> = flow.filterNotNull()

    override suspend fun save(settings: UserSettings) {
        flow.value = settings
    }

    override suspend fun markNotificationsAsked() {
        flow.value = flow.value?.copy(notificationsAsked = true)
    }
}

private class FakeNetworkMonitor : NetworkMonitor {
    val online = MutableStateFlow(true)
    override val isOnline: Flow<Boolean> = online
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val withKey = UserSettings(anthropicKey = "k", startProvider = UserSettings.START_ANTHROPIC)

    private val network = FakeNetworkMonitor()
    private var repliesStarted = 0

    private fun CoroutineScope.viewModel(provider: Provider, settings: UserSettings = withKey): ChatViewModel {
        val session = ChatSession(listOf(provider), 0, InMemoryConversationStore(), this, debounceMs = 0)
        val flow = MutableStateFlow<UserSettings?>(settings)
        return ChatViewModel(
            FakeChatRepository(session, flow),
            FakeSettingsRepository(flow),
            network,
            keepAlive = { repliesStarted++ },
            SavedStateHandle(),
        )
    }

    private suspend fun ChatViewModel.awaitReply(count: Int = 2): AssistantMessage = uiState.first { s ->
        !s.chat.streaming && s.chat.messages.size == count && (s.chat.messages.last() as? AssistantMessage)?.pending == false
    }.chat.messages.last() as AssistantMessage

    private fun ChatViewModel.type(text: String) = onDraftChange(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun sendClearsTheDraftAndTheReplyStreamsIn() = runTest(main.dispatcher) {
        val p = ScriptedProvider(listOf(StreamEvent.Delta("Hel"), StreamEvent.Delta("lo"), StreamEvent.Done(Turn(Role.Assistant, "Hello"))))
        val vm = backgroundScope.viewModel(p)
        vm.type("hi")
        vm.send()
        assertEquals("", vm.draft.text)
        assertEquals("Hello", vm.awaitReply().text)
    }

    @Test
    fun aFailedMessageComesBackEvenWithNoScreenWatching() = runTest(main.dispatcher) {
        val p = ScriptedProvider(listOf(StreamEvent.Failed(StreamError("Boom"))))
        val vm = backgroundScope.viewModel(p)
        vm.type("resend me")
        vm.send()
        // Nothing collects uiState here, as when Settings is showing.
        vm.uiState.first { !it.chat.streaming && it.chat.messages.isNotEmpty() }
        assertEquals("resend me", vm.draft.text)
        assertEquals(TextRange(9), vm.draft.selection)
    }

    @Test
    fun aRestoredMessageDoesNotReplaceANewDraft() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider(listOf(StreamEvent.Failed(StreamError("Boom"))), gate = gate)
        val vm = backgroundScope.viewModel(p)
        vm.type("first")
        vm.send()
        vm.type("something new")
        gate.complete(Unit)
        assertEquals("Boom", vm.awaitReply().failure)
        assertEquals("something new", vm.draft.text)
    }

    @Test
    fun aMissingKeyIsExplainedAndNothingIsSent() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider(), settings = UserSettings())
        vm.type("hi")
        vm.send()
        val state = vm.uiState.first { it.userMessage != null }
        assertEquals(SnackbarMessage(R.string.add_key, listOf("Anthropic")), state.userMessage)
        assertFalse(state.hasKey)
        assertEquals("the draft is kept", "hi", vm.draft.text)
        assertTrue(state.chat.messages.isEmpty())
        vm.userMessageShown()
        assertNull(vm.uiState.first { it.userMessage == null }.userMessage)
    }

    @Test
    fun changesWaitForTheReply() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider(hang = true))
        vm.type("hi")
        vm.send()
        vm.uiState.first { it.chat.streaming }
        vm.selectModel(0, "other")
        assertEquals(SnackbarMessage(R.string.wait_switch_model), vm.uiState.first { it.userMessage != null }.userMessage)
        vm.stop()
        assertTrue(vm.awaitReply().canceled)
    }

    @Test
    fun upAndDownStepThroughSentMessages() = runTest(main.dispatcher) {
        val p = ScriptedProvider(listOf(StreamEvent.Done(Turn(Role.Assistant, "ok"))))
        val vm = backgroundScope.viewModel(p)
        vm.type("one")
        vm.send()
        vm.awaitReply(2)
        vm.type("two")
        vm.send()
        vm.awaitReply(4)

        vm.type("draft")
        assertTrue(vm.historyPrevious())
        assertEquals(TextFieldValue("two", TextRange(0)), vm.draft)
        assertTrue(vm.historyPrevious())
        assertEquals("one", vm.draft.text)
        assertFalse("nothing older", vm.historyPrevious())
        assertTrue(vm.historyNext())
        assertEquals("two", vm.draft.text)
        assertTrue(vm.historyEscape())
        assertEquals(TextFieldValue("draft", TextRange(5)), vm.draft)
        assertFalse(vm.historyEscape())
    }

    @Test
    fun retrySendsTheFailedMessageAgainAndTakesItOutOfTheInput() = runTest(main.dispatcher) {
        val p = ScriptedProvider(listOf(StreamEvent.Failed(StreamError("Boom"))))
        val vm = backgroundScope.viewModel(p)
        vm.type("try me")
        vm.send()
        vm.uiState.first { !it.chat.streaming && it.chat.messages.size == 2 }
        assertEquals("the message came back to the input", "try me", vm.draft.text)

        p.script = listOf(StreamEvent.Done(Turn(Role.Assistant, "Done")))
        vm.retry()
        assertEquals("", vm.draft.text)
        assertEquals("Done", vm.awaitReply(4).text)
        val user = vm.uiState.value.chat.messages[2] as com.casualexplorer.chat.core.UserMessage
        assertEquals("try me", user.text)
    }

    @Test
    fun aSentReplyIsKeptAliveInTheBackground() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider(listOf(StreamEvent.Done(Turn(Role.Assistant, "ok")))))
        vm.type("hi")
        vm.send()
        assertEquals(1, repliesStarted)
        vm.awaitReply()
    }

    @Test
    fun offlineNothingIsSentAndTheDraftStays() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider())
        network.online.value = false
        vm.uiState.first { it.offline }
        vm.type("hi")
        vm.send()
        assertEquals(SnackbarMessage(R.string.offline_send), vm.uiState.first { it.userMessage != null }.userMessage)
        assertEquals("hi", vm.draft.text)
        assertEquals(0, repliesStarted)
        network.online.value = true
        assertFalse(vm.uiState.first { !it.offline }.offline)
    }

    @Test
    fun notificationPermissionIsAskedOnce() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider())
        assertTrue(vm.uiState.first { it.askNotifications }.askNotifications)
        vm.notificationsAsked()
        assertFalse(vm.uiState.first { !it.askNotifications }.askNotifications)
    }

    @Test
    fun thinkingExpandsAndCollapses() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider())
        vm.toggleThinking(7)
        assertEquals(setOf(7L), vm.uiState.first { it.expandedThinking.isNotEmpty() }.expandedThinking)
        vm.toggleThinking(7)
        assertEquals(emptySet<Long>(), vm.uiState.first { it.expandedThinking.isEmpty() }.expandedThinking)
    }

    @Test
    fun theEffortSentIsShownWhenTheModelLowersIt() = runTest(main.dispatcher) {
        val vm = backgroundScope.viewModel(ScriptedProvider(requestEffort = "high"))
        val state = vm.uiState.first { it.chat.requestEfforts.isNotEmpty() }
        assertEquals("medium", state.chat.efforts[0])
        assertEquals("high", state.chat.requestEfforts[0])
    }
}
