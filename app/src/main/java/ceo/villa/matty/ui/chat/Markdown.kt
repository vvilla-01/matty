package ceo.villa.matty.ui.chat

/*
 * A small Mattermost-flavoured Markdown parser. It produces a block tree; inline text is kept
 * as a list of spans the renderer turns into an AnnotatedString. Covers what people actually
 * type in chat: emphasis, code, links, autolinks, @mentions, ~channels, :emoji:, #hashtags,
 * headings, quotes, lists, tables, rules, fenced and indented code, and images (GIFs) as their own blocks.
 */

sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>, val tasks: List<Boolean?>) : MdBlock
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>, val align: List<Char>) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val code: String) : MdInline
    data class Link(val url: String, val children: List<MdInline>) : MdInline
    data class Mention(val name: String) : MdInline
    data class ChannelLink(val name: String) : MdInline
    data class Emoji(val name: String) : MdInline
    data class Hashtag(val tag: String) : MdInline
    data object LineBreak : MdInline
}

object Markdown {
    private val fence = Regex("^\\s{0,3}(```|~~~)\\s*([\\w+#.-]*)\\s*$")
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val imageOnly = Regex("^\\s*!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)\\s*$")

    /** Leading whitespace in columns, a tab reaching the next multiple of four (as CommonMark counts it). */
    private fun indentOf(l: String): Int {
        var c = 0
        for (ch in l) { if (ch == ' ') c++ else if (ch == '\t') c += 4 - c % 4 else break }
        return c
    }

    /** Takes [cols] columns of indent off [l]; a tab only partly taken leaves the rest of it as spaces. */
    private fun dropIndent(l: String, cols: Int): String {
        var c = 0
        var k = 0
        while (k < l.length && c < cols) { val ch = l[k]; if (ch == ' ') c++ else if (ch == '\t') c += 4 - c % 4 else break; k++ }
        return " ".repeat((c - cols).coerceAtLeast(0)) + l.substring(k)
    }

    private fun indentedCode(line: String) = line.isNotBlank() && indentOf(line) >= 4
    private fun unindent(line: String) = dropIndent(line, 4)

