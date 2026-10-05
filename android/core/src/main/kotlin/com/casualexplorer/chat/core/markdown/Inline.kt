package com.casualexplorer.chat.core.markdown

/** A run of inline text with one style. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** Set on link text. */
    val url: String? = null,
    /** Image alt text, shown as "Image: alt →". */
    val image: Boolean = false,
)

private data class Style(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val url: String? = null,
)

/**
 * Parses inline markdown: code spans, strong/emphasis with * and _,
 * ~~strikethrough~~, links, images, autolinks, backslash escapes and hard
 * line breaks. Unclosed delimiters (common while a reply streams) stay
 * literal. Soft line breaks become spaces unless [keepNewlines], which user
 * messages use so their typed line breaks show.
 */
fun parseInline(text: String, keepNewlines: Boolean = false): List<InlineSpan> {
    val out = ArrayList<InlineSpan>()
    InlineParser(out, keepNewlines).parse(text, Style())
    // Merge neighbours with the same style.
    val merged = ArrayList<InlineSpan>(out.size)
    for (span in out) {
        val last = merged.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            merged[merged.size - 1] = last.copy(text = last.text + span.text)
        } else if (span.text.isNotEmpty()) {
            merged += span
        }
    }
    return merged
}

private class InlineParser(val out: MutableList<InlineSpan>, val keepNewlines: Boolean) {
    fun parse(s: String, style: Style) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out += InlineSpan(buf.toString(), style.bold, style.italic, style.strike, url = style.url)
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> { // hard break
                    buf.append('\n')
                    i += 2
                }
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCTUATION -> {
                    buf.append(s[i + 1])
                    i += 2
                }
                c == '\n' -> {
                    val hard = buf.endsWith("  ")
                    while (buf.endsWith(" ")) buf.setLength(buf.length - 1)
                    buf.append(if (hard || keepNewlines) '\n' else ' ')
                    i++
                    while (i < s.length && s[i] == ' ') i++
                }
                c == '`' -> {
                    val run = runLength(s, i, '`')
                    val close = findCodeClose(s, i + run, run)
                    if (close < 0) {
                        buf.append(s, i, i + run)
                        i += run
                    } else {
                        flush()
                        var code = s.substring(i + run, close).replace('\n', ' ')
                        if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                            code = code.substring(1, code.length - 1)
                        }
                        out += InlineSpan(code, style.bold, style.italic, style.strike, code = true, url = style.url)
                        i = close + run
                    }
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(s, i, c)
                    val n = when {
                        c == '~' -> if (run >= 2) 2 else 0
                        run >= 2 -> 2
                        else -> 1
                    }
                    val close = if (n == 0) -1 else findEmphasisClose(s, i, n, c)
                    if (close < 0) {
                        buf.append(s, i, i + run)
                        i += run
                    } else {
                        flush()
                        val inner = when {
                            c == '~' -> style.copy(strike = true)
                            n == 2 -> style.copy(bold = true)
                            else -> style.copy(italic = true)
                        }
                        parse(s.substring(i + n, close), inner)
                        i = close + n
                    }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val link = parseLink(s, i + 1)
                    if (link == null) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        out += InlineSpan("Image: ${link.text} →", url = link.url, image = true)
                        i = link.end
                    }
                }
                c == '[' -> {
                    val link = parseLink(s, i)
                    if (link == null) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        parse(link.text, style.copy(url = link.url))
                        i = link.end
                    }
                }
                c == '<' -> {
                    val end = s.indexOf('>', i + 1)
                    val inner = if (end > 0) s.substring(i + 1, end) else ""
                    if (end > 0 && ' ' !in inner && (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:"))) {
                        flush()
                        out += InlineSpan(inner, style.bold, style.italic, style.strike, url = inner)
                        i = end + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private class Link(val text: String, val url: String, val end: Int)

    /** Parses "[text](url)" at [start], or null. */
    private fun parseLink(s: String, start: Int): Link? {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) break
            }
            i++
        }
        if (i >= s.length || i + 1 >= s.length || s[i + 1] != '(') return null
        val close = s.indexOf(')', i + 2)
        if (close < 0) return null
        var url = s.substring(i + 2, close).trim()
        // Drop a title: [text](url "title").
        val space = url.indexOfFirst { it == ' ' || it == '\t' }
        if (space > 0) url = url.substring(0, space)
        url = url.removePrefix("<").removeSuffix(">")
        return Link(s.substring(start + 1, i), url, close + 1)
    }

    private fun runLength(s: String, i: Int, c: Char): Int {
        var j = i
        while (j < s.length && s[j] == c) j++
        return j - i
    }

    private fun findCodeClose(s: String, from: Int, run: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                val r = runLength(s, i, '`')
                if (r == run) return i
                i += r
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * Finds the delimiter run closing the one at [open]: the opener must be
     * followed by a non-space and the closer preceded by one; for '_' they
     * must not sit inside a word.
     */
    private fun findEmphasisClose(s: String, open: Int, n: Int, c: Char): Int {
        val after = open + n
        if (after >= s.length || s[after].isWhitespace()) return -1
        if (c == '_' && open > 0 && s[open - 1].isLetterOrDigit()) return -1
        var i = after + 1
        while (i <= s.length - n) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '`' -> { // skip code spans
                    val run = runLength(s, i, '`')
                    val close = findCodeClose(s, i + run, run)
                    i = if (close < 0) i + run else close + run
                }
                s[i] == c -> {
                    val runEnd = i + runLength(s, i, c)
                    val len = runEnd - i
                    // A closer follows a non-space; for '_' it doesn't sit inside a word.
                    val closes = !s[i - 1].isWhitespace() && len >= n &&
                        (c != '_' || runEnd >= s.length || !s[runEnd].isLetterOrDigit())
                    // A single delimiter skips a nested double one ("*a **b** c*");
                    // otherwise a longer run closes with its last n characters.
                    if (closes && (n == 2 || len != 2)) return runEnd - n
                    i = runEnd
                }
                else -> i++
            }
        }
        return -1
    }

    companion object {
        const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}

/** The visible text of inline markdown, as the reader sees it. */
fun inlinePlainText(text: String): String = parseInline(text).joinToString("") { it.text }
