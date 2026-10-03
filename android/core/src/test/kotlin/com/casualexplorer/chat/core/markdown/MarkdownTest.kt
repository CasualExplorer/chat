package com.casualexplorer.chat.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    @Test
    fun parsesCommonBlocks() {
        val doc = """
            # Title

            Some *text*
            on two lines.

            Sub
            ---

            - one
            - two
              - nested

            1. first
            2. second

            > quoted
            > more

            ```kotlin
            val x = 1
            ```

            | a | b |
            |:--|--:|
            | 1 | 2 |

            ***

            - [x] done
        """.trimIndent()
        val blocks = Markdown.parse(doc)
        assertEquals(MdHeading(1, "Title"), blocks[0])
        assertEquals(MdParagraph("Some *text*\non two lines."), blocks[1])
        assertEquals(MdHeading(2, "Sub"), blocks[2])
        val bullets = blocks[3] as MdList
        assertEquals(listOf("•", "•"), bullets.items.map { it.marker })
        assertEquals(MdList(false, listOf(MdListItem("•", listOf(MdParagraph("nested"))))), bullets.items[1].blocks[1])
        assertEquals(listOf("1.", "2."), (blocks[4] as MdList).items.map { it.marker })
        assertEquals(MdQuote(listOf(MdParagraph("quoted\nmore"))), blocks[5])
        assertEquals(MdCodeBlock("kotlin", "val x = 1"), blocks[6])
        assertEquals(MdTable(listOf("a", "b"), listOf(MdAlign.Start, MdAlign.End), listOf(listOf("1", "2"))), blocks[7])
        assertEquals(MdRule, blocks[8])
        assertEquals(true, (blocks[9] as MdList).items.single().checked)
    }

    @Test
    fun unclosedFenceIsOpenCode() {
        assertEquals(listOf(MdParagraph("Intro"), MdCodeBlock("py", "x = 1\n", closed = false)), Markdown.parse("Intro\n\n```py\nx = 1\n"))
    }

    @Test
    fun orderedListKeepsItsStart() {
        assertEquals(listOf("3.", "4."), (Markdown.parse("3. c\n4. d").single() as MdList).items.map { it.marker })
    }

    @Test
    fun inlineStyles() {
        val spans = parseInline("a **bold** *it* ~~gone~~ `code` [link](http://x \"t\") \\*lit\\*")
        assertEquals(
            listOf(
                InlineSpan("a "), InlineSpan("bold", bold = true), InlineSpan(" "), InlineSpan("it", italic = true),
                InlineSpan(" "), InlineSpan("gone", strike = true), InlineSpan(" "), InlineSpan("code", code = true),
                InlineSpan(" "), InlineSpan("link", url = "http://x"), InlineSpan(" *lit*"),
            ),
            spans,
        )
    }

    @Test
    fun unclosedDelimitersStayLiteral() {
        assertEquals(listOf(InlineSpan("streaming **bol")), parseInline("streaming **bol"))
        assertEquals(listOf(InlineSpan("snake_case_name")), parseInline("snake_case_name"))
    }

    @Test
    fun nestedEmphasis() {
        val spans = parseInline("*a **b** c*")
        assertEquals(
            listOf(InlineSpan("a ", italic = true), InlineSpan("b", bold = true, italic = true), InlineSpan(" c", italic = true)),
            spans,
        )
        assertEquals(listOf(InlineSpan("both", bold = true, italic = true)), parseInline("***both***"))
    }

    @Test
    fun softBreaksBecomeSpacesUnlessKept() {
        assertEquals("one two", inlinePlainText("one\ntwo"))
        assertEquals("one\ntwo", parseInline("one\ntwo", keepNewlines = true).joinToString("") { it.text })
        assertEquals("hard\nbreak", inlinePlainText("hard  \nbreak"))
    }

    @Test
    fun highlighterFindsTokenKinds() {
        val tokens = CodeHighlighter.tokenize("fun main() { // hi\n  val s = \"x\" + 42\n}", "kotlin")
        fun kindOf(text: String) = tokens.first { it.text.trim() == text }.kind
        assertEquals(TokenKind.Keyword, kindOf("fun"))
        assertEquals(TokenKind.Function, kindOf("main"))
        assertEquals(TokenKind.Comment, kindOf("// hi"))
        assertEquals(TokenKind.String, kindOf("\"x\""))
        assertEquals(TokenKind.Number, kindOf("42"))
        assertEquals("fun main() { // hi\n  val s = \"x\" + 42\n}", tokens.joinToString("") { it.text }, "tokens cover the code")
    }

    @Test
    fun highlighterUsesHashCommentsForPython() {
        val tokens = CodeHighlighter.tokenize("# note\nprint('a')", "python")
        assertEquals(CodeToken("# note", TokenKind.Comment), tokens.first())
        assertTrue(tokens.any { it.kind == TokenKind.String && it.text == "'a'" })
    }
}