    fun parse(text: String): List<MdBlock> = parseBlocks(text.replace("\r\n", "\n").split('\n'))

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var i = 0
        val para = ArrayList<String>()
        fun flush() {
            if (para.isEmpty()) return
            // Pull standalone images out of the paragraph so they render as media.
            val textLines = ArrayList<String>()
            for (l in para) {
                val m = imageOnly.matchEntire(l)
                if (m != null) {
                    if (textLines.isNotEmpty()) { out += MdBlock.Paragraph(parseInlines(textLines.joinToString("\n"))); textLines.clear() }
                    out += MdBlock.Image(m.groupValues[1], m.groupValues[2])
                } else textLines += l
            }
            if (textLines.isNotEmpty()) out += MdBlock.Paragraph(parseInlines(textLines.joinToString("\n")))
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val marker = f.groupValues[1]
                val lang = f.groupValues[2]
                // An indented fence (in a list item, say) takes that much indent off its lines too.
                val indent = line.length - line.trimStart().length
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    val l = lines[i]
                    body += l.drop(minOf(indent, l.length - l.trimStart(' ').length))
                    i++
                }
                i++ // closing fence (or end)
                out += MdBlock.Code(lang, body.joinToString("\n"))
                continue
            }
            if (line.isBlank()) { flush(); i++; continue }
            // An indented code block (four spaces or a tab), as /code posts it. It can't break into
            // a paragraph: there, an indented line just carries on the text.
            if (para.isEmpty() && indentedCode(line)) {
                val body = ArrayList<String>()
                while (i < lines.size && (indentedCode(lines[i]) || lines[i].isBlank())) { body += unindent(lines[i]); i++ }
                while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
                out += MdBlock.Code("", body.joinToString("\n"))
                continue
            }
            val h = heading.matchEntire(line)
            if (h != null) {
                flush()
                out += MdBlock.Heading(h.groupValues[1].length, parseInlines(h.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line) && para.isEmpty()) { flush(); out += MdBlock.Rule; i++; continue }
            if (line.trimStart().startsWith(">")) {
                flush()
                val q = ArrayList<String>()
                var lazy = false
                while (i < lines.size) {
                    val l = lines[i]
                    q += when {
                        l.trimStart().startsWith(">") -> { lazy = false; l.trimStart().removePrefix(">").removePrefix(" ") }
                        // An indented line carrying on the quote's paragraph stays in the quote, as
                        // text: kept indented, so it can't start a heading, list, fence or code there.
                        indentedCode(l) && q.isNotEmpty() && q.last().isNotBlank() && (lazy || !indentedCode(q.last())) -> { lazy = true; l }
                        else -> break
                    }
                    i++
                }
                out += MdBlock.Quote(parseBlocks(q))
                continue
            }
            if (bullet.matches(line) || ordered.matches(line)) {
                flush()
                val isOrdered = ordered.matches(line) && !bullet.matches(line)
                val start = if (isOrdered) ordered.matchEntire(line)!!.groupValues[2].toIntOrNull() ?: 1 else 1
                val baseIndent = indentOf(line)
                val items = ArrayList<ArrayList<String>>()
                // The current item's marker and its space: 2 for "- ", 3 for "1. ", 4 for "10. ".
                var width = 2
                while (i < lines.size) {
                    val l = lines[i]
                    val ind = indentOf(l)
                    val mb = bullet.matchEntire(l)
                    val mo = ordered.matchEntire(l)
                    val m = mb ?: mo
                    if (m != null && ind <= baseIndent + 1) {
                        items += arrayListOf(m.groupValues[3])
                        width = if (mb != null) 2 else mo!!.groupValues[2].length + 2
                        i++
                    } else if (l.isNotBlank() && items.isNotEmpty() && (ind > baseIndent || m == null && !l.trimStart().startsWith(">"))) {
                        // Continuation or nested list: keep it with the current item, de-indented. Text
                        // indented past "1. " but short of code there would read as code once only two
                        // columns came off: the marker's own width comes off instead (spaces only).
                        val cols = if (ind - baseIndent in 6 until width + 4 && ind == l.length - l.trimStart(' ').length) baseIndent + width
                        else minOf(ind, baseIndent + 2)
                        items.last() += if (ind > baseIndent) dropIndent(l, cols) else l.trim()
                        i++
                    } else if (l.isBlank() && items.isNotEmpty()) {
                        // A blank line inside an item: the item carries on when what follows is
                        // indented into it (a nested list, a second paragraph, its own code).
                        var j = i
                        while (j < lines.size && lines[j].isBlank()) j++
                        val next = lines.getOrNull(j)
                        if (next != null && indentOf(next) >= baseIndent + 2) {
                            while (i < j) { items.last() += ""; i++ }
                        } else break
                    } else break
                }
                val tasks = items.map { lines0 -> task.matchEntire(lines0.first())?.groupValues?.get(1)?.let { it != " " } }
                val parsed = items.mapIndexed { idx, ls ->
                    val first = if (tasks[idx] != null) task.matchEntire(ls.first())!!.groupValues[2] else ls.first()
                    parseBlocks(listOf(first) + ls.drop(1))
                }
                out += MdBlock.ListBlock(isOrdered, start, parsed, tasks)
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flush()
                val header = splitRow(line)
                val align = splitRow(lines[i + 1]).map { c ->
                    val t = c.trim()
                    when { t.startsWith(":") && t.endsWith(":") -> 'c'; t.endsWith(":") -> 'r'; else -> 'l' }
                }
                i += 2
                val rows = ArrayList<List<List<MdInline>>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i]).map { parseInlines(it.trim()) }
                    i++
                }
                out += MdBlock.Table(header.map { parseInlines(it.trim()) }, rows, align)
                continue
            }
            para += line
            i++
        }
        flush()
        return out
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(Regex("(?<!\\\\)\\|")).map { it.replace("\\|", "|") }
    }

    // ---- inlines ---------------------------------------------------------------------------

    private val urlChars = Regex("^(https?://|www\\.|mailto:)[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val mention = Regex("^@([a-zA-Z0-9][a-zA-Z0-9._\\-]*[a-zA-Z0-9_]|[a-zA-Z0-9])")
    private val channel = Regex("^~([a-z0-9][a-z0-9_\\-]*)")
    private val emoji = Regex("^:([a-zA-Z0-9_+\\-]+):")
    private val hashtag = Regex("^#([\\p{L}][\\p{L}\\p{N}_.\\-]{2,})")

    fun parseInlines(src: String): List<MdInline> {
        val out = ArrayList<MdInline>()
        val buf = StringBuilder()
        fun text() { if (buf.isNotEmpty()) { out += MdInline.Text(buf.toString()); buf.clear() } }
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val prev = if (i > 0) src[i - 1] else ' '
            val boundary = !prev.isLetterOrDigit()
            when {
                c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!~|>@:".contains(src[i + 1]) -> { buf.append(src[i + 1]); i += 2 }
                c == '\n' -> { text(); out += MdInline.LineBreak; i++ }
                c == '`' -> {
                    val ticks = src.drop(i).takeWhile { it == '`' }.length
                    val close = src.indexOf("`".repeat(ticks), i + ticks)
                    if (close > 0) {
                        text(); out += MdInline.Code(src.substring(i + ticks, close).trim()); i = close + ticks
                    } else { buf.append(c); i++ }
                }
                (c == '*' || c == '_') && i + 1 < n && src[i + 1] == c && (c == '*' || boundary) -> {
                    val marker = "$c$c"
                    val close = findClose(src, marker, i + 2)
                    if (close > i + 2) {
                        text(); out += MdInline.Bold(parseInlines(src.substring(i + 2, close))); i = close + 2
                    } else { buf.append(marker); i += 2 }
                }
                (c == '*' || c == '_') && (c == '*' || boundary) && i + 1 < n && !src[i + 1].isWhitespace() -> {
                    val close = findClose(src, c.toString(), i + 1)
                    val afterOk = close > 0 && (c == '*' || close + 1 >= n || !src[close + 1].isLetterOrDigit())
                    if (close > i + 1 && afterOk) {
                        text(); out += MdInline.Italic(parseInlines(src.substring(i + 1, close))); i = close + 1
                    } else { buf.append(c); i++ }
                }
                c == '~' && i + 1 < n && src[i + 1] == '~' -> {
                    val close = src.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        text(); out += MdInline.Strike(parseInlines(src.substring(i + 2, close))); i = close + 2
                    } else { buf.append("~~"); i += 2 }
                }
                c == '~' && boundary && channel.find(src.substring(i))?.let { m ->
                    text(); out += MdInline.ChannelLink(m.groupValues[1]); i += m.value.length; true
                } == true -> {}
                c == '!' && i + 1 < n && src[i + 1] == '[' -> {
                    val link = parseLink(src, i + 1)
                    if (link != null) {
                        text(); out += MdInline.Link(link.second, listOf(MdInline.Text(link.first.ifBlank { "image" }))); i = link.third
                    } else { buf.append(c); i++ }
                }
                c == '[' -> {
                    val link = parseLink(src, i)
                    if (link != null) {
                        text(); out += MdInline.Link(link.second, parseInlines(link.first)); i = link.third
                    } else { buf.append(c); i++ }
                }
                c == '<' && src.indexOf('>', i).let { e -> e > i && urlChars.containsMatchIn(src.substring(i + 1, e)) } -> {
                    val e = src.indexOf('>', i)
                    val url = src.substring(i + 1, e)
                    text(); out += MdInline.Link(url, listOf(MdInline.Text(url))); i = e + 1
                }
                boundary && (c == 'h' || c == 'H' || c == 'w' || c == 'W' || c == 'm') && urlChars.find(src.substring(i))?.let { m ->
                    var url = m.value
                    // Trailing punctuation is almost never part of the link.
                    while (url.isNotEmpty() && url.last() in ".,;:!?\"')]}") {
                        if (url.last() == ')' && url.count { it == '(' } >= url.count { it == ')' }) break
                        url = url.dropLast(1)
                    }
                    text(); out += MdInline.Link(if (url.startsWith("www.", true)) "https://$url" else url, listOf(MdInline.Text(url)))
                    i += url.length
                    true
                } == true -> {}
                c == '@' && boundary && mention.find(src.substring(i))?.let { m ->
                    var name = m.groupValues[1]
                    // "@bob." at a sentence end: the dot belongs to the sentence.
                    while (name.endsWith(".")) name = name.dropLast(1)
                    text(); out += MdInline.Mention(name); i += 1 + name.length; true
                } == true -> {}
                c == ':' && emoji.find(src.substring(i))?.let { m ->
                    text(); out += MdInline.Emoji(m.groupValues[1]); i += m.value.length; true
                } == true -> {}
                c == '#' && boundary && hashtag.find(src.substring(i))?.let { m ->
                    var tag = m.groupValues[1]
                    while (tag.endsWith(".") || tag.endsWith("-")) tag = tag.dropLast(1)
                    text(); out += MdInline.Hashtag(tag); i += 1 + tag.length; true
                } == true -> {}
                else -> { buf.append(c); i++ }
            }
        }
        text()
        return out
    }

    private fun findClose(src: String, marker: String, from: Int): Int {
        var j = from
        while (true) {
            val k = src.indexOf(marker, j)
            if (k < 0) return -1
            if (k > 0 && src[k - 1] == '\\') { j = k + 1; continue }
            if (k > from && src[k - 1].isWhitespace()) { j = k + 1; continue }
            // For single markers don't match the first half of a double.
            if (marker.length == 1 && k + 1 < src.length && src[k + 1] == marker[0]) { j = k + 2; continue }
            return k
        }
    }

    /** Parses `[text](url)` at [start]; returns text, url, and the index after it. */
    private fun parseLink(src: String, start: Int): Triple<String, String, Int>? {
        if (src.getOrNull(start) != '[') return null
        var depth = 0
        var j = start
        while (j < src.length) {
            when (src[j]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
                '\n' -> return null
            }
            j++
        }
        if (j >= src.length || src.getOrNull(j + 1) != '(') return null
        val close = src.indexOf(')', j + 2)
        if (close < 0) return null
        var url = src.substring(j + 2, close).trim()
        val sp = url.indexOf(' ')
        if (sp > 0) url = url.substring(0, sp)
        url = url.removePrefix("<").removeSuffix(">")
        if (url.isEmpty()) return null
        return Triple(src.substring(start + 1, j), url, close + 1)
    }

    /** Plain text for previews and notifications. */
    fun plain(text: String, emojiLookup: (String) -> String?): String = buildString {
        fun walk(list: List<MdInline>) {
            list.forEach { el ->
                when (el) {
                    is MdInline.Text -> append(el.text)
                    is MdInline.Bold -> walk(el.children)
                    is MdInline.Italic -> walk(el.children)
                    is MdInline.Strike -> walk(el.children)
                    is MdInline.Code -> append(el.code)
                    is MdInline.Link -> walk(el.children)
                    is MdInline.Mention -> append('@').append(el.name)
                    is MdInline.ChannelLink -> append('~').append(el.name)
                    is MdInline.Emoji -> append(emojiLookup(el.name) ?: ":${el.name}:")
                    is MdInline.Hashtag -> append('#').append(el.tag)
                    MdInline.LineBreak -> append(' ')
                }
            }
        }
        parse(text).forEach { b ->
            if (isNotEmpty()) append(' ')
            when (b) {
                is MdBlock.Paragraph -> walk(b.inlines)
                is MdBlock.Heading -> walk(b.inlines)
                is MdBlock.Code -> append(b.code.lineSequence().firstOrNull().orEmpty())
                is MdBlock.Quote -> append("“…”")
                is MdBlock.ListBlock -> append("• …")
                is MdBlock.Table -> append("[table]")
                is MdBlock.Image -> append("GIF")
                MdBlock.Rule -> {}
            }
        }
    }.trim()
}
