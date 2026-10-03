package com.casualexplorer.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casualexplorer.chat.ChatViewModel
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.CONTEXT_WARN_PERCENT
import com.casualexplorer.chat.core.ChatState
import com.casualexplorer.chat.core.EFFORTS
import com.casualexplorer.chat.core.ModelChoice
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.core.formatEffort
import com.casualexplorer.chat.core.formatTokens
import com.casualexplorer.chat.core.modelChoices

private val body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)
private val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)

/**
 * The conversation: the model bar on top, the messages, and the input.
 * [input] is hoisted so a draft survives a visit to Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showEffort by rememberSaveable { mutableStateOf(false) }
    // Which replies have their thinking expanded.
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val currentInput by rememberUpdatedState(input)

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) {
        // A message whose reply failed before any text goes back in an empty input.
        vm.restoredInput.collect { text ->
            if (currentInput.text.isBlank()) onInputChange(TextFieldValue(text, TextRange(text.length)))
        }
    }

    Scaffold(
        containerColor = Palette.BgBase,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ModelBar(
                state,
                onModel = { showModels = true },
                onEffort = { showEffort = true },
                onNewChat = {
                    vm.newChat()
                    expanded.clear()
                },
                onSettings = onOpenSettings,
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    EmptyState(state, hasKey = vm.hasKey(state.active), onOpenSettings)
                } else {
                    MessageList(
                        state,
                        expanded = { expanded[it] == true },
                        onToggle = { expanded[it] = expanded[it] != true },
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                    )
                }
            }
            HorizontalDivider(color = Palette.Separator)
            InputBar(
                vm,
                state,
                input,
                onInputChange,
                onSend = { if (vm.submit(input.text)) onInputChange(TextFieldValue()) },
            )
        }
    }

    if (showModels) {
        ModelSheet(
            state,
            onSelect = {
                vm.selectModel(it.provider, it.model)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }
    if (showEffort) {
        EffortSheet(
            state,
            onSelect = {
                vm.selectEffort(it)
                showEffort = false
            },
            onDismiss = { showEffort = false },
        )
    }
}

/**
 * "◇ model via Provider" over the reasoning effort and context use. Tapping
 * the model opens the model picker; tapping the effort opens its selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelBar(
    state: ChatState,
    onModel: () -> Unit,
    onEffort: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val active = state.active
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.BgBase),
            title = {
                Column {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append("$MODEL_ICON ") }
                            withStyle(SpanStyle(color = Palette.FgBase)) { append(state.models.getOrElse(active) { "" }) }
                            withStyle(SpanStyle(color = Palette.FgMoreSubtle)) { append(" via ${state.providerNames.getOrElse(active) { "" }}") }
                        },
                        style = body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = "Switch model") { onModel() },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reasoning " + formatEffort(state.requestEfforts.getOrElse(active) { "" }),
                            style = small,
                            color = Palette.FgMostSubtle,
                            modifier = Modifier.clickable(onClickLabel = "Change reasoning effort") { onEffort() },
                        )
                        contextUsage(state)?.let {
                            Text("  ·  ", style = small, color = Palette.FgMostSubtle)
                            Text(it, style = small)
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Palette.FgMoreSubtle)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.FgMoreSubtle)
                }
            },
        )
        HorizontalDivider(color = Palette.Separator)
    }
}

/**
 * How much of the context window the conversation fills, as Crush shows it:
 * "12% (24.5K)", with a warning sign past 80%. Without a known window
 * (OpenAI doesn't report one) it is just the token count.
 */
private fun contextUsage(state: ChatState): AnnotatedString? {
    val usage = state.usage ?: return null
    val tokens = formatTokens(usage.context)
    val pct = state.contextPercent
    return buildAnnotatedString {
        if (pct < 0) {
            withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append("$tokens tokens") }
            return@buildAnnotatedString
        }
        if (pct > CONTEXT_WARN_PERCENT) withStyle(SpanStyle(color = Palette.Warning)) { append("⚠ ") }
        withStyle(SpanStyle(color = Palette.FgMoreSubtle)) { append("$pct%") }
        withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append(" ($tokens)") }
    }
}

@Composable
private fun EmptyState(state: ChatState, hasKey: Boolean, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "chat",
            style = TextStyle(
                fontFamily = Mono,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                brush = Brush.linearGradient(listOf(Palette.Primary, Palette.Secondary)),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "$MODEL_ICON ${state.models.getOrElse(state.active) { "" }} via ${state.providerNames.getOrElse(state.active) { "" }}",
            style = small,
            color = Palette.FgMoreSubtle,
        )
        Spacer(Modifier.height(24.dp))
        if (hasKey) {
            Text("Type a message below to start.", style = body, color = Palette.FgMostSubtle)
        } else {
            Text(
                "Add your ${state.providerNames.getOrElse(state.active) { "" }} API key to start.",
                style = body,
                color = Palette.FgMostSubtle,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings) { Text("Open Settings", style = body, color = Palette.FgBase) }
        }
    }
}

