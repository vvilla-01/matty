package ceo.villa.matty.ui.chat

/**
 * Who an "@name" in a message is, and what is drawn for it. A mention of somebody the app knows
 * is drawn with their name as the app shows names everywhere else; the message itself keeps the
 * username, so typing, editing, copying and quoting are untouched.
 */
object Mentions {
    /** Mentions of everybody at once: nobody to name or open. */
    val special = setOf("channel", "here", "all")

    private val separators = charArrayOf('.', '-', '_')

    // The same name as the Markdown parser's, not after a letter or digit (an email address).
    private val token = Regex("(?<![A-Za-z0-9])@([a-zA-Z0-9][a-zA-Z0-9._\\-]*[a-zA-Z0-9_]|[a-zA-Z0-9])")

    /**
     * The usernames [name] may be a mention of, longest first: the whole token, then, as Mattermost
     * reads it, each part of it before a ".", "-" or "_" ("dave.thanks" is "dave" saying thanks).
     */
    fun candidates(name: String): List<String> {
        val lower = name.lowercase()
        val out = mutableListOf(lower)
        var end = lower.lastIndexOfAny(separators)
        while (end > 0) {
            out += lower.substring(0, end)
            end = lower.lastIndexOfAny(separators, end - 1)
        }
        return out
    }

    /** The username [name] is a mention of, among the ones [isKnown] knows, or null. */
    fun resolve(name: String, isKnown: (String) -> Boolean): String? = candidates(name).firstOrNull(isKnown)

    /**
     * What is drawn for "@[name]": the person's name with [username] to open, and the [rest] of the
     * token as ordinary text; somebody the app doesn't know stays as typed.
     */
    data class Drawn(val username: String?, val label: String, val rest: String)

    /** [labels] maps each known username, in lower case, to the name the app shows for them. */
    fun drawn(name: String, labels: Map<String, String>): Drawn {
        if (name in special) return Drawn(null, name, "")
        val who = resolve(name) { it in labels } ?: return Drawn(null, name, "")
        return Drawn(who, labels.getValue(who), name.substring(who.length))
    }

    /** Every mention in a paragraph's inlines, at any depth. */
    fun names(inlines: List<MdInline>): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(list: List<MdInline>) {
            for (el in list) when (el) {
                is MdInline.Mention -> out += el.name
                is MdInline.Bold -> walk(el.children)
                is MdInline.Italic -> walk(el.children)
                is MdInline.Strike -> walk(el.children)
                is MdInline.Link -> walk(el.children)
                else -> {}
            }
        }
        walk(inlines)
        return out
    }

    /**
     * The usernames to ask the server for so the mentions in [messages] can be named: of each
     * token, every candidate longer than the first one already [isKnown] (so "@dave.thanks" still
     * asks whether "dave.thanks" is somebody before settling on "dave").
     */
    fun unknown(messages: Collection<String>, isKnown: (String) -> Boolean): Set<String> {
        val out = HashSet<String>()
        for (m in messages) {
            if ('@' !in m) continue
            for (t in token.findAll(m)) {
                val name = t.groupValues[1].trimEnd('.')
                if (name.isEmpty() || name in special) continue
                for (c in candidates(name)) { if (isKnown(c)) break; out += c }
            }
        }
        return out
    }
}
