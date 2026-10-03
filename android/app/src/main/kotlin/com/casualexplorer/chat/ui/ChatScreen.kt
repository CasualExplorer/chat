package com.casualexplorer.chat.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.CONTEXT_WARN_PERCENT
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.EFFORTS
import com.casualexplorer.chat.core.ModelChoice
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.core.formatEffort
import com.casualexplorer.chat.core.formatTokens
import com.casualexplorer.chat.core.modelChoices
import com.casualexplorer.chat.notifications.canNotify
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** The chat screen, connected to [vm]. */
@Composable
fun ChatRoute(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Asked once, on the first send: that is when it becomes clear why a
    // chat app would notify (a reply finishing in the background).
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    ChatScreen(
        uiState = uiState,
        input = vm.draft,
        onInputChange = vm::onDraftChange,
        onSend = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && uiState.askNotifications && !canNotify(context)) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.notificationsAsked()
            }
            vm.send()
        },
        onStop = vm::stop,
        onRetry = vm::retry,
        onHistoryPrevious = vm::historyPrevious,
        onHistoryNext = vm::historyNext,
        onHistoryEscape = vm::historyEscape,
        onSelectModel = vm::selectModel,
        onSelectEffort = vm::selectEffort,
        onNewChat = vm::newChat,
        onOpenConversation = vm::openConversation,
        onDeleteConversation = vm::deleteConversation,
        onToggleThinking = vm::toggleThinking,
        onUserMessageShown = vm::userMessageShown,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * The conversation: the model in the top bar, the messages, and the input.
 * The history callbacks return whether there was a message to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onHistoryPrevious: () -> Boolean,
    onHistoryNext: () -> Boolean,
    onHistoryEscape: () -> Boolean,
    onSelectModel: (provider: Int, model: String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onNewChat: () -> Unit,
    onToggleThinking: (replyId: Long) -> Unit,
    onUserMessageShown: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConversation: (Long) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
) {
    val state = uiState.chat
    val snackbar = remember { SnackbarHostState() }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val actions = remember(clipboard, context, scope, copied) {
        MessageActions(
            onCopy = { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                    // Android 13 and later confirm a copy themselves.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar(copied)
                }
            },
            onShare = { text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }

    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            onUserMessageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                ConversationList(
                    state,
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawer.close() }
                    },
                    onOpen = {
                        onOpenConversation(it)
                        scope.launch { drawer.close() }
                    },
                    onDelete = onDeleteConversation,
                )
            }
        },
        modifier = modifier,
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { ModelTitle(state, onClick = { showModels = true }) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_chats))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Filled.Create, contentDescription = stringResource(R.string.new_chat))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                if (uiState.offline) OfflineBanner()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty()) {
                        EmptyState(state, hasKey = uiState.hasKey, onOpenSettings)
                    } else {
                        MessageList(
                            state,
                            expanded = uiState.expandedThinking,
                            onToggleThinking = onToggleThinking,
                            actions = actions,
                            onRetry = onRetry,
                        )
                    }
                }
                InputBar(
                    streaming = state.streaming,
                    offline = uiState.offline,
                    input = input,
                    onInputChange = onInputChange,
                    onSend = onSend,
                    onStop = onStop,
                    onHistoryPrevious = onHistoryPrevious,
                    onHistoryNext = onHistoryNext,
                    onHistoryEscape = onHistoryEscape,
                )
            }
        }
    }

    if (showModels) {
        ModelSheet(
            state,
            onSelectModel = {
                onSelectModel(it.provider, it.model)
                showModels = false
            },
            onSelectEffort = onSelectEffort,
            onDismiss = { showModels = false },
        )
    }
}

/**
 * The saved chats, the most recent first, under New chat. Each can be
 * deleted, after a confirmation.
 */
@Composable
private fun ConversationList(
    state: ChatState,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.chats),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp).semantics { heading() },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.new_chat)) },
            icon = { Icon(Icons.Filled.Create, contentDescription = null) },
            selected = state.conversationId == null,
            onClick = onNewChat,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (state.conversations.isEmpty()) {
            Text(
                stringResource(R.string.no_chats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn {
            items(state.conversations, key = { it.id }) { conversation ->
                NavigationDrawerItem(
                    label = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = conversation.id == state.conversationId,
                    onClick = { onOpen(conversation.id) },
                    badge = {
                        IconButton(onClick = { deleting = conversation.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_chat))
                        }
                    },
                )
            }
        }
    }
    val target = state.conversations.firstOrNull { it.id == deleting }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_text, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The model, with the provider, the reasoning effort sent and how full the
 * context window is under it. Tapping it opens the model sheet.
 */
@Composable
private fun ModelTitle(state: ChatState, onClick: () -> Unit) {
    val active = state.active
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.switch_model), role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.models.getOrElse(active) { "" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        Row {
            Text(
                stringResource(
                    R.string.model_subtitle,
                    state.providerNames.getOrElse(active) { "" },
                    formatEffort(state.requestEfforts.getOrElse(active) { "" }),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            contextUsage(state)?.let { (text, warn) ->
                Text(
                    " · $text",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * How much of the context window the last reply filled, "12% of context",
 * and whether that is past the warning level. Without a known window
 * (OpenAI doesn't report one) it is just the token count.
 */
@Composable
private fun contextUsage(state: ChatState): Pair<String, Boolean>? {
    val usage = state.usage ?: return null
    val pct = state.contextPercent
    if (pct < 0) return stringResource(R.string.context_tokens, formatTokens(usage.context)) to false
    return stringResource(R.string.context_percent, pct) to (pct > CONTEXT_WARN_PERCENT)
}

@Composable
private fun EmptyState(state: ChatState, hasKey: Boolean, onOpenSettings: () -> Unit) {
    val provider = state.providerNames.getOrElse(state.active) { "" }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_model, state.models.getOrElse(state.active) { "" }, provider),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!hasKey) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.empty_no_key, provider),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        }
    }
}

/**
 * The messages, newest at the bottom: a reversed list stays anchored to the
 * newest while a reply grows, unless the reader has scrolled up, when a
 * button jumps back down.
 */
@Composable
private fun MessageList(
    state: ChatState,
    expanded: Set<Long>,
    onToggleThinking: (Long) -> Unit,
    actions: MessageActions,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val items = remember(state.messages, zone) { chatItems(state.messages, zone).asReversed() }
    // Only the last reply can be retried, once it has ended without completing.
    val retryable = (state.messages.lastOrNull() as? AssistantMessage)?.takeIf { it.failed && !state.streaming }?.id
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items,
                key = { it.key },
                contentType = {
                    when (it) {
                        is ChatItem.DateHeader -> 0
                        is ChatItem.Message -> if (it.message is UserMessage) 1 else 2
                    }
                },
            ) { item ->
                when (item) {
                    is ChatItem.DateHeader -> DateHeader(item.day, zone)
                    is ChatItem.Message -> Box(Modifier.padding(top = if (item.firstInGroup) 16.dp else 4.dp)) {
                        when (val message = item.message) {
                            is UserMessage -> UserMessageView(item, message, actions)
                            is AssistantMessage -> AssistantMessageView(
                                item,
                                message,
                                expanded = message.id in expanded,
                                onToggleThinking = { onToggleThinking(message.id) },
                                actions = actions,
                                onRetry = if (message.id == retryable) onRetry else null,
                            )
                        }
                    }
                }
            }
        }
        if (showJump) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.jump_to_newest))
            }
        }
    }
}

