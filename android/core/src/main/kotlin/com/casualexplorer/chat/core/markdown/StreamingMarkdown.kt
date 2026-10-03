package com.casualexplorer.chat.core.markdown

// The streaming markdown renderer is adapted from Crush
// (github.com/charmbracelet/crush, internal/ui/chat), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

/**
 * Caches the parsed blocks of a "stable prefix" so each streaming flush only
 * re-parses the trailing portion of the document; the UI then redraws only
 * the blocks that changed, since the cached ones are the same instances.
 *
 * The boundary between "stable" and "trailing" is detected by
 * [findSafeMarkdownBoundary]: a position immediately after a blank line at
 * which we can prove no markdown construct is open (fenced code block, list,
 * table, block quote, setext header).
 *
 * Two parses concatenated are NOT generally equal to a single parse of the
 * whole document. The boundary check is therefore deliberately conservative;
 * whenever it has the slightest doubt the call falls back to a full parse and
 * the cache is left untouched.
 *
 * Invariants:
 *
 *  - [stablePrefix] is always a literal prefix of the most recently rendered
 *    content. If new content does not have stablePrefix as its prefix the
 *    cache is dropped.
 *  - [stablePrefixRender] is the parse of stablePrefix alone.
 *  - [width] is the layout key that produced stablePrefixRender. A change
 *    drops the cache. (Compose wraps text itself, so callers that don't
 *    re-parse per width pass a constant.)
 */
class StreamingMarkdown(private val parse: (String) -> List<MdBlock> = Markdown::parse) {
    internal var width = 0
    internal var stablePrefix = ""
    internal var stablePrefixRender: List<MdBlock> = emptyList()

    // Cached cumulative state at the stable prefix boundary. Used by
    // findBoundaryAfter to validate new boundary candidates without
    // re-scanning the entire prefix from the start. baseFenceCount is always
    // even (safe boundaries require even fence parity), so the delta scan
    // always starts outside a fence.
    private var baseFenceCount = 0
    private var baseHasListMarker = false

    /** Drops every cached field. After reset the next render is a full parse. */
    fun reset() {
        width = 0
        stablePrefix = ""
        stablePrefixRender = emptyList()
        baseFenceCount = 0
        baseHasListMarker = false
    }

    /**
     * The blocks of [content], reusing the cached stable-prefix blocks when it
     * is safe to do so. On any uncertainty the call falls back to a full parse
     * and leaves the cache untouched (or drops it).
     */
    fun render(content: String, width: Int = 0): List<MdBlock> {
        // Width change OR content not a prefix-extension: drop cache, full
        // render, then try to seed a fresh boundary from this call.
        if (width != this.width || !content.startsWith(stablePrefix)) {
            reset()
            this.width = width
            val out = parse(content)
            tryAdvanceFromEmpty(content, width)
            return out
        }

        // Incremental boundary search: only scan the delta after the stable
        // prefix.
        val boundary = findBoundaryAfter(content)
        if (boundary < 0) {
            // No safe boundary anywhere yet. Full render; do not modify the
            // cache (a future flush may find one).
            return parse(content)
        }

        if (boundary <= stablePrefix.length) {
            // Cached prefix already covers an at-least-as-late boundary.
            // Render the trailing partial fresh and glue.
            return stablePrefixRender + renderTrailing(content.substring(stablePrefix.length))
        }

        // boundary > stablePrefix.length: we have a NEW chunk of safe content.
        // Render the new chunk, append it to stablePrefixRender, promote the
        // boundary, then render the remaining trail.
        val newChunk = content.substring(stablePrefix.length, boundary)
        stablePrefixRender = stablePrefixRender + renderTrailing(newChunk)
        stablePrefix = content.substring(0, boundary)
        baseFenceCount += countFenceLines(newChunk)
        baseHasListMarker = baseHasListMarker || chunkHasListMarker(newChunk)

        val trail = content.substring(boundary)
        if (trail.isEmpty()) return stablePrefixRender
        return stablePrefixRender + renderTrailing(trail)
    }

    /**
     * Seeds the cache from a fresh state: if there is a safe boundary inside
     * [content], parse the prefix once more and cache it so the next flush can
     * avoid the full work.
     */
    private fun tryAdvanceFromEmpty(content: String, width: Int) {
        var boundary = findSafeMarkdownBoundary(content)
        if (boundary <= 0) boundary = relaxedBoundary(content, 0)
        if (boundary <= 0) return
        val prefix = content.substring(0, boundary)
        stablePrefix = prefix
        stablePrefixRender = parse(prefix)
        this.width = width
        baseFenceCount = countFenceLines(prefix)
        baseHasListMarker = chunkHasListMarker(prefix)
    }

