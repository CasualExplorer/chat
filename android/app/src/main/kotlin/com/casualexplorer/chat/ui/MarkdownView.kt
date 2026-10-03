package com.casualexplorer.chat.ui

// The markdown styles follow Crush's glamour themes
// (github.com/charmbracelet/crush, internal/ui/styles), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.casualexplorer.chat.core.markdown.CodeHighlighter
import com.casualexplorer.chat.core.markdown.MdAlign
import com.casualexplorer.chat.core.markdown.MdBlock
import com.casualexplorer.chat.core.markdown.MdCodeBlock
import com.casualexplorer.chat.core.markdown.MdHeading
import com.casualexplorer.chat.core.markdown.MdHtml
import com.casualexplorer.chat.core.markdown.MdList
import com.casualexplorer.chat.core.markdown.MdParagraph
import com.casualexplorer.chat.core.markdown.MdQuote
import com.casualexplorer.chat.core.markdown.MdRule
import com.casualexplorer.chat.core.markdown.MdTable
import com.casualexplorer.chat.core.markdown.StreamingMarkdown
import com.casualexplorer.chat.core.markdown.TokenKind
import com.casualexplorer.chat.core.markdown.parseInline
import com.casualexplorer.chat.core.markdown.Markdown as MarkdownParser

/** How markdown is drawn: the reply theme, or the muted one of the thinking box. */
@Immutable
data class MdStyle(
    val text: Color,
    val quiet: Boolean,
    /** Typed line breaks are kept, as in user messages. */
    val keepNewlines: Boolean = false,
)

val ReplyStyle = MdStyle(text = Palette.FgSubtle, quiet = false)
val UserStyle = MdStyle(text = Palette.FgSubtle, quiet = false, keepNewlines = true)
val QuietStyle = MdStyle(text = Palette.FgMoreSubtle, quiet = true)

private val bodyText = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)
private val codeText = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)

/**
 * Renders markdown that may still be streaming. While [streaming], blocks
 * come from a [StreamingMarkdown] cache, so only the paragraph still arriving
 * is re-parsed; the cached blocks are the same instances as before, so
 * Compose skips them. A finished message is parsed whole.
 */
@Composable
fun Markdown(text: String, streaming: Boolean, style: MdStyle, modifier: Modifier = Modifier) {
    val cache = remember { StreamingMarkdown() }
    val blocks = remember(text, streaming) {
        if (streaming) {
            cache.render(text)
        } else {
            cache.reset()
            MarkdownParser.parse(text)
        }
    }
    MarkdownBlocks(blocks, style, modifier)
}

@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, style: MdStyle, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { i, block ->
            key(i) { BlockView(block, style) }
        }
    }
}

@Composable
private fun BlockView(block: MdBlock, style: MdStyle) {
    when (block) {
        is MdParagraph -> InlineText(block.text, style)
        is MdHeading -> HeadingView(block, style)
        is MdCodeBlock -> CodeBlockView(block, style)
        is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.FgMostSubtle))
            Spacer(Modifier.width(10.dp))
            MarkdownBlocks(block.blocks, style)
        }
        is MdList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in block.items) {
                Row {
                    val marker = when (item.checked) {
                        true -> "[✓]"
                        false -> "[ ]"
                        null -> item.marker
                    }
                    Text(marker, style = bodyText, color = if (style.quiet) style.text else Palette.FgMoreSubtle)
                    Spacer(Modifier.width(8.dp))
                    MarkdownBlocks(item.blocks, style)
                }
            }
        }
        is MdTable -> TableView(block, style)
        MdRule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.Separator))
        is MdHtml -> Text(block.text, style = codeText, color = Palette.FgMoreSubtle)
    }
}

@Composable
private fun InlineText(text: String, style: MdStyle, color: Color = style.text, bold: Boolean = false) {
    val annotated = remember(text, style, color, bold) { inlineAnnotated(text, style, color, bold) }
    Text(annotated, style = bodyText)
}

