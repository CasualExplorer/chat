package com.casualexplorer.chat.core.markdown

// Adapted from Crush (github.com/charmbracelet/crush, internal/ui/chat),
// Copyright 2025-2026 Charmbracelet, Inc., used under FSL-1.1-MIT.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The visible text of [blocks], one line per rendered line, the way a reader
 * sees it. The Go tests compare glamour output with ANSI stripped; here the
 * render is the block list, and this is its visible form.
 */
fun plainText(blocks: List<MdBlock>, indent: String = ""): List<String> = blocks.flatMap { block ->
    when (block) {
        is MdHeading -> listOf(indent + inlinePlainText(block.text))
        is MdParagraph -> inlinePlainText(block.text).split('\n').map { indent + it }
        is MdCodeBlock -> block.code.split('\n').map { "$indent  $it" }
        is MdQuote -> plainText(block.blocks, "$indent│ ")
        is MdList -> block.items.flatMap { item ->
            val lines = plainText(item.blocks, "$indent  ")
            if (lines.isEmpty()) listOf(indent + item.marker) else listOf(indent + item.marker + " " + lines[0].trimStart()) + lines.drop(1)
        }
        is MdTable -> (listOf(block.header) + block.rows).map { row -> indent + row.joinToString("  ") { inlinePlainText(it) } }
        MdRule -> listOf("$indent--------")
        is MdHtml -> block.text.split('\n').map { indent + it }
    }
}

/** The non-blank visible lines of [blocks], with trailing whitespace trimmed. */
fun nonBlankLines(blocks: List<MdBlock>): List<String> = plainText(blocks).map { it.trimEnd() }.filter { it.isNotBlank() }

/**
 * Whether the visible text contains markdown source markers that parsing
 * should have consumed: "```" fence delimiters and bare "###" headers.
 */
fun containsRawMarkdownSource(blocks: List<MdBlock>): Boolean {
    val lines = plainText(blocks.filter { it !is MdCodeBlock })
    return lines.any { "```" in it || it.trimStart().startsWith("###") }
}

/** Splits [doc] into [n] growing prefixes, ending with the full document. */
fun progressivePrefixes(doc: String, n: Int): List<String> = (1..maxOf(n, 1)).map { i ->
    doc.substring(0, if (i == n) doc.length else doc.length * i / n)
}

/** A fresh parse of the whole document, which the streamed render must match. */
fun freshRender(content: String) = Markdown.parse(content)

class MarkdownStreamTest {
    // ---------------------------------------------------------------------
    // findSafeMarkdownBoundary unit tests.
    // ---------------------------------------------------------------------

    private data class Case(val name: String, val content: String, val want: Int)

