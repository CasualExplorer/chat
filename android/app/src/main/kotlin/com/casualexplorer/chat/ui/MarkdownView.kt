package com.casualexplorer.chat.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** How markdown is drawn. Text takes the content colour of where it is drawn. */
@Immutable
data class MdStyle(
    /** Smaller text, for the thinking summary. */
    val compact: Boolean = false,
    /** Typed line breaks are kept, as in user messages. */
    val keepNewlines: Boolean = false,
)

/** The colours inline markdown is drawn in, resolved from the theme. */
@Immutable
data class MdColors(
    val text: Color,
    val link: Color,
    val muted: Color,
    val codeBackground: Color,
)

@Composable
private fun mdColors(): MdColors {
    val scheme = MaterialTheme.colorScheme
    return MdColors(
        text = LocalContentColor.current,
        link = scheme.primary,
        muted = scheme.onSurfaceVariant,
        codeBackground = scheme.surfaceContainerHighest,
    )
}

@Composable
private fun bodyStyle(style: MdStyle): TextStyle =
    if (style.compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (style.compact) 6.dp else 10.dp)) {
        blocks.forEachIndexed { i, block ->
            key(i) { BlockView(block, style) }
        }
    }
}

@Composable
private fun BlockView(block: MdBlock, style: MdStyle) {
    val colors = mdColors()
    when (block) {
        is MdParagraph -> InlineText(block.text, style)
        is MdHeading -> HeadingView(block, style)
        is MdCodeBlock -> CodeBlockView(block)
        is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(12.dp))
            MarkdownBlocks(block.blocks, style)
        }
        is MdList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in block.items) {
                Row {
                    val marker = when (item.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> if (item.marker.firstOrNull()?.isDigit() == true) item.marker else "•"
                    }
                    Text(marker, style = bodyStyle(style), color = colors.muted)
                    Spacer(Modifier.width(8.dp))
                    MarkdownBlocks(item.blocks, style)
                }
            }
        }
        is MdTable -> TableView(block, style)
        MdRule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is MdHtml -> Text(block.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont), color = colors.muted)
    }
}

@Composable
private fun InlineText(text: String, style: MdStyle, textStyle: TextStyle = bodyStyle(style), modifier: Modifier = Modifier) {
    val colors = mdColors()
    val annotated = remember(text, style, colors) { inlineAnnotated(text, style.keepNewlines, colors) }
    Text(annotated, style = textStyle, modifier = modifier)
}

@Composable
private fun HeadingView(block: MdHeading, style: MdStyle) {
    val typography = MaterialTheme.typography
    val textStyle = when {
        style.compact -> typography.titleSmall
        block.level == 1 -> typography.titleLarge
        block.level == 2 -> typography.titleMedium
        else -> typography.titleSmall
    }
    InlineText(block.text, style, textStyle, Modifier.semantics { heading() })
}

@Composable
private fun CodeBlockView(block: MdCodeBlock) {
    val codeColors = LocalCodeColors.current
    val plain = LocalContentColor.current
    val annotated = remember(block, codeColors, plain) { highlight(block, codeColors, plain) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(annotated, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont), softWrap = false)
    }
}

@Composable
private fun TableView(block: MdTable, style: MdStyle) {
    // Cells don't wrap, so each column lines up as one Column; wide tables scroll.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (col in block.header.indices) {
            val align = when (block.aligns[col]) {
                MdAlign.Start -> Alignment.Start
                MdAlign.Center -> Alignment.CenterHorizontally
                MdAlign.End -> Alignment.End
            }
            Column(horizontalAlignment = align) {
                InlineText(block.header[col], style, bodyStyle(style).copy(fontWeight = FontWeight.SemiBold))
                HorizontalDivider(Modifier.padding(vertical = 2.dp).width(24.dp))
                for (row in block.rows) InlineText(row[col], style)
            }
        }
    }
}

private fun safeUrl(url: String) =
    url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

/** Builds the styled text of inline markdown. */
fun inlineAnnotated(text: String, keepNewlines: Boolean, colors: MdColors): AnnotatedString =
    buildAnnotatedString {
        for (span in parseInline(text, keepNewlines)) {
            val link = span.url != null
            val spanStyle = SpanStyle(
                color = when {
                    span.image -> colors.muted
                    link -> colors.link
                    else -> colors.text
                },
                fontFamily = if (span.code) CodeFont else null,
                background = if (span.code) colors.codeBackground else Color.Unspecified,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.strike && link -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                    span.strike -> TextDecoration.LineThrough
                    link -> TextDecoration.Underline
                    else -> null
                },
            )
            val shown = if (span.code) " ${span.text} " else span.text
            val url = span.url
            if (url != null && safeUrl(url)) {
                withLink(LinkAnnotation.Url(url, TextLinkStyles(spanStyle))) { append(shown) }
            } else {
                withStyle(spanStyle) { append(shown) }
            }
        }
    }

private fun tokenColor(kind: TokenKind, colors: CodeColors, plain: Color): Color = when (kind) {
    TokenKind.Plain, TokenKind.Punctuation -> plain
    TokenKind.Keyword -> colors.keyword
    TokenKind.Type -> colors.type
    TokenKind.Function -> colors.function
    TokenKind.String -> colors.string
    TokenKind.Number -> colors.number
    TokenKind.Comment -> colors.comment
    TokenKind.Operator -> colors.operator
}

/** A code block in syntax colours. */
private fun highlight(block: MdCodeBlock, colors: CodeColors, plain: Color): AnnotatedString = buildAnnotatedString {
    for (token in CodeHighlighter.tokenize(block.code, block.language)) {
        withStyle(SpanStyle(color = tokenColor(token.kind, colors, plain))) { append(token.text) }
    }
}
