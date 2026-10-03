package com.casualexplorer.chat.core.markdown

/**
 * A parsed markdown block. Blocks are immutable values: the streaming cache
 * hands back the same instances for the stable prefix, so the UI can skip
 * redrawing them.
 */
sealed interface MdBlock

/** [text] is inline markdown; see [parseInline]. */
data class MdHeading(val level: Int, val text: String) : MdBlock

/** [text] is inline markdown with the paragraph's line breaks kept as '\n'. */
data class MdParagraph(val text: String) : MdBlock

/** [closed] is false while the closing fence hasn't arrived. */
data class MdCodeBlock(val language: String, val code: String, val closed: Boolean = true) : MdBlock

data class MdQuote(val blocks: List<MdBlock>) : MdBlock

/** [marker] is "•", "3." and so on; [checked] is null unless the item is a task. */
data class MdListItem(val marker: String, val blocks: List<MdBlock>, val checked: Boolean? = null)

data class MdList(val ordered: Boolean, val items: List<MdListItem>) : MdBlock

enum class MdAlign { Start, Center, End }

data class MdTable(val header: List<String>, val aligns: List<MdAlign>, val rows: List<List<String>>) : MdBlock

data object MdRule : MdBlock

/** Raw HTML, shown as its source. */
data class MdHtml(val text: String) : MdBlock

/**
 * A small CommonMark-flavoured block parser: headings (ATX and setext),
 * paragraphs, fenced and indented code, block quotes, nested lists, GFM
 * tables, rules, HTML blocks and link reference definitions. It is lenient:
 * any input parses, which matters while a reply is still streaming.
 */
object Markdown {
    fun parse(source: String): List<MdBlock> {
        val refs = LinkedHashMap<String, String>()
        val blocks = parseLines(source.replace("\r\n", "\n").split('\n'), refs)
        return if (refs.isEmpty()) blocks else blocks.map { resolveRefs(it, refs) }
    }

    private val atxHeading = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val rule = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val listMarker = Regex("""^( {0,3})([-*+]|\d{1,9}[.)])(?:([ \t]+)(.*))?$""")
    private val tableDelimiter = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val linkRef = Regex("""^ {0,3}\[([^\]]+)]:[ \t]*<?(\S+?)>?(?:[ \t]+.*)?$""")
    private val task = Regex("""^\[([ xX])][ \t]+""")

    private fun isBlank(line: String) = line.isBlank()

    private fun fenceOf(line: String): Pair<Char, Int>? {
        if (!isFenceLine(line)) return null
        val t = line.trimStart(' ')
        val c = t[0]
        return c to t.takeWhile { it == c }.length
    }

    private fun leadingSpaces(line: String): Int {
        var n = 0
        for (ch in line) {
            n += when (ch) {
                ' ' -> 1
                '\t' -> 4 - n % 4
                else -> return n
            }
        }
        return n
    }

    /** Removes up to [n] columns of indentation. */
    private fun dedent(line: String, n: Int): String {
        var col = 0
        var i = 0
        while (i < line.length && col < n) {
            when (line[i]) {
                ' ' -> col++
                '\t' -> col += 4 - col % 4
                else -> break
            }
            i++
        }
        return line.substring(i)
    }

    /** Whether [line] starts a block that ends a paragraph. */
    private fun interruptsParagraph(line: String): Boolean {
        if (isFenceLine(line) || atxHeading.matches(line) || rule.matches(line)) return true
        val t = line.trimStart(' ')
        if (leadingSpaces(line) < 4 && t.startsWith(">")) return true
        if (isHTMLBlockOpener(line)) return true
        val m = listMarker.matchEntire(line) ?: return false
        val content = m.groupValues[4]
        if (content.isBlank()) return false
        val marker = m.groupValues[2]
        return !marker[0].isDigit() || marker.dropLast(1) == "1"
    }