/** Under the top bar while the device is offline. TalkBack announces it when it appears. */
@Composable
private fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.offline),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** "Today", "Yesterday", or the date. */
@Composable
private fun DateHeader(day: LocalDate, zone: ZoneId) {
    val today = LocalDate.now(zone)
    val label = when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * The input, which grows to a few lines, with Send (or Stop while a reply
 * streams). With a hardware keyboard, Enter sends (Shift+Enter is a new
 * line), Up and Down at the ends of the text step through the messages sent
 * this session, and Esc goes back to the draft.
 */
@Composable
private fun InputBar(
    streaming: Boolean,
    offline: Boolean,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onHistoryPrevious: () -> Boolean,
    onHistoryNext: () -> Boolean,
    onHistoryEscape: () -> Boolean,
) {
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val hardware = event.nativeKeyEvent.device?.isVirtual == false
        val atStart = input.selection.collapsed && input.selection.start == 0
        val atEnd = input.selection.collapsed && input.selection.end == input.text.length
        return when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                if (!hardware || event.isShiftPressed || event.isAltPressed || event.isCtrlPressed) return false
                if (!streaming && !offline) onSend()
                true
            }
            Key.DirectionUp -> (atStart || '\n' !in input.text) && onHistoryPrevious()
            Key.DirectionDown -> (atEnd || '\n' !in input.text) && onHistoryNext()
            Key.Escape -> onHistoryEscape()
            else -> false
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.message_placeholder)) },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).onPreviewKeyEvent(::onKey),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                if (streaming) {
                    val stop = stringResource(R.string.stop)
                    FilledTonalIconButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = stop }) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)).background(LocalContentColor.current))
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = input.text.isNotBlank() && !offline) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

/**
 * The model and its reasoning effort. The effort chips set the active
 * provider's effort (each provider keeps its own); the list switches model
 * or provider, and the conversation carries over. A filter that matches no
 * model can be used as a model ID as it is.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(
    state: ChatState,
    onSelectModel: (ModelChoice) -> Unit,
    onSelectEffort: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.active
    val provider = state.providerNames.getOrElse(active) { "" }
    val chosen = state.efforts.getOrElse(active) { "" }
    val sent = state.requestEfforts.getOrElse(active) { chosen }
    var filter by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.model_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.reasoning_effort, provider), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (effort in EFFORTS) {
                    FilterChip(
                        selected = effort == chosen,
                        onClick = { onSelectEffort(effort) },
                        label = { Text(formatEffort(effort)) },
                    )
                }
            }
            if (sent != chosen) {
                Text(
                    stringResource(R.string.effort_lowered, state.models.getOrElse(active) { "" }, formatEffort(chosen), formatEffort(sent)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.filter_models)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val query = filter.trim()
        val choices = state.modelChoices().filter { query.isEmpty() || it.model.contains(query, ignoreCase = true) }
        val custom = if (query.isNotEmpty() && choices.none { it.model == query }) {
            state.providerNames.indices.map { ModelChoice(it, query, false) }
        } else {
            emptyList()
        }
        LazyColumn(Modifier.heightIn(max = 480.dp).padding(top = 8.dp, bottom = 16.dp)) {
            items(choices + custom, key = { "${it.provider}/${it.model}/${it in custom}" }) { choice ->
                val name = state.providerNames[choice.provider]
                ListItem(
                    headlineContent = { Text(choice.model) },
                    supportingContent = {
                        Text(
                            when {
                                choice.current -> stringResource(R.string.current_model, name)
                                choice in custom -> stringResource(R.string.use_with, name)
                                else -> name
                            },
                        )
                    },
                    trailingContent = { RadioButton(selected = choice.current, onClick = null) },
                    modifier = Modifier.selectable(selected = choice.current, role = Role.RadioButton) { onSelectModel(choice) },
                )
            }
        }
    }
}