    /**
     * The latest safe boundary in [content] strictly after the stable prefix,
     * validated against the cached cumulative state so the search is
     * O(delta) instead of O(n). Returns -1 when no safe boundary exists.
     */
    private fun findBoundaryAfter(content: String): Int {
        // When there is no stable prefix, fall back to the full scan.
        if (stablePrefix.isEmpty()) {
            val b = findSafeMarkdownBoundary(content)
            if (b > 0) return b
            return relaxedBoundary(content, 0)
        }

        // Scan blank-line candidates from latest to earliest, but only those
        // strictly after the stable prefix.
        var p = blankLineBefore(content, content.length)
        while (p > stablePrefix.length) {
            if (isSafeBoundaryIncremental(content, p)) return p
            p = blankLineBefore(content, p - 1)
        }
        val b = relaxedBoundary(content, stablePrefix.length)
        if (b > stablePrefix.length) return b
        // No new boundary found after the stable prefix: the caller renders
        // the trailing part fresh and keeps the cache.
        return stablePrefix.length
    }

    /**
     * Looks for a cut at a plain newline once the tail after [after] has
     * outgrown [RELAX_BOUNDARY_AFTER]. Prose holding no blank line never
     * satisfies the blank-line predicate, so without this every flush would
     * re-parse the whole document: O(n) per tick, O(n²) over a turn.
     * Candidates are still validated by the same predicate the blank-line
     * search uses. Returns -1 while the tail is still small, and when no
     * candidate validates.
     */
    private fun relaxedBoundary(content: String, after: Int): Int {
        if (content.length - after <= RELAX_BOUNDARY_AFTER) return -1
        var tried = 0
        var p = newlineBefore(content, content.length)
        while (p > after) {
            if (isSafeBoundaryIncremental(content, p)) return p
            if (++tried >= RELAXED_BOUNDARY_CANDIDATES) break
            p = newlineBefore(content, p - 1)
        }
        return -1
    }

    /**
     * Validates a boundary candidate at [p] using the cached cumulative state
     * plus a scan of content[stablePrefix.length, p).
     */
    private fun isSafeBoundaryIncremental(content: String, p: Int): Boolean {
        val delta = content.substring(stablePrefix.length, p)

        // (2) Fence parity: base count + delta count must be even.
        if ((baseFenceCount + countFenceLines(delta)) % 2 != 0) return false

        // (2b) HTML and link-ref hazards in the delta.
        if (deltaHasHTMLorRef(delta)) return false

        // (2b) List hazard: if a list marker exists anywhere in the full
        // prefix, the last non-blank line before the boundary must not be an
        // indented continuation paragraph.
        val prefix = content.substring(0, p)
        val lastLine = lastNonBlankLine(prefix)
        if (baseHasListMarker || chunkHasListMarker(delta)) {
            if (lastLine.isNotEmpty() && !isListItemMarker(lastLine.trimStart(' ', '\t')) &&
                (lastLine[0] == ' ' || lastLine[0] == '\t')
            ) {
                return false
            }
        }

        // (3) Last non-blank line must not open a construct.
        if (lastLine.isNotEmpty() && lineOpensConstruct(lastLine)) return false

        // (4) Setext underline check.
        val rest = content.substring(p)
        if (rest.isNotEmpty() && isSetextUnderlineCandidate(firstNonBlankLine(rest))) return false

        return true
    }

    private fun renderTrailing(text: String): List<MdBlock> = if (text.isEmpty()) emptyList() else parse(text)

    companion object {
        /**
         * Bounds how much unstable tail a flush will re-parse. Sized just
         * above a prose paragraph so structured text never reaches it.
         */
        const val RELAX_BOUNDARY_AFTER = 2 shl 10

        /** Caps how many newline candidates one flush validates. */
        const val RELAXED_BOUNDARY_CANDIDATES = 8
    }
}

/**
 * The offset of the first character AFTER the latest newline that ends
 * strictly before [until], or -1 when there is none.
 */
internal fun newlineBefore(content: String, until: Int): Int {
    if (until <= 0) return -1
    val nl = content.lastIndexOf('\n', until - 1)
    return if (nl < 0) -1 else nl + 1
}

/**
 * Whether the delta (text between the stable prefix and a boundary
 * candidate) contains an HTML block opener or a link reference definition.
 */
internal fun deltaHasHTMLorRef(delta: String): Boolean {
    var inFence = false
    for (line in splitLines(delta)) {
        if (isFenceLine(line)) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        if (isHTMLBlockOpener(line) || isLinkRefDefinition(line)) return true
    }
    return false
}

/** Whether any line in [chunk] is a list-item marker (outside fenced code blocks). */
internal fun chunkHasListMarker(chunk: String): Boolean {
    var inFence = false
    for (line in splitLines(chunk)) {
        if (isFenceLine(line)) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        if (isListItemMarker(line.trimStart(' ', '\t'))) return true
    }
    return false
}

