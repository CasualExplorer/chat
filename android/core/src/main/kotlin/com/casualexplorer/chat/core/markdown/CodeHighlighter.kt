package com.casualexplorer.chat.core.markdown

enum class TokenKind { Plain, Keyword, Type, Function, String, Number, Comment, Operator, Punctuation }

data class CodeToken(val text: String, val kind: TokenKind)

/**
 * A small lexer for code blocks: comments, strings, numbers, keywords, type
 * names (capitalised identifiers) and function calls, with the comment and
 * keyword rules picked by the fence's language. It never fails; anything it
 * doesn't recognise is plain text.
 */
object CodeHighlighter {
    private val cLike = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return",
        "goto", "try", "catch", "finally", "throw", "throws", "new", "delete", "class", "struct", "interface",
        "enum", "union", "extends", "implements", "public", "private", "protected", "internal", "static",
        "final", "const", "var", "val", "let", "fun", "func", "function", "fn", "def", "import", "package",
        "from", "as", "is", "in", "of", "typeof", "instanceof", "void", "null", "nil", "true", "false",
        "this", "self", "super", "async", "await", "yield", "go", "defer", "chan", "select", "range", "type",
        "map", "impl", "trait", "mut", "pub", "use", "mod", "crate", "match", "where", "loop", "when",
        "object", "override", "open", "abstract", "data", "sealed", "companion", "suspend", "inline",
        "operator", "export", "extern", "unsafe", "namespace", "using", "template", "typename", "virtual",
        "readonly", "declare", "module", "lambda", "with", "pass", "raise", "except", "global", "nonlocal",
        "and", "or", "not", "elif", "None", "True", "False", "guard", "protocol", "extension", "init",
        "Some", "Ok", "Err", "undefined", "constructor", "get", "set",
    )
    private val shell = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
        "function", "return", "local", "export", "echo", "exit", "cd", "set", "unset", "source", "alias",
    )
    private val sql = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
        "drop", "alter", "add", "join", "left", "right", "inner", "outer", "on", "group", "by", "order",
        "having", "limit", "offset", "as", "and", "or", "not", "null", "is", "in", "like", "distinct",
        "union", "all", "primary", "key", "foreign", "references", "index", "default", "case", "when",
        "then", "else", "end", "with", "returning",
    )

    private val hashComments = setOf(
        "python", "py", "sh", "bash", "zsh", "shell", "console", "ruby", "rb", "yaml", "yml", "toml",
        "perl", "r", "dockerfile", "makefile", "make", "conf", "ini", "nix", "elixir", "ex",
    )
    private val dashComments = setOf("sql", "lua", "haskell", "hs")

    fun tokenize(code: String, language: String): List<CodeToken> {
        val lang = language.lowercase()
        val keywords = when {
            lang in setOf("sh", "bash", "zsh", "shell", "console") -> shell
            lang == "sql" -> sql
            lang in setOf("json", "text", "txt", "plain", "markdown", "md") -> emptySet()
            else -> cLike
        }
        val caseInsensitive = lang == "sql"
        val hash = lang in hashComments
        val slash = !hash && lang !in dashComments && lang != "json"
        val dash = lang in dashComments
        val tripleQuotes = lang in setOf("python", "py", "kotlin", "kt", "swift", "scala")
        val backticks = lang in setOf("js", "javascript", "ts", "typescript", "jsx", "tsx", "go", "golang")

        val out = ArrayList<CodeToken>()
        fun add(text: String, kind: TokenKind) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = CodeToken(last.text + text, kind) else out += CodeToken(text, kind)
        }
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                slash && code.startsWith("//", i) || hash && c == '#' || dash && code.startsWith("--", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    add(code.substring(i, end), TokenKind.Comment)
                    i = end
                }
                slash && code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    add(code.substring(i, end), TokenKind.Comment)
                    i = end
                }
                tripleQuotes && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) -> {
                    val q = code.substring(i, i + 3)
                    val end = code.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
                    add(code.substring(i, end), TokenKind.String)
                    i = end
                }
                c == '"' || c == '\'' || backticks && c == '`' -> {
                    var j = i + 1
                    while (j < n && code[j] != c && (c == '`' || code[j] != '\n')) {
                        if (code[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, n)
                    add(code.substring(i, end), TokenKind.String)
                    i = end
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    add(code.substring(i, j), TokenKind.Number)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' || c == '@' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
                    val word = code.substring(i, j)
                    var k = j
                    while (k < n && code[k] == ' ') k++
                    val kind = when {
                        (if (caseInsensitive) word.lowercase() else word) in keywords -> TokenKind.Keyword
                        k < n && code[k] == '(' -> TokenKind.Function
                        word[0].isUpperCase() && keywords.isNotEmpty() -> TokenKind.Type
                        else -> TokenKind.Plain
                    }
                    add(word, kind)
                    i = j
                }
                c in "+-*/%=<>!&|^~?:" -> {
                    add(c.toString(), TokenKind.Operator)
                    i++
                }
                c in "{}()[];,." -> {
                    add(c.toString(), TokenKind.Punctuation)
                    i++
                }
                else -> {
                    add(c.toString(), TokenKind.Plain)
                    i++
                }
            }
        }
        return out
    }
}