    private fun parseLines(lines: List<String>, refs: MutableMap<String, String>): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isBlank(line)) {
                i++
                continue
            }

            // Fenced code.
            val fence = fenceOf(line)
            if (fence != null) {
                val indent = leadingSpaces(line)
                val info = line.trimStart(' ').drop(fence.second).trim()
                val code = ArrayList<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    val close = fenceOf(lines[i])
                    if (close != null && close.first == fence.first && close.second >= fence.second &&
                        lines[i].trimStart(' ').drop(close.second).isBlank()
                    ) {
                        closed = true
                        i++
                        break
                    }
                    code += dedent(lines[i], indent)
                    i++
                }
                blocks += MdCodeBlock(info.substringBefore(' ').substringBefore('{'), code.joinToString("\n"), closed)
                continue
            }

            // Indented code.
            if (leadingSpaces(line) >= 4) {
                val code = ArrayList<String>()
                while (i < lines.size && (isBlank(lines[i]) || leadingSpaces(lines[i]) >= 4)) {
                    code += dedent(lines[i], 4)
                    i++
                }
                while (code.isNotEmpty() && code.last().isBlank()) code.removeAt(code.size - 1)
                blocks += MdCodeBlock("", code.joinToString("\n"))
                continue
            }

            val heading = atxHeading.matchEntire(line)
            if (heading != null) {
                blocks += MdHeading(heading.groupValues[1].length, heading.groupValues[2].trim())
                i++
                continue
            }

            if (rule.matches(line)) {
                blocks += MdRule
                i++
                continue
            }

            // Block quote: the '>' lines, plus lazy continuation lines.
            if (line.trimStart(' ').startsWith(">")) {
                val inner = ArrayList<String>()
                while (i < lines.size && !isBlank(lines[i])) {
                    val t = lines[i].trimStart(' ')
                    if (t.startsWith(">")) {
                        inner += t.drop(1).let { if (it.startsWith(" ")) it.drop(1) else it }
                    } else if (interruptsParagraph(lines[i])) {
                        break
                    } else {
                        inner += lines[i]
                    }
                    i++
                }
                blocks += MdQuote(parseLines(inner, refs))
                continue
            }

            if (isHTMLBlockOpener(line)) {
                val html = ArrayList<String>()
                while (i < lines.size && !isBlank(lines[i])) html += lines[i++]
                blocks += MdHtml(html.joinToString("\n"))
                continue
            }

            if (listMarker.matches(line)) {
                i = parseList(lines, i, refs, blocks)
                continue
            }

            // Table: a header row followed by a delimiter row.
            if ('|' in line && i + 1 < lines.size && tableDelimiter.matches(lines[i + 1]) && '-' in lines[i + 1]) {
                val header = splitRow(line)
                val aligns = splitRow(lines[i + 1]).map {
                    val c = it.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> MdAlign.Center
                        c.endsWith(":") -> MdAlign.End
                        else -> MdAlign.Start
                    }
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && !isBlank(lines[i]) && '|' in lines[i]) {
                    val cells = splitRow(lines[i++])
                    rows += List(header.size) { cells.getOrElse(it) { "" } }
                }
                blocks += MdTable(header, List(header.size) { aligns.getOrElse(it) { MdAlign.Start } }, rows)
                continue
            }

            val ref = linkRef.matchEntire(line)
            if (ref != null) {
                refs.putIfAbsent(ref.groupValues[1].lowercase(), ref.groupValues[2])
                i++
                continue
            }

            // Paragraph, which a setext underline turns into a heading.
            val para = ArrayList<String>()
            para += line.trim()
            i++
            var setext = 0
            while (i < lines.size && !isBlank(lines[i])) {
                val next = lines[i]
                val t = next.trim()
                if (leadingSpaces(next) < 4 && t.isNotEmpty() && t.all { it == '=' }) {
                    setext = 1
                    i++
                    break
                }
                if (leadingSpaces(next) < 4 && t.isNotEmpty() && t.all { it == '-' }) {
                    setext = 2
                    i++
                    break
                }
                if (interruptsParagraph(next)) break
                para += t
                i++
            }
            val text = para.joinToString("\n")
            blocks += if (setext > 0) MdHeading(setext, text.replace('\n', ' ')) else MdParagraph(text)
        }
        return blocks
    }

    /** Parses the list starting at [start] into [out], returning the index after it. */
    private fun parseList(lines: List<String>, start: Int, refs: MutableMap<String, String>, out: MutableList<MdBlock>): Int {
        val first = listMarker.matchEntire(lines[start])!!
        val ordered = first.groupValues[2][0].isDigit()
        val delimiter = first.groupValues[2].last()
        var number = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 0
        val items = ArrayList<MdListItem>()
        var i = start
        while (i < lines.size) {
            val m = listMarker.matchEntire(lines[i]) ?: break
            val marker = m.groupValues[2]
            if (marker[0].isDigit() != ordered || marker.last() != delimiter) break
            val gap = m.groupValues[3]
            val pad = if (gap.length in 1..4) gap.length else 1
            val contentIndent = m.groupValues[1].length + marker.length + pad
            val body = ArrayList<String>()
            val firstContent = if (gap.length > 4) " ".repeat(gap.length - 1) + m.groupValues[4] else m.groupValues[4]
            body += firstContent
            i++
            var lastBlank = false
            while (i < lines.size) {
                val line = lines[i]
                if (isBlank(line)) {
                    body += ""
                    lastBlank = true
                    i++
                    continue
                }
                if (leadingSpaces(line) >= contentIndent) {
                    body += dedent(line, contentIndent)
                } else if (!lastBlank && !interruptsParagraph(line) && !listMarker.matches(line)) {
                    body += line.trim() // lazy continuation
                } else {
                    break
                }
                lastBlank = false
                i++
            }
            // Blank lines after the item belong to the list only if it goes on.
            while (body.size > 1 && body.last().isEmpty()) {
                body.removeAt(body.size - 1)
                i--
            }
            var checked: Boolean? = null
            task.find(body[0])?.let {
                checked = it.groupValues[1] != " "
                body[0] = body[0].substring(it.range.last + 1)
            }
            items += MdListItem(if (ordered) "${number++}$delimiter" else "•", parseLines(body, refs), checked)
            // Skip the blank lines between items.
            var j = i
            while (j < lines.size && isBlank(lines[j])) j++
            if (j < lines.size && listMarker.matches(lines[j])) i = j else break
        }
        out += MdList(ordered, items)
        return i
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '|') {
                cell.append('|')
                k += 2
                continue
            }
            if (c == '|') {
                cells += cell.toString().trim()
                cell.setLength(0)
            } else {
                cell.append(c)
            }
            k++
        }
        cells += cell.toString().trim()
        return cells
    }

    private val refLink = Regex("""\[([^\]]+)](?:\[([^\]]*)])?(?!\()""")

    private fun resolveRefs(block: MdBlock, refs: Map<String, String>): MdBlock {
        fun r(text: String) = refLink.replace(text) { m ->
            val label = m.groupValues[2].ifEmpty { m.groupValues[1] }.lowercase()
            val url = refs[label] ?: return@replace m.value
            "[${m.groupValues[1]}]($url)"
        }
        return when (block) {
            is MdParagraph -> MdParagraph(r(block.text))
            is MdHeading -> block.copy(text = r(block.text))
            is MdQuote -> MdQuote(block.blocks.map { resolveRefs(it, refs) })
            is MdList -> block.copy(items = block.items.map { item -> item.copy(blocks = item.blocks.map { resolveRefs(it, refs) }) })
            is MdTable -> block.copy(header = block.header.map(::r), rows = block.rows.map { row -> row.map(::r) })
            else -> block
        }
    }
}
