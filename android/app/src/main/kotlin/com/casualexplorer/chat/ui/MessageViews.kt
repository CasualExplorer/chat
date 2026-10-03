package com.casualexplorer.chat.ui

// The message items and spinner follow Crush's (github.com/charmbracelet/crush,
// internal/ui/chat and internal/ui/anim), Copyright 2025-2026 Charmbracelet,
// Inc., used under FSL-1.1-MIT.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.UserMessage
import com.casualexplorer.chat.core.formatDuration
import com.casualexplorer.chat.core.formatElapsed
import com.casualexplorer.chat.core.formatTokens
import com.casualexplorer.chat.core.markdown.countLines
import com.casualexplorer.chat.core.markdown.tailLines
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A thinking summary longer than this shows only its last lines until it is
 * expanded.
 */
private const val MAX_COLLAPSED_THINKING_LINES = 10

private val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
private val body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)
private val gutter = 16.dp

/** A message the user sent, behind a coloured left border. */
@Composable
fun UserMessageView(message: UserMessage) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = gutter),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Primary))
        Spacer(Modifier.width(10.dp))
        SelectionContainer {
            Markdown(message.text, streaming = false, style = UserStyle)
        }
    }
}

/**
 * A reply: the reasoning summary in a muted box, the reply, any note, and a
 * footer naming the model. Until the reply's text starts it shows the
 * spinner.
 */
@Composable
fun AssistantMessageView(
    message: AssistantMessage,
    expanded: Boolean,
    onToggleThinking: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = gutter + 12.dp, end = gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (message.thinking.isNotBlank()) ThinkingBox(message, expanded, onToggleThinking)

        when {
            message.spinning -> Spinner(if (message.thinking.isNotEmpty()) "Thinking" else "", message.startMs)
            message.text.isNotEmpty() -> SelectionContainer {
                Markdown(message.text, streaming = message.pending, style = ReplyStyle)
            }
            !message.failed -> Text("(no text in this reply)", style = body, color = Palette.FgSubtle, fontStyle = FontStyle.Italic)
        }

        when {
            message.canceled -> Text("Canceled", style = body, color = Palette.FgSubtle, fontStyle = FontStyle.Italic)
            message.failure.isNotEmpty() -> ErrorBanner(message.failure)
            message.note.isNotEmpty() -> Text(message.note, style = body, color = Palette.FgSubtle, fontStyle = FontStyle.Italic)
        }
        if (!message.pending && !message.failed) InfoLine(message, onCopy)
    }
}

/**
 * The reasoning summary as a muted box showing its last lines unless
 * expanded, with a "Thought for" footer once the reply starts. Tapping a long
 * box expands or collapses it.
 */
@Composable
private fun ThinkingBox(message: AssistantMessage, expanded: Boolean, onToggle: () -> Unit) {
    val thinking = message.thinking.trim()
    val lines = countLines(thinking)
    val collapsible = lines > MAX_COLLAPSED_THINKING_LINES + 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.BgLeastVisible)
                .clickable(enabled = collapsible, onClickLabel = if (expanded) "Collapse" else "Expand") { onToggle() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsible && !expanded) {
                val (tail, hidden) = tailLines(thinking, MAX_COLLAPSED_THINKING_LINES, lines)
                Text("… ($hidden lines hidden) [tap to expand]", style = small, color = Palette.FgMoreSubtle)
                Markdown(tail.trim(), streaming = false, style = QuietStyle)
            } else {
                Markdown(thinking, streaming = message.pending, style = QuietStyle)
                if (collapsible) Text("[tap to collapse]", style = small, color = Palette.FgMoreSubtle)
            }
        }
        if (message.thinkForMs > 0) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Palette.FgMoreSubtle)) { append("Thought for ") }
                    withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append(formatDuration(message.thinkForMs)) }
                },
                style = small,
            )
        }
    }
}

/** A failed reply's "ERROR why it failed", with the reason wrapped beside the tag. */
@Composable
private fun ErrorBanner(reason: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "ERROR",
            style = body,
            color = Palette.OnPrimary,
            modifier = Modifier.background(Palette.Destructive).padding(horizontal = 6.dp),
        )
        Spacer(Modifier.width(8.dp))
        SelectionContainer { Text(reason, style = body, color = Palette.FgSubtle) }
    }
}

/** The footer under a finished reply: "◇ model via Provider in 2.3s · 12.4K in · 845 out ───". */
@Composable
private fun InfoLine(message: AssistantMessage, onCopy: () -> Unit) {
    var info = "via ${message.provider} in ${formatDuration(message.elapsedMs)}"
    message.usage?.let { info += " · ${formatTokens(it.input)} in · ${formatTokens(it.output)} out" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append("$MODEL_ICON ") }
                withStyle(SpanStyle(color = Palette.FgMoreSubtle)) { append(message.model) }
                withStyle(SpanStyle(color = Palette.FgMostSubtle)) { append(" $info") }
            },
            style = small,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.Separator))
        Text(
            "copy",
            style = small,
            color = Palette.FgMoreSubtle,
            modifier = Modifier.clickable(onClickLabel = "Copy the reply's markdown") { onCopy() }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

private const val SPINNER_SIZE = 15 // scrambled characters before the label
private const val SPINNER_FRAMES = SPINNER_SIZE * 2 // the colour cycle loops after this many frames
private const val SPINNER_BIRTH = 20 // frames before every character has appeared
private const val SPINNER_INTERVAL_MS = 50L
private const val SPINNER_RUNES = "0123456789abcdefABCDEF~!@#$£€%^&*()+=_"

/**
 * Crush's "working" animation: a row of scrambled characters in a moving
 * purple-pink gradient, which fade in from dots over the first second, then
 * the label and how long the request has been running.
 */
@Composable
fun Spinner(label: String, startMs: Long) {
    val ramp = remember { gradientRamp(SPINNER_SIZE * 3, Palette.Primary, Palette.Secondary, Palette.Primary, Palette.Secondary) }
    val frames = remember { Array(SPINNER_FRAMES) { CharArray(SPINNER_SIZE) { SPINNER_RUNES[Random.nextInt(SPINNER_RUNES.length)] } } }
    val birth = remember { IntArray(SPINNER_SIZE) { Random.nextInt(SPINNER_BIRTH) } }
    var age by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_INTERVAL_MS)
            age++
            now = System.currentTimeMillis()
        }
    }
    val step = age % SPINNER_FRAMES
    Text(
        buildAnnotatedString {
            for (j in 0 until SPINNER_SIZE) {
                withStyle(SpanStyle(color = ramp[step + j], fontWeight = FontWeight.Bold)) {
                    append(if (age < birth[j]) '.' else frames[step][j])
                }
            }
            withStyle(SpanStyle(color = Palette.FgMostSubtle)) {
                if (label.isNotEmpty()) append(" $label")
                append(" " + formatElapsed(now - startMs))
            }
        },
        style = body,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/** Blends [size] colours through the given stops. */
private fun gradientRamp(size: Int, vararg stops: Color): List<Color> {
    val segments = stops.size - 1
    val ramp = ArrayList<Color>(size)
    for (i in 0 until segments) {
        val n = size / segments + if (i < size % segments) 1 else 0
        for (j in 0 until n) ramp += lerp(stops[i], stops[i + 1], j.toFloat() / n)
    }
    return ramp
}
