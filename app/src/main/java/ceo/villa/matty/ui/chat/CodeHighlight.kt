package ceo.villa.matty.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Code in VillaNET's colours: its code blocks sit on near-black (#0a0a0c) inside a thin
 * separator-red border, and the tokens take VillaNET's reds (#ff5a6a, #c8102e) with warm
 * peach and coral alongside, so a snippet reads as part of the same app as the streams.
 * The light theme gets the same family, darkened for a pale page.
 */
class CodeTheme(
    val background: Color,
    val border: Color,
    val label: Color,
    val plain: Color,
    val keyword: Color,
    val literal: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val property: Color,
    val annotation: Color,
    val punctuation: Color,
    /** Inline `code` in a sentence. */
    val inlineText: Color,
    val inlineBackground: Color,
) {
    companion object {
        val Dark = CodeTheme(
            background = Color(0xFF0A0A0C),
            border = Color(0xFF3C040D),
            label = Color(0xFFFF5A6A),
            plain = Color(0xFFE6E6EA),
            keyword = Color(0xFFFF5A6A),
            literal = Color(0xFFFF7A85),
            string = Color(0xFFFFB38A),
            number = Color(0xFFFF8F5A),
            comment = Color(0xFF7A7A80),
            type = Color(0xFFFF9AA6),
            function = Color(0xFFFFD6DB),
            property = Color(0xFFE9A3AC),
            annotation = Color(0xFFFF4D63),
            punctuation = Color(0xFF8E8E94),
            inlineText = Color(0xFFFF9AA6),
            inlineBackground = Color(0xFF1A0A0D),
        )
        val Light = CodeTheme(
            background = Color(0xFFFFF6F7),
            border = Color(0xFFF3CDD3),
            label = Color(0xFFC8102E),
            plain = Color(0xFF1C1C20),
            keyword = Color(0xFFC8102E),
            literal = Color(0xFFB0002A),
            string = Color(0xFF9C4A1A),
            number = Color(0xFFB4470F),
            comment = Color(0xFF8A8A8E),
            type = Color(0xFFA3123A),
            function = Color(0xFF5A1A24),
            property = Color(0xFF8F2A3A),
            annotation = Color(0xFFC8102E),
            punctuation = Color(0xFF6E6E74),
            inlineText = Color(0xFFA3123A),
            inlineBackground = Color(0xFFFDEBEE),
        )

        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}

private val keywords = setOf(
    // Kotlin / Java / C-family / Swift
    "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "abstract", "open", "override",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue", "import", "package", "in", "is", "as",
    "public", "private", "protected", "internal", "static", "final", "const", "void", "new", "try", "catch", "finally",
    "throw", "throws", "extends", "implements", "switch", "case", "default", "goto", "sizeof", "typedef", "struct",
    "union", "unsigned", "signed", "int", "long", "short", "char", "float", "double", "bool", "boolean", "byte",
    "string", "suspend", "inline", "operator", "companion", "init", "lateinit", "where", "by", "get", "set",
    "func", "guard", "let", "protocol", "extension", "self", "Self", "super", "this",
    // JS / TS
    "function", "async", "await", "yield", "export", "from", "typeof", "instanceof", "delete", "of", "type",
    "readonly", "declare", "namespace", "keyof",
    // Python / Ruby
    "def", "lambda", "pass", "with", "global", "nonlocal", "assert", "raise", "except", "elif", "and", "or", "not",
    "end", "module", "require", "unless", "until", "then", "begin", "rescue", "ensure",
    // Go / Rust
    "go", "chan", "defer", "select", "map", "range", "fallthrough", "fn", "impl", "pub", "use", "mod", "match",
    "mut", "ref", "trait", "crate", "dyn", "move", "loop", "unsafe",
    // Shell
    "fi", "esac", "done", "echo", "export", "local", "source",
)
private val literals = setOf("true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN", "Infinity")
private val sqlKeywords = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "join", "left", "right", "inner",
    "outer", "on", "group", "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null", "is", "in",
    "create", "table", "index", "drop", "alter", "add", "primary", "key", "foreign", "references", "distinct", "union",
    "case", "when", "then", "else", "end", "exists", "like", "between", "count", "sum", "avg", "min", "max", "asc", "desc",
)
private val hashLangs = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "fish", "yaml", "yml", "ruby", "rb", "toml", "conf", "dockerfile",
    "make", "makefile", "r", "perl", "pl", "powershell", "ps1", "nix", "cmake", "ini", "properties", "",
)
private val dashLangs = setOf("sql", "lua", "haskell", "hs", "elm")
/** Only C-family-like languages have block comments; elsewhere a slash then a star (a shell glob) is a path. */
private val blockCommentLangs = setOf(
    "c", "cpp", "c++", "h", "hpp", "java", "kotlin", "kt", "kts", "js", "javascript", "ts", "typescript", "jsx", "tsx",
    "css", "scss", "less", "go", "rust", "rs", "swift", "cs", "csharp", "scala", "php", "dart", "groovy", "gradle",
    "jsonc", "objc", "sql",
)
/** Blocks that are meant to stay plain: logs, terminal output, prose. */
private val plainLangs = setOf("text", "txt", "plain", "plaintext", "nohighlight", "output", "log")
private val hashOnly = setOf("python", "py", "bash", "sh", "shell", "zsh", "fish", "yaml", "yml", "ruby", "rb", "toml", "r", "perl", "pl", "nix")
private val markupLangs = setOf("html", "xml", "svg", "vue", "jsx", "tsx")
/** Languages where '…' is one character ('x', '\n'), not a string, and 'a can be a Rust lifetime. */
private val charLangs = setOf("rust", "rs", "c", "cpp", "c++", "h", "java", "kotlin", "kt", "go", "cs", "csharp", "scala", "swift")
private const val PUNCT = "{}()[];,.:=+-*/<>!&|?%^~"
private val numberToken = Regex("0[xXbB][0-9a-fA-F_]+[uUlL]*|\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?[fFdDlLuU]*")