@Composable
private fun HeadingView(block: MdHeading, style: MdStyle) {
    if (style.quiet) {
        InlineText(block.text, style, bold = true)
        return
    }
    when (block.level) {
        1 -> {
            val annotated = remember(block) {
                inlineAnnotated(block.text, style, Palette.WarningSubtle, bold = true)
            }
            Text(annotated, style = bodyText, modifier = Modifier.background(Palette.Primary).padding(horizontal = 6.dp))
        }
        6 -> InlineText("###### " + block.text, style, Palette.SuccessMostSubtle)
        else -> InlineText("#".repeat(block.level) + " " + block.text, style, Palette.Info, bold = true)
    }
}

@Composable
private fun CodeBlockView(block: MdCodeBlock, style: MdStyle) {
    val annotated = remember(block, style) { highlight(block, style) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (style.quiet) Palette.BgLessVisible.copy(alpha = 0.5f) else Palette.BgLessVisible)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(annotated, style = codeText, softWrap = false)
    }
}

@Composable
private fun TableView(block: MdTable, style: MdStyle) {
    // Cells don't wrap, so each column lines up as one Column; wide tables scroll.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (col in block.header.indices) {
            val align = when (block.aligns[col]) {
                MdAlign.Start -> androidx.compose.ui.Alignment.Start
                MdAlign.Center -> androidx.compose.ui.Alignment.CenterHorizontally
                MdAlign.End -> androidx.compose.ui.Alignment.End
            }
            Column(horizontalAlignment = align) {
                InlineText(block.header[col], style, if (style.quiet) style.text else Palette.FgBase, bold = true)
                Box(Modifier.padding(vertical = 2.dp).width(24.dp).height(1.dp).background(Palette.Separator))
                for (row in block.rows) InlineText(row[col], style)
            }
        }
    }
}

private fun safeUrl(url: String) =
    url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

/** Builds the styled text of inline markdown. */
fun inlineAnnotated(text: String, style: MdStyle, color: Color, bold: Boolean = false): AnnotatedString =
    buildAnnotatedString {
        for (span in parseInline(text, style.keepNewlines)) {
            val link = span.url != null
            val spanStyle = SpanStyle(
                color = when {
                    style.quiet -> style.text
                    span.code -> Palette.Destructive
                    span.image -> Palette.FgMoreSubtle
                    link -> Palette.SuccessMostSubtle
                    else -> color
                },
                background = if (span.code) Palette.BgLessVisible else Color.Unspecified,
                fontWeight = if (bold || span.bold || (link && !span.image)) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.strike && link -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                    span.strike -> TextDecoration.LineThrough
                    link -> TextDecoration.Underline
                    else -> null
                },
            )
            val shown = if (span.code) " ${span.text} " else span.text
            val url = span.url
            if (url != null && safeUrl(url)) {
                withLink(LinkAnnotation.Url(url, TextLinkStyles(spanStyle))) { append(shown) }
            } else {
                withStyle(spanStyle) { append(shown) }
            }
        }
    }

private fun tokenColor(kind: TokenKind): Color = when (kind) {
    TokenKind.Plain -> Palette.FgSubtle
    TokenKind.Keyword -> Palette.Info
    TokenKind.Type -> Palette.Guppy
    TokenKind.Function -> Palette.SuccessMostSubtle
    TokenKind.String -> Palette.Cumin
    TokenKind.Number -> Palette.Success
    TokenKind.Comment -> Palette.FgMostSubtle
    TokenKind.Operator -> Palette.Salmon
    TokenKind.Punctuation -> Palette.WarningSubtle
}

/** A code block in the palette's syntax colours; the thinking box keeps it muted. */
private fun highlight(block: MdCodeBlock, style: MdStyle): AnnotatedString = buildAnnotatedString {
    if (style.quiet) {
        withStyle(SpanStyle(color = style.text)) { append(block.code) }
        return@buildAnnotatedString
    }
    for (token in CodeHighlighter.tokenize(block.code, block.language)) {
        withStyle(SpanStyle(color = tokenColor(token.kind))) { append(token.text) }
    }
}
