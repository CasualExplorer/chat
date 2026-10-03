package com.casualexplorer.chat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.core.formatDuration
import com.casualexplorer.chat.core.formatElapsed
import com.casualexplorer.chat.core.formatTokens
import com.casualexplorer.chat.core.markdown.countLines
import com.casualexplorer.chat.core.markdown.tailLines
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A thinking summary longer than this shows only its last lines until it is
 * expanded, as in the terminal app.
 */
private const val MAX_COLLAPSED_THINKING_LINES = 10

private val BubbleRadius = 20.dp
private val TailRadius = 6.dp

/** What a long press on a message offers. */
class MessageActions(
    val onCopy: (String) -> Unit,
    val onShare: (String) -> Unit,
) {
    /** Copy and Share, as TalkBack's actions for a message with [text]. */
    @Composable
    fun accessibilityActions(text: String): List<CustomAccessibilityAction> {
        val copy = stringResource(R.string.copy)
        val share = stringResource(R.string.share)
        return listOf(
            CustomAccessibilityAction(copy) {
                onCopy(text)
                true
            },
            CustomAccessibilityAction(share) {
                onShare(text)
                true
            },
        )
    }
}

/** The local time of [epochMs], e.g. "10:42" or "10:42 AM". */
fun formatTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

/**
 * A message the user sent: a bubble at the end side, with its time under the
 * last of a group.
 */
