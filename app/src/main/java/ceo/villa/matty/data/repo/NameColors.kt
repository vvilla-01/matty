package ceo.villa.matty.data.repo

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ceo.villa.matty.data.api.User

/**
 * The colours of "Colorful names", twenty for each theme, made in OKLCH at one lightness and
 * chroma per half (chroma only lowered where a hue would leave sRGB). The first five are 72° of
 * hue apart, the first ten 36°; the second ten sit half way between those and clearly darker, so
 * none reads as its neighbour. Dark: at least 6.7:1 on black; light: at least 4.9:1 on white.
 * (Made with a small OKLCH script: dark L 0.80 / 0.68, C 0.14 / 0.16; light L 0.53 / 0.42,
 * C 0.15 / 0.13; hues from 25°.)
 */
object NamePalette {
    val dark = longArrayOf(
        0xFFFFA098, 0xFFD7BD44, 0xFF46DAAE, 0xFF78C7FF, 0xFFDDA2FE,
        0xFFFEA757, 0xFF9AD170, 0xFF07D6E7, 0xFFADB8FF, 0xFFFF97CC,
        0xFFE7723E, 0xFF96A200, 0xFF06AFA4, 0xFF5897F9, 0xFFCF6FC5,
        0xFFC68C01, 0xFF36B362, 0xFF05A8D1, 0xFFA181EF, 0xFFE76887,
    )
    val light = longArrayOf(
        0xFFB2403D, 0xFF7E6B01, 0xFF007F61, 0xFF0273AC, 0xFF894DA8,
        0xFF9D5800, 0xFF477D01, 0xFF037A84, 0xFF585FC0, 0xFFA74079,
        0xFF812F01, 0xFF4C5200, 0xFF015A54, 0xFF1B4A92, 0xFF732C6D,
        0xFF664601, 0xFF025D2B, 0xFF01566C, 0xFF543A8B, 0xFF842541,
    )
    const val SIZE = 20
}

/**
 * Everybody in [people] without a colour in [assigned] is given one: the next colour nobody has
 * yet, oldest account first (then by id; bots and deactivated accounts after the people they come
 * with), so the first twenty people are all different; once all are taken, the least used,
 * lowest index first. A colour once given is never changed. The result
 * is [assigned] itself when there was nobody new.
 */
fun handOutNameColors(assigned: Map<String, Int>, people: Collection<User>, size: Int = NamePalette.SIZE): Map<String, Int> {
    // Placeholders (somebody known only by id) wait until they're known, with their account's age.
    val newcomers = people.filter { it.username.isNotEmpty() && it.id !in assigned }
    if (newcomers.isEmpty()) return assigned
    val used = IntArray(size)
    assigned.values.forEach { if (it in 0 until size) used[it]++ }
    val out = HashMap(assigned)
    // People first: a server's bots are usually its oldest accounts, and would take the colours
    // that are furthest apart before anybody who talks. They and accounts that have been
    // deactivated still get one each, after everybody else of the same batch.
    newcomers.sortedWith(compareBy<User>({ it.isBot || it.deleteAt != 0L }, { it.createAt }, { it.id })).forEach { u ->
        var pick = 0
        for (i in 1 until size) if (used[i] < used[pick]) pick = i
        used[pick]++
        out[u.id] = pick
    }
    return out
}

/** The colour of somebody with nothing handed out yet (a webhook's made-up id, or before the saved ones are read). */
fun fallbackNameColor(userId: String, size: Int = NamePalette.SIZE): Int = (userId.hashCode() and 0x7fffffff) % size

/** Stored as "id=index,id=index": user ids are letters and digits. */
fun encodeNameColors(assigned: Map<String, Int>): String = assigned.entries.joinToString(",") { "${it.key}=${it.value}" }

fun decodeNameColors(raw: String?, size: Int = NamePalette.SIZE): Map<String, Int> =
    raw.orEmpty().split(',').mapNotNull { part ->
        val id = part.substringBefore('=', "")
        val i = part.substringAfter('=', "").toIntOrNull()
        if (id.isEmpty() || i == null || i !in 0 until size) null else id to i
    }.toMap()

/** Who has which colour on the server in use, for every name drawn; names recolour when it changes. */
object NameColors {
    var assigned: Map<String, Int> by mutableStateOf(emptyMap())
}

/** One server's handed-out colours, kept on the device (wiped with the rest at sign-out). */
class NameColorStore(private val prefs: SharedPreferences, private val serverId: String) {
    fun read(): Map<String, Int> = decodeNameColors(prefs.getString(serverId, null))
    fun write(assigned: Map<String, Int>) { prefs.edit().putString(serverId, encodeNameColors(assigned)).apply() }
}