@Composable
private fun MessageList(
    state: ChatState,
    expanded: (Long) -> Boolean,
    onToggle: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    // Newest first in a reversed list: the list stays anchored to the bottom
    // while a reply grows, unless the reader has scrolled up.
    val messages = state.messages.asReversed()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is UserMessage -> UserMessageView(message)
                is AssistantMessage -> AssistantMessageView(
                    message,
                    expanded = expanded(message.id),
                    onToggleThinking = { onToggle(message.id) },
                    onCopy = { onCopy(message.text) },
                )
            }
        }
    }
}

/**
 * The input, behind Crush's ":::" prompt, with Send (or Stop while a reply
 * streams). With a hardware keyboard, Enter sends (Shift+Enter is a new
 * line), Up and Down at the ends of the text step through the messages sent
 * this session, and Esc goes back to the draft.
 */
@Composable
private fun InputBar(
    vm: ChatViewModel,
    state: ChatState,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val hardware = event.nativeKeyEvent.device?.isVirtual == false
        val atStart = input.selection.collapsed && input.selection.start == 0
        val atEnd = input.selection.collapsed && input.selection.end == input.text.length
        return when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                if (!hardware || event.isShiftPressed || event.isAltPressed || event.isCtrlPressed) return false
                if (!state.streaming) onSend()
                true
            }
            Key.DirectionUp -> {
                if (!atStart && '\n' in input.text) return false
                val text = vm.prompts.previous(input.text) ?: return false
                onInputChange(TextFieldValue(text, TextRange(0)))
                true
            }
            Key.DirectionDown -> {
                if (!atEnd && '\n' in input.text) return false
                val text = vm.prompts.next() ?: return false
                onInputChange(TextFieldValue(text, TextRange(text.length)))
                true
            }
            Key.Escape -> {
                val text = vm.prompts.escape() ?: return false
                onInputChange(TextFieldValue(text, TextRange(text.length)))
                true
            }
            else -> false
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.BgBase)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (focused) " > " else ":::",
            style = body.copy(fontWeight = FontWeight.Bold),
            color = if (focused) Palette.Success else Palette.FgMoreSubtle,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = input,
            onValueChange = {
                // Editing leaves the prompt history.
                if (it.text != input.text) vm.prompts.edited(it.text)
                onInputChange(it)
            },
            textStyle = body.copy(color = Palette.FgBase),
            cursorBrush = SolidColor(Palette.Secondary),
            maxLines = 8,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent(::onKey),
            decorationBox = { inner ->
                Box {
                    if (input.text.isEmpty()) {
                        Text("Ready…", style = body, color = Palette.FgMostSubtle)
                    }
                    inner()
                }
            },
        )
        if (state.streaming) {
            IconButton(onClick = { vm.cancel() }) {
                Icon(Icons.Filled.Close, contentDescription = "Stop the reply", tint = Palette.Destructive)
            }
        } else {
            IconButton(onClick = onSend, enabled = input.text.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (input.text.isNotBlank()) Palette.Primary else Palette.FgMostSubtle,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Switches model or provider; the conversation carries over. It lists each
 * provider's current model, then the models it serves, and a filter that
 * matches nothing can be used as a model ID as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(state: ChatState, onSelect: (ModelChoice) -> Unit, onDismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.BgLeastVisible) {
        Text("Switch Model", style = body, color = Palette.Primary, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            textStyle = body,
            placeholder = { Text("Filter or type a model ID", style = body, color = Palette.FgMostSubtle) },
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val query = filter.trim()
        val choices = state.modelChoices().filter { query.isEmpty() || it.model.contains(query, ignoreCase = true) }
        val custom = if (query.isNotEmpty() && choices.none { it.model == query }) {
            state.providerNames.indices.map { ModelChoice(it, query, false) }
        } else {
            emptyList()
        }
        LazyColumn(Modifier.heightIn(max = 480.dp).padding(top = 8.dp, bottom = 24.dp)) {
            items(choices + custom) { choice ->
                val provider = state.providerNames[choice.provider]
                val info = when {
                    choice.current -> "current · $provider"
                    choice in custom -> "use with $provider"
                    else -> provider
                }
                SheetRow(choice.model, info, selected = choice.current) { onSelect(choice) }
            }
        }
    }
}

/** Sets the active provider's reasoning effort; each provider keeps its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortSheet(state: ChatState, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val active = state.active
    val chosen = state.efforts.getOrElse(active) { "" }
    val sent = state.requestEfforts.getOrElse(active) { chosen }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.BgLeastVisible) {
        Text(
            "Reasoning Effort · ${state.providerNames.getOrElse(active) { "" }}",
            style = body,
            color = Palette.Primary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (sent != chosen) {
            Text(
                "${state.models[active]} doesn't support ${formatEffort(chosen)}; requests use ${formatEffort(sent)}.",
                style = small,
                color = Palette.FgMoreSubtle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
            for (effort in EFFORTS) {
                SheetRow(formatEffort(effort), if (effort == chosen) "current" else "", selected = effort == chosen) { onSelect(effort) }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, info: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Palette.Primary else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = body,
            color = if (selected) Palette.OnPrimary else Palette.FgBase,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (info.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(info, style = small, color = if (selected) Palette.OnPrimary else Palette.FgMostSubtle)
        }
    }
}

@Composable
fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Primary,
    unfocusedBorderColor = Palette.BgMostVisible,
    focusedTextColor = Palette.FgBase,
    unfocusedTextColor = Palette.FgBase,
    cursorColor = Palette.Secondary,
)
