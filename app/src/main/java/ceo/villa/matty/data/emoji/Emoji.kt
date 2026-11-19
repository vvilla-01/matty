package ceo.villa.matty.data.emoji

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EmojiEntry(
    val unicode: String,
    val names: List<String>,
    val category: String,
    val isSkinVariant: Boolean,
) {
    val name: String get() = names.first()
}

/**
 * Mattermost's emoji set (the same short names the web and desktop apps use, so reactions
 * round-trip: "+1", "heart", "joy"...). Loaded once from assets/emoji.tsv.
 */
object EmojiStore {
    val categories = listOf(
        "smileys-emotion" to "Smileys",
        "people-body" to "People",
        "animals-nature" to "Nature",
        "food-drink" to "Food",
        "activities" to "Activities",
        "travel-places" to "Travel",
        "objects" to "Objects",
        "symbols" to "Symbols",
        "flags" to "Flags",
    )

    @Volatile private var loaded = false
    private var byName: Map<String, EmojiEntry> = emptyMap()
    var all: List<EmojiEntry> = emptyList(); private set
    var pickable: List<EmojiEntry> = emptyList(); private set

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val list = ArrayList<EmojiEntry>(3400)
        context.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@forEach
                val uni = parts[0].split('-').joinToString("") { String(Character.toChars(it.toInt(16))) }
                list += EmojiEntry(uni, parts[1].split(','), parts[2], parts[3] == "1")
            }
        }
        val map = HashMap<String, EmojiEntry>(list.size * 2)
        list.forEach { e -> e.names.forEach { map.putIfAbsent(it, e) } }
        // Common aliases people type that Mattermost also accepts.
        map["thumbsup"]?.let { map.putIfAbsent("+1", it) }
        map["+1"]?.let { map.putIfAbsent("thumbsup", it) }
        map["-1"]?.let { map.putIfAbsent("thumbsdown", it) }
        all = list
        pickable = list.filterNot { it.isSkinVariant }
        byName = map
        loaded = true
    }

    fun unicode(name: String): String? = byName[name]?.unicode

    fun isKnown(name: String) = byName.containsKey(name)

    fun search(query: String, limit: Int = 60): List<EmojiEntry> {
        val q = query.lowercase().trim(':')
        if (q.isEmpty()) return emptyList()
        val prefix = ArrayList<EmojiEntry>()
        val contains = ArrayList<EmojiEntry>()
        for (e in pickable) {
            when {
                e.names.any { it.startsWith(q) } -> prefix += e
                e.names.any { it.contains(q) } -> contains += e
            }
            if (prefix.size >= limit) break
        }
        return (prefix + contains).take(limit)
    }

    /** Emoji-only messages get the Discord "jumbo" treatment. */
    private val shortcode = Regex(":[a-z0-9_+\\-]+:")
    fun isEmojiOnly(text: String, customNames: Set<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 64) return false
        var rest = shortcode.replace(t) { m ->
            val n = m.value.trim(':')
            if (isKnown(n) || n in customNames) "" else m.value
        }
        rest = rest.replace(" ", "")
        if (rest.isEmpty()) return true
        // Remaining must be only emoji code points.
        var i = 0
        while (i < rest.length) {
            val cp = rest.codePointAt(i)
            val isEmojiish = Character.getType(cp) == Character.OTHER_SYMBOL.toInt() ||
                cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0x1F1E6..0x1F1FF || cp in 0xE0020..0xE007F
            if (!isEmojiish) return false
            i += Character.charCount(cp)
        }
        return true
    }
}