    @Test
    fun findSafeMarkdownBoundaryTableDriven() {
        val cases = listOf(
            Case("empty", "", -1),
            Case("single line", "Just a single paragraph", -1),
            Case("two paragraphs", "First paragraph.\n\nSecond paragraph.", "First paragraph.\n\n".length),
            Case("three paragraphs picks latest", "First.\n\nSecond.\n\nThird.", "First.\n\nSecond.\n\n".length),
            // The only blank line is before the fence opens, which doesn't
            // change the prefix's rendering.
            Case("open fence at end", "Para.\n\n```go\nfoo()\n", "Para.\n\n".length),
            // The blank line after foo() is inside the fence (odd count); the
            // earlier one before the fence is still safe.
            Case("inside open fence: no candidate after open", "Para.\n\n```go\nfoo()\n\nbar()\n", "Para.\n\n".length),
            Case("closed fence followed by paragraph", "Para1.\n\n```\nfoo()\n```\n\nPara2.", "Para1.\n\n```\nfoo()\n```\n\n".length),
            // A list opening after the boundary doesn't change the prefix.
            Case("open list at end", "Para.\n\n- one\n- two\n", "Para.\n\n".length),
            Case("list interior: no boundary", "- one\n- two\n", -1),
            // Conservative: the list may not be closed.
            Case("closed list then paragraph", "- one\n- two\n\nPara.", -1),
            Case("table at end", "Para.\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n", "Para.\n\n".length),
            Case("table interior with internal blank line: no late boundary", "| a | b |\n| --- | --- |\n\n| 1 | 2 |\n", -1),
            Case("block quote at end", "Para.\n\n> quoted\n> still quoted\n", "Para.\n\n".length),
            // The first line after the boundary looks like a setext underline.
            Case("setext underline pending", "Heading\n\n=====\n", -1),
            Case("indented code at end of prefix", "Para.\n\n    code line\n\nNext.", "Para.\n\n".length),
        )
        for (c in cases) {
            val got = findSafeMarkdownBoundary(c.content)
            assertEquals(c.want, got, "${c.name}: findSafeMarkdownBoundary(${c.content.quote()})")
            if (got > 0) {
                assertTrue(got <= c.content.length, "${c.name}: boundary $got out of range")
                assertEquals('\n', c.content[got - 1], "${c.name}: boundary $got does not sit immediately after a newline")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Streaming-equivalence tests.
    // ---------------------------------------------------------------------

    private val scenarios = mapOf(
        "plain-paragraphs" to listOf(
            "This is the first paragraph of the document.",
            "",
            "Here is the second paragraph; it has some words.",
            "",
            "And a third paragraph for good measure.",
            "",
            "Finally a fourth paragraph to push past one boundary.",
        ),
        "paragraphs-with-fence" to listOf(
            "Intro paragraph.",
            "",
            "Some explanatory prose before the code.",
            "",
            "```go",
            "func hello() {",
            "\tfmt.Println(\"hi\")",
            "}",
            "```",
            "",
            "And a closing paragraph after the code block.",
        ),
        "paragraphs-with-list" to listOf(
            "Intro paragraph.",
            "",
            "- list item one",
            "- list item two",
            "- list item three",
            "",
            "Trailing paragraph.",
        ),
        "paragraphs-with-table" to listOf(
            "Intro paragraph.",
            "",
            "| col a | col b |",
            "| ----- | ----- |",
            "| 1     | 2     |",
            "| 3     | 4     |",
            "",
            "Trailing paragraph after the table.",
        ),
    ).mapValues { it.value.joinToString("\n") }

    /**
     * Drives progressive prefixes through the cache and asserts the final
     * output matches a fresh parse of the whole document. Block lists are
     * compared exactly, a stricter bar than the Go test's visual equivalence.
     */
    @Test
    fun finalEquivalentToFreshRender() {
        for ((name, doc) in scenarios) {
            val sm = StreamingMarkdown()
            var last = emptyList<MdBlock>()
            for (p in progressivePrefixes(doc, 15)) last = sm.render(p, 80)
            assertEquals(freshRender(doc), last, "$name: final streaming output must match a fresh full render")
        }
    }

    /** Every intermediate flush is non-empty and doesn't leak raw markdown source. */
    @Test
    fun intermediateOutputsPlausible() {
        for ((name, doc) in scenarios) {
            val sm = StreamingMarkdown()
            progressivePrefixes(doc, 12).forEachIndexed { i, p ->
                if (p.isEmpty()) return@forEachIndexed
                val out = sm.render(p, 80)
                assertTrue(out.isNotEmpty(), "$name step $i: empty render for prefix len ${p.length}")
                assertFalse(containsRawMarkdownSource(out), "$name step $i: render leaked raw markdown source.\nprefix=${p.quote()}\nout=$out")
            }
        }
    }

    /** Streaming re-parses only the tail: the stable prefix's blocks are the same instances. */
    @Test
    fun stablePrefixBlocksAreReused() {
        val sm = StreamingMarkdown()
        val first = sm.render("Para one.\n\nPara two.\n\nPara thr", 0)
        val second = sm.render("Para one.\n\nPara two.\n\nPara three.", 0)
        assertEquals(3, second.size)
        assertTrue(first[0] === second[0] && first[1] === second[1], "stable blocks must be reused, not re-parsed")
        assertEquals(MdParagraph("Para three."), second[2])
    }

    // ---------------------------------------------------------------------
    // Cache invalidation tests.
    // ---------------------------------------------------------------------

    /**
     * A layout key change drops the cache. (The Go test also checks that the
     * render differs between widths; Compose wraps text itself, so the
     * blocks don't depend on width and only the cache contract applies.)
     */
    @Test
    fun widthChangeInvalidates() {
        val doc = "Para one.\n\nPara two.\n\nPara three."
        val sm = StreamingMarkdown()
        sm.render(doc, 80)
        assertEquals(80, sm.width, "width must be cached after first render")
        val out = sm.render(doc, 40)
        assertEquals(40, sm.width, "width change must update cached width")
        assertTrue(doc.startsWith(sm.stablePrefix), "stable prefix must be a prefix of the current content")
        assertEquals(freshRender(doc), out)
    }

    /** Content that doesn't extend the stable prefix (the user retried the turn) resets the cache. */
    @Test
    fun nonPrefixContentInvalidates() {
        val sm = StreamingMarkdown()
        for (p in progressivePrefixes("Para one.\n\nPara two.\n\nPara three.", 6)) sm.render(p, 80)
        assertTrue(sm.stablePrefix.isNotEmpty(), "stable prefix must be populated after streaming a multi-paragraph doc")

        val other = "Completely different opening paragraph.\n\nAnd a second."
        val out = sm.render(other, 80)
        assertTrue(out.isNotEmpty())
        assertTrue(other.startsWith(sm.stablePrefix), "stable prefix must be reset to a prefix of the new content")
        assertEquals(freshRender(other), out, "render after non-prefix content change must match a fresh render")
    }

    @Test
    fun resetClearsCache() {
        val doc = "Para one.\n\nPara two.\n\nPara three."
        val sm = StreamingMarkdown()
        sm.render(doc, 80)
        sm.reset()
        assertEquals(0, sm.width)
        assertEquals("", sm.stablePrefix)
        assertEquals(emptyList(), sm.stablePrefixRender)
        assertEquals(freshRender(doc), sm.render(doc, 80))
    }

    // ---------------------------------------------------------------------
    // Fallback safety.
    // ---------------------------------------------------------------------

    /**
     * One giant table built row by row: there is never a boundary, so every
     * flush is a full parse equal to a fresh one, and the cache never
     * advances.
     */
    @Test
    fun noSafeBoundaryAlwaysFullRenders() {
        val doc = listOf(
            "| col a | col b | col c |",
            "| ----- | ----- | ----- |",
            "| 1     | 2     | 3     |",
            "| 4     | 5     | 6     |",
            "| 7     | 8     | 9     |",
            "| 10    | 11    | 12    |",
            "| 13    | 14    | 15    |",
            "| 16    | 17    | 18    |",
            "| 19    | 20    | 21    |",
            "| 22    | 23    | 24    |",
        ).joinToString("\n")
        assertEquals(-1, findSafeMarkdownBoundary(doc), "sanity check: no blank lines, no safe boundary")

        val sm = StreamingMarkdown()
        progressivePrefixes(doc, 10).forEachIndexed { i, p ->
            if (p.isEmpty()) return@forEachIndexed
            assertEquals(freshRender(p), sm.render(p, 80), "step $i (len=${p.length}) must equal a fresh render")
        }
        assertEquals("", sm.stablePrefix, "stable prefix must remain empty when no safe boundary ever exists")
    }

    /** A single line growing one character at a time never crashes or renders empty. */
    @Test
    fun noSafeBoundaryDoesNotCrash() {
        val src = "The quick brown fox jumps over the lazy dog."
        val sm = StreamingMarkdown()
        for (i in 1..src.length) assertTrue(sm.render(src.substring(0, i), 80).isNotEmpty())
    }

    // ---------------------------------------------------------------------
    // Anywhere-in-prefix hazards (B1 / B2 / B3, see prefixHasOpenHazard).
    // The cached stable prefix may never extend past the hazard line, and
    // the final flush must show the same lines as a fresh parse.
    // ---------------------------------------------------------------------

    private fun runProgressiveBoundaryRespectTest(doc: String, hazardLineOffset: Int) {
        val sm = StreamingMarkdown()
        var last = emptyList<MdBlock>()
        progressivePrefixes(doc, 25).forEachIndexed { i, p ->
            if (p.isEmpty()) return@forEachIndexed
            last = sm.render(p, 80)
            assertTrue(last.isNotEmpty(), "step $i: empty render")
            assertTrue(
                sm.stablePrefix.length <= hazardLineOffset,
                "step $i: cached stable prefix advanced past the hazard line\n" +
                    "prefix len=${sm.stablePrefix.length}, hazard at $hazardLineOffset, stablePrefix=${sm.stablePrefix.quote()}",
            )
        }
        assertEquals(nonBlankLines(freshRender(doc)), nonBlankLines(last), "final streaming output must show the same lines as a fresh render")
    }

    @Test
    fun looseListContinuation() {
        val doc = listOf(
            "Intro paragraph.",
            "",
            "- item one",
            "",
            "  continuation paragraph still belongs to item one",
            "",
            "- item two",
            "",
            "Trailing paragraph after the list.",
        ).joinToString("\n")
        runProgressiveBoundaryRespectTest(doc, doc.indexOf("- item one"))
    }

    @Test
    fun htmlBlock() {
        val doc = listOf(
            "Intro paragraph.",
            "",
            "<div>",
            "some block content",
            "</div>",
            "",
            "Trailing paragraph after the HTML block.",
        ).joinToString("\n")
        runProgressiveBoundaryRespectTest(doc, doc.indexOf("<div>"))
    }

    /** HTML block type 7: a generic tag not in the fixed type-6 set. */
    @Test
    fun htmlBlockType7() {
        val doc = listOf(
            "Intro paragraph.",
            "",
            "<custom-tag>",
            "some block content",
            "</custom-tag>",
            "",
            "Trailing paragraph after the custom-tag block.",
        ).joinToString("\n")
        runProgressiveBoundaryRespectTest(doc, doc.indexOf("<custom-tag>"))
    }

    @Test
    fun linkRefDefinition() {
        val doc = listOf(
            "Intro paragraph.",
            "",
            "[ref]: http://example.com",
            "",
            "Trailing paragraph that links to [the example][ref] inline.",
        ).joinToString("\n")
        runProgressiveBoundaryRespectTest(doc, doc.indexOf("[ref]:"))
        // The reference resolves: the link text shows, not the brackets.
        val spans = parseInline((freshRender(doc).last() as MdParagraph).text)
        assertTrue(spans.any { it.text == "the example" && it.url == "http://example.com" }, "$spans")
    }

    // ---------------------------------------------------------------------
    // Relaxed boundary: past RELAX_BOUNDARY_AFTER of unstable tail, cut at a
    // plain newline, but only where the same hazard checks pass.
    // ---------------------------------------------------------------------

    private fun longProse(n: Int) = (0 until n).joinToString("\n") {
        "Let me reconsider step $it: the constraint holds for every branch here."
    }

    @Test
    fun relaxedBoundaryAdvancesOnLongProse() {
        val doc = longProse(400)
        assertTrue(doc.length > StreamingMarkdown.RELAX_BOUNDARY_AFTER * 2, "sanity check: doc must outgrow the relax threshold")
        assertEquals(-1, findSafeMarkdownBoundary(doc), "sanity check: no blank lines, no safe boundary")

        val sm = StreamingMarkdown()
        var last = emptyList<MdBlock>()
        for (p in progressivePrefixes(doc, 40)) {
            if (p.isEmpty()) continue
            last = sm.render(p, 80)
            assertTrue(last.isNotEmpty())
            assertTrue(p.startsWith(sm.stablePrefix), "stable prefix must stay a literal prefix of the content")
        }
        assertTrue(sm.stablePrefix.isNotEmpty(), "relaxed boundary must advance the cache once the tail outgrows the threshold")
        val visible = plainText(last).joinToString("\n")
        assertTrue("step 0:" in visible, "head of the trace must survive")
        assertTrue("step 399:" in visible, "tail of the trace must survive")
    }

    @Test
    fun relaxedBoundaryRespectsHazards() {
        val tests = mapOf(
            // Every line holds a pipe, so lineOpensConstruct rejects each candidate.
            "long table" to (listOf("| col a | col b | col c |", "| ----- | ----- | ----- |") +
                (2 until 1200).map { "| $it | ${it * 2} | ${it * 3} |" }).joinToString("\n"),
            // An open fence makes the parity odd at every candidate inside it.
            "open code fence" to "```go\n" + longProse(400),
        )
        for ((name, doc) in tests) {
            assertTrue(doc.length > StreamingMarkdown.RELAX_BOUNDARY_AFTER * 2)
            val sm = StreamingMarkdown()
            for (p in progressivePrefixes(doc, 40)) {
                if (p.isNotEmpty()) assertTrue(sm.render(p, 80).isNotEmpty())
            }
            assertEquals("", sm.stablePrefix, "$name: cache must not advance across an unsafe construct")
        }
    }

    @Test
    fun tailLinesKeepsTheLastLines() {
        val s = (1..15).joinToString("\n") { "line $it" }
        val (tail, hidden) = tailLines(s, 10, countLines(s))
        assertEquals(5, hidden)
        assertEquals("line 6", tail.lineSequence().first())
        assertEquals(s to 0, tailLines(s, 20, countLines(s)))
    }

    private fun String.quote() = "\"" + replace("\n", "\\n") + "\""
}