/**
 * A small highlighter that knows no grammar but reads like one in any common language:
 * comments, strings (and JSON keys), numbers, keywords, literals, types, function calls,
 * annotations, markup tags and punctuation.
 */
fun highlightCode(code: String, lang: String, t: CodeTheme): AnnotatedString = buildAnnotatedString {
    append(code)
    addStyle(SpanStyle(color = t.plain), 0, code.length)
    if (code.length > 20_000) return@buildAnnotatedString
    val l = lang.lowercase().trim()
    if (l in plainLangs) return@buildAnnotatedString
    val hash = l in hashLangs
    val block = l in blockCommentLangs
    val prose = l == "" // an unlabelled block may be a log or a sentence: stay conservative
    val slash = l !in hashOnly && l != "sql"
    val dash = l in dashLangs
    val sql = l == "sql" || l == "mysql" || l == "postgres" || l == "postgresql" || l == "sqlite"
    val markup = l in markupLangs
    val chars = l in charLangs
    val backticks = l in setOf("js", "javascript", "ts", "typescript", "jsx", "tsx", "go", "sh", "bash", "shell", "kotlin", "kt", "")
    fun style(color: Color, from: Int, to: Int, italic: Boolean = false, bold: Boolean = false) =
        addStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null, fontWeight = if (bold) FontWeight.Medium else null), from, to)
    fun lineEnd(from: Int) = code.indexOf('\n', from).let { if (it < 0) code.length else it }
    fun identEnd(from: Int): Int { var j = from; while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++; return j }
    if (l == "diff" || l == "patch") {
        // Whole lines: added, removed, file headers and hunk markers.
        var s = 0
        while (s < code.length) {
            val e = lineEnd(s)
            when {
                code.startsWith("@@", s) -> style(t.comment, s, e, italic = true)
                code.startsWith("+++", s) || code.startsWith("---", s) -> style(t.label, s, e, bold = true)
                code.startsWith("+", s) -> style(t.string, s, e)
                code.startsWith("-", s) -> style(t.keyword, s, e)
            }
            s = e + 1
        }
        return@buildAnnotatedString
    }
    var i = 0
    val n = code.length
    while (i < n) {
        val ch = code[i]
        val prev = if (i > 0) code[i - 1] else '\n'
        when {
            block && code.startsWith("/*", i) -> {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                style(t.comment, i, end, italic = true); i = end
            }
            // Not after ':' (that's a URL: https://…).
            slash && code.startsWith("//", i) && prev != ':' -> { val end = lineEnd(i); style(t.comment, i, end, italic = true); i = end }
            // At a line start or after a space: never $# or ${#arr[@]} in a shell script.
            hash && ch == '#' && prev.isWhitespace() -> { val end = lineEnd(i); style(t.comment, i, end, italic = true); i = end }
            dash && code.startsWith("--", i) && prev.isWhitespace() -> { val end = lineEnd(i); style(t.comment, i, end, italic = true); i = end }
            markup && code.startsWith("<!--", i) -> {
                val end = code.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                style(t.comment, i, end, italic = true); i = end
            }
            code.startsWith("\"\"\"", i) || code.startsWith("'''", i) -> {
                val q = code.substring(i, i + 3)
                val end = code.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
                style(t.string, i, end); i = end
            }
            chars && ch == '\'' -> {
                // A character literal: one character or one escape between the quotes. Anything
                // else is a lifetime ('a) or a stray quote, never the start of a long "string".
                val end = when {
                    i + 2 < n && code[i + 1] != '\\' && code[i + 2] == '\'' -> i + 3
                    i + 1 < n && code[i + 1] == '\\' -> code.indexOf('\'', i + 2).takeIf { it in (i + 2)..(i + 11) }?.plus(1)
                    else -> null
                }
                if (end != null) { style(t.string, i, end); i = end }
                else if (i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) { val e = identEnd(i + 1); style(t.type, i, e); i = e }
                else i++
            }
            // An apostrophe inside a word (it's, don't) isn't a quote; f'…', r'…' and b'…' are.
            ch == '\'' && prev.isLetterOrDigit() && !(prev in "fFrRbBuU" && (i < 2 || !code[i - 2].isLetterOrDigit())) -> i++
            ch == '"' || ch == '\'' || (ch == '`' && backticks) -> {
                // A quote with no partner on its line (an apostrophe, a Rust lifetime) is just a character.
                var j = i + 1
                var closed = false
                while (j < n && (code[j] != '\n' || ch == '`')) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == ch) { closed = true; break }
                    j++
                }
                if (!closed) { i++; continue }
                val end = j + 1
                // A JSON (or YAML / dict) key: a string followed by a colon.
                var k = end
                while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
                style(if (k < n && code[k] == ':' && ch == '"') t.property else t.string, i, end)
                i = end
            }
            ch.isDigit() && !(prev.isLetterOrDigit() || prev == '_') -> {
                val m = numberToken.find(code, i)
                val end = if (m != null && m.range.first == i) m.range.last + 1 else i + 1
                style(t.number, i, end); i = end
            }
            ch == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_') -> {
                val end = identEnd(i + 1); style(t.annotation, i, end); i = end
            }
            markup && ch == '<' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '/') -> {
                val start = if (code[i + 1] == '/') i + 2 else i + 1
                val end = identEnd(start).let { e -> var x = e; while (x < n && (code[x] == '-' || code[x] == ':' || code[x].isLetterOrDigit())) x++; x }
                style(t.punctuation, i, start); style(t.keyword, start, end); i = end
            }
            ch.isLetter() || ch == '_' || ch == '$' -> {
                val end = identEnd(i)
                val word = code.substring(i, end)
                var k = end
                while (k < n && code[k] == ' ') k++
                val next = if (k < n) code[k] else ' '
                when {
                    word in literals -> style(t.literal, i, end)
                    (sql && word.lowercase() in sqlKeywords) || (!sql && word in keywords) ||
                        (l == "" && word.length > 2 && word.all { it.isUpperCase() } && word.lowercase() in sqlKeywords) -> style(t.keyword, i, end, bold = true)
                    next == '(' -> style(t.function, i, end)
                    markup && next == '=' -> style(t.property, i, end)
                    !prose && word.length > 1 && word.all { it.isUpperCase() || it == '_' || it.isDigit() } && word.any { it.isLetter() } -> style(t.number, i, end)
                    !prose && word.first().isUpperCase() -> style(t.type, i, end)
                }
                i = end
            }
            ch in PUNCT -> { style(t.punctuation, i, i + 1); i++ }
            else -> i++
        }
    }
}