/**
 * The offset of the END of the latest safe boundary in [content], i.e. the
 * offset such that content[0, boundary) is a valid stable-prefix candidate.
 * The offset always points immediately after a blank-line separator, so
 * concatenating a fresh parse of the rest to a cached parse of the prefix
 * needs no shared state across the cut.
 *
 * Returns -1 when no safe boundary exists. SAFETY FIRST: any time we have the
 * slightest doubt we return -1 and let the caller fall back to a full parse.
 *
 * Decision tree, in order of preference (latest boundary wins):
 *
 *  1. Walk backward through every blank-line position p.
 *  2. Require an even number of fence lines in the prefix (no open fence).
 *     2b. Reject list, HTML-block and link-reference hazards anywhere in the
 *     prefix; see [prefixHasOpenHazard].
 *  3. Reject if the last non-blank line of the prefix is a list item, a table
 *     line, a block quote, a setext underline or indented code.
 *  4. Reject if the first non-blank line after the boundary looks like a
 *     setext underline, which would change the prefix's last paragraph.
 */
fun findSafeMarkdownBoundary(content: String): Int {
    if (content.isEmpty()) return -1
    var p = blankLineBefore(content, content.length)
    while (p > 0) {
        if (isSafeBoundaryAt(content, p)) return p
        p = blankLineBefore(content, p - 1)
    }
    return -1
}

/**
 * The offset of the first character AFTER the latest blank-line separator
 * ("\n([ \t]*\n)+") that ends strictly before [until], or -1.
 */
internal fun blankLineBefore(content: String, until: Int): Int {
    if (until <= 0) return -1
    var end = until
    while (end > 0) {
        val nl = content.lastIndexOf('\n', end - 1)
        if (nl < 0) return -1
        val prev = if (nl == 0) -1 else content.lastIndexOf('\n', nl - 1)
        if (prev >= 0 && isBlankOrSpaces(content, prev + 1, nl)) return nl + 1
        end = nl
    }
    return -1
}

private fun isBlankOrSpaces(s: String, from: Int, to: Int): Boolean {
    for (i in from until to) if (s[i] != ' ' && s[i] != '\t') return false
    return true
}

/** Whether content[0, p) is a safe stable prefix; p must be a blank-line boundary. */
internal fun isSafeBoundaryAt(content: String, p: Int): Boolean {
    val prefix = content.substring(0, p)
    if (countFenceLines(prefix) % 2 != 0) return false
    if (prefixHasOpenHazard(prefix)) return false
    val lastLine = lastNonBlankLine(prefix)
    if (lastLine.isNotEmpty() && lineOpensConstruct(lastLine)) return false
    val rest = content.substring(p)
    if (rest.isNotEmpty() && isSetextUnderlineCandidate(firstNonBlankLine(rest))) return false
    return true
}

/**
 * Whether [prefix] contains any of three constructs that cannot be safely cut
 * at a blank-line boundary even when the immediately preceding line looks
 * fine:
 *
 *  - B1 (loose lists): a list marker appeared and the last non-blank line is
 *    indented but not itself a marker: a continuation paragraph of a list
 *    that may still be open. A non-indented last line means the list was
 *    closed by the blank line before it.
 *  - B2 (HTML blocks): any HTML-block opener, since the suffix may close it.
 *  - B3 (reference link definitions): any "[label]: url" line, which the
 *    suffix may use and which a separate parse would lose.
 */
internal fun prefixHasOpenHazard(prefix: String): Boolean {
    var inFence = false
    var hasListMarker = false
    var lastTrimmed = ""
    var lastRaw = ""
    for (line in splitLines(prefix)) {
        if (isFenceLine(line)) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        val trimmed = line.trimStart(' ', '\t')
        if (trimmed.isEmpty()) continue
        lastTrimmed = trimmed
        lastRaw = line
        if (isListItemMarker(trimmed)) hasListMarker = true
        if (isHTMLBlockOpener(line)) return true
        if (isLinkRefDefinition(line)) return true
    }
    return hasListMarker && lastTrimmed.isNotEmpty() && !isListItemMarker(lastTrimmed) &&
        (lastRaw[0] == ' ' || lastRaw[0] == '\t')
}

/** Counts lines that open or close a fenced code block. */
internal fun countFenceLines(s: String): Int = splitLines(s).count(::isFenceLine)

/**
 * Whether [line] opens or closes a fenced code block: up to three spaces,
 * then at least three backticks or tildes.
 */
internal fun isFenceLine(line: String): Boolean {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    if (i >= line.length) return false
    val c = line[i]
    if (c != '`' && c != '~') return false
    var run = 0
    while (i < line.length && line[i] == c) {
        i++
        run++
    }
    return run >= 3
}