@Composable
fun UserMessageView(item: ChatItem.Message, message: UserMessage, actions: MessageActions) {
    // One TalkBack item per message, saying who sent it and when, with the
    // long-press actions as accessibility actions.
    val said = stringResource(R.string.message_from_you, message.text)
    val sentAt = if (message.createdAt > 0) stringResource(R.string.message_sent_at, formatTime(message.createdAt)) else ""
    val a11yActions = actions.accessibilityActions(message.text)
    // The start inset keeps a user's bubble from spanning the screen, so
    // the two sides read apart.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp)
            .clearAndSetSemantics {
                contentDescription = if (sentAt.isEmpty()) said else "$said. $sentAt"
                customActions = a11yActions
            },
        horizontalAlignment = Alignment.End,
    ) {
        val shape = RoundedCornerShape(
            topStart = BubbleRadius,
            topEnd = if (item.firstInGroup) BubbleRadius else TailRadius,
            bottomEnd = if (item.lastInGroup) TailRadius else BubbleRadius,
            bottomStart = BubbleRadius,
        )
        MessageMenu(message.text, actions) { modifier ->
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = modifier.widthIn(max = 640.dp),
            ) {
                Markdown(
                    message.text,
                    streaming = false,
                    style = MdStyle(keepNewlines = true),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        if (item.lastInGroup && message.createdAt > 0) {
            Text(
                formatTime(message.createdAt),
                // Laid out by its own script, so "10:41 AM" keeps its order in RTL.
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * A reply: the reasoning summary in a card, the reply in a bubble at the
 * start side, then how it ended (a note, Stopped, or an error) and a footer
 * naming the model. Until its text starts, a typing indicator shows.
 * [onRetry], if set, offers to send the message again.
 */
@Composable
fun AssistantMessageView(
    item: ChatItem.Message,
    message: AssistantMessage,
    expanded: Boolean,
    onToggleThinking: () -> Unit,
    actions: MessageActions,
    onRetry: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.thinking.isNotBlank()) ThinkingCard(message, expanded, onToggleThinking)

        when {
            message.spinning -> TypingIndicator(thinking = message.thinking.isNotEmpty(), startMs = message.startMs)
            message.text.isNotEmpty() -> {
                val shape = RoundedCornerShape(
                    topStart = if (item.firstInGroup) BubbleRadius else TailRadius,
                    topEnd = BubbleRadius,
                    bottomEnd = BubbleRadius,
                    bottomStart = TailRadius,
                )
                MessageMenu(message.text, actions) { modifier ->
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
                        Markdown(
                            message.text,
                            streaming = message.pending,
                            style = MdStyle(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            !message.failed -> Text(
                stringResource(R.string.no_text),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            message.canceled -> StatusRow(stringResource(R.string.stopped), onRetry)
            message.failure.isNotEmpty() -> ErrorRow(message.failure, onRetry)
            message.note.isNotEmpty() -> Text(
                message.note,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!message.pending && !message.failed) ReplyFooter(message)
    }
}

/**
 * Shows a menu of [actions] on a long press of the content, which gets the
 * modifier that makes it pressable. "Select text" opens the text in a dialog
 * where it can be selected.
 */
@Composable
private fun MessageMenu(text: String, actions: MessageActions, content: @Composable (Modifier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.message_actions)
    val a11yActions = actions.accessibilityActions(text)
    Box {
        content(
            Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = { open = true },
                    onLongClickLabel = label,
                )
                .semantics { customActions = a11yActions },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                open = false
                actions.onCopy(text)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                open = false
                actions.onShare(text)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.select_text)) }, onClick = {
                open = false
                selecting = true
            })
        }
    }
    if (selecting) {
        AlertDialog(
            onDismissRequest = { selecting = false },
            confirmButton = { TextButton(onClick = { selecting = false }) { Text(stringResource(R.string.close)) } },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            },
        )
    }
}

/**
 * The reasoning summary in a card, showing its last lines unless expanded,
 * under a header that says how long the model thought. The header expands
 * and collapses a long summary.
 */
@Composable
private fun ThinkingCard(message: AssistantMessage, expanded: Boolean, onToggle: () -> Unit) {
    val thinking = message.thinking.trim()
    val lines = countLines(thinking)
    val collapsible = lines > MAX_COLLAPSED_THINKING_LINES + 1
    val header = if (message.thinkForMs > 0) {
        stringResource(R.string.thought_for, formatDuration(message.thinkForMs))
    } else {
        stringResource(R.string.thinking)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val stateText = stringResource(if (expanded) R.string.thinking_expanded else R.string.thinking_collapsed)
            val toggleLabel = stringResource(if (expanded) R.string.collapse_thinking else R.string.expand_thinking)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(
                        if (collapsible) {
                            Modifier
                                .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                                .semantics { stateDescription = stateText }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(header, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            }
            if (collapsible && !expanded) {
                val (tail, hidden) = tailLines(thinking, MAX_COLLAPSED_THINKING_LINES, lines)
                Text(
                    "… " + pluralStringResource(R.plurals.lines_hidden, hidden, hidden),
                    style = MaterialTheme.typography.labelSmall,
                )
                Markdown(tail.trim(), streaming = false, style = MdStyle(compact = true), modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Markdown(thinking, streaming = message.pending, style = MdStyle(compact = true), modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

/**
 * Three pulsing dots, then "Thinking… 12s" once the model is reasoning, or
 * just the time so far. TalkBack announces it once, not every second.
 */
@Composable
private fun TypingIndicator(thinking: Boolean, startMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val transition = rememberInfiniteTransition(label = "typing")
    val elapsed = formatElapsed(now - startMs)
    val label = if (thinking) stringResource(R.string.thinking_for, elapsed) else elapsed
    val description = stringResource(R.string.thinking)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(shape = RoundedCornerShape(BubbleRadius), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { i ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 200)),
                        label = "dot$i",
                    )
                    Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How a stopped reply ended, with Retry if offered. */
@Composable
private fun StatusRow(text: String, onRetry: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** Why a reply failed, with Retry if offered. The reason can be selected, to copy a request id. */
@Composable
private fun ErrorRow(reason: String, onRetry: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        // TalkBack announces a failure as it happens.
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (onRetry == null) 12.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.error_label), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                SelectionContainer(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (onRetry != null) {
                // In the card's own colour: the primary colour reads poorly on errorContainer.
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** Under a finished reply: "gpt-5.6-luna · 2.3s · 12.4K in · 845 out · 10:42". */
@Composable
private fun ReplyFooter(message: AssistantMessage) {
    val parts = buildList {
        add(message.model)
        add(formatDuration(message.elapsedMs))
        message.usage?.let { add(stringResource(R.string.reply_footer_tokens, formatTokens(it.input), formatTokens(it.output))) }
        if (message.startMs > 0) add(formatTime(message.startMs))
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}