internal fun lastNonBlankLine(s: String): String = splitLines(s).lastOrNull { it.isNotBlank() } ?: ""

internal fun firstNonBlankLine(s: String): String = splitLines(s).firstOrNull { it.isNotBlank() } ?: ""

/** The lines of [s] without their terminators; a trailing unterminated segment is included. */
internal fun splitLines(s: String): Sequence<String> = sequence {
    var start = 0
    for (i in s.indices) {
        if (s[i] == '\n') {
            yield(s.substring(start, i))
            start = i + 1
        }
    }
    if (start <= s.length - 1) yield(s.substring(start))
}

/**
 * Whether [line] keeps a markdown construct open across the boundary. Errs
 * conservatively: anything that smells like list/table/quote/setext/indented
 * code returns true.
 */
internal fun lineOpensConstruct(line: String): Boolean {
    if (line.isNotEmpty() && line[0] == '\t') return true
    if (line.startsWith("    ")) return true
    val trimmed = line.trimStart(' ', '\t')
    if (trimmed.isEmpty()) return false
    if (trimmed[0] == '>') return true
    if (isListItemMarker(trimmed)) return true
    // Table: any pipe anywhere. Pipe-in-prose is rare and the cost of
    // bailing is one slow frame.
    if ('|' in line) return true
    return isSetextUnderlineCandidate(trimmed)
}

/**
 * Whether [line] (already left-trimmed) starts with a CommonMark list-item
 * marker followed by a space or tab.
 */
internal fun isListItemMarker(line: String): Boolean {
    if (line.isEmpty()) return false
    val c = line[0]
    if (c == '-' || c == '*' || c == '+') return line.length >= 2 && (line[1] == ' ' || line[1] == '\t')
    var i = 0
    while (i < line.length && line[i] in '0'..'9') i++
    if (i == 0 || i > 9 || i >= line.length) return false
    if (line[i] != '.' && line[i] != ')') return false
    if (i + 1 >= line.length) return false
    return line[i + 1] == ' ' || line[i + 1] == '\t'
}

/**
 * Whether [line] (with optional leading whitespace) is entirely '=' or
 * entirely '-' with optional trailing whitespace.
 */
internal fun isSetextUnderlineCandidate(line: String): Boolean {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    if (i == line.length) return false
    val c = line[i]
    if (c != '=' && c != '-') return false
    var j = i
    while (j < line.length && line[j] == c) j++
    while (j < line.length) {
        if (line[j] != ' ' && line[j] != '\t') return false
        j++
    }
    return j - i >= 1
}

/**
 * Whether [line] begins one of the seven CommonMark HTML block patterns,
 * loosely: "<!--", "<?", "<![CDATA[", "<!X", script/pre/style/textarea, or
 * '<' or '</' followed by an ASCII letter.
 */
internal fun isHTMLBlockOpener(line: String): Boolean {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    val rest = line.substring(i)
    if (rest.length < 2 || rest[0] != '<') return false
    if (rest.startsWith("<!--") || rest.startsWith("<?") || rest.startsWith("<![CDATA[")) return true
    if (rest.length >= 3 && rest[1] == '!' && rest[2].isAsciiLetter()) return true
    val low = rest.lowercase()
    for (t in listOf("<script", "<pre", "<style", "<textarea")) {
        if (low.startsWith(t)) {
            val next = low.getOrNull(t.length)
            if (next == null || next == ' ' || next == '\t' || next == '>') return true
        }
    }
    var j = 1
    if (j < rest.length && rest[j] == '/') j++
    return j < rest.length && rest[j].isAsciiLetter()
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/** Whether [line] opens a link reference definition: `^ {0,3}\[[^\]]+\]:\s*\S+`. */
internal fun isLinkRefDefinition(line: String): Boolean {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    if (i >= line.length || line[i] != '[') return false
    i++
    val labelStart = i
    while (i < line.length && line[i] != ']') i++
    if (i >= line.length || i == labelStart) return false
    i++
    if (i >= line.length || line[i] != ':') return false
    i++
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i < line.length
}

/** The number of lines in [s]; an empty string is one line. */
fun countLines(s: String): Int = s.count { it == '\n' } + 1

/**
 * The last [n] lines of [s] and the count of hidden (earlier) lines.
 * [totalLines] is the line count of s (from [countLines]).
 */
fun tailLines(s: String, n: Int, totalLines: Int): Pair<String, Int> {
    if (n <= 0) return "" to totalLines
    if (totalLines <= n) return s to 0
    var count = 0
    for (i in s.length - 1 downTo 0) {
        if (s[i] == '\n' && ++count == n) return s.substring(i + 1) to totalLines - n
    }
    return s to 0
}
