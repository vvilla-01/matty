package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.plugins.PersonFacts
import ceo.villa.matty.plugins.UserActivity
import ceo.villa.matty.plugins.VoiceState

/**
 * What VillaNET knows about everybody, by user id, as the rest of the app asks for it: on the
 * air, in voice, whether the custom status beside their name is VillaNET's badge, and what
 * they're doing.
 *
 * All of it is looked up, none of it worked out. The first three are read straight from the
 * snapshot's people index, and what somebody is doing ("Playing ELDEN RING", "Streaming ELDEN
 * RING") from everyone's accounts ([VnAccounts]). VillaNET decides each once, on the server, where
 * everything they depend on is known, and every client (its web panel, this app) only draws the
 * answer, so the two can never disagree about who is live or what a stream is of. Nothing here
 * reads the cards or the voice roster, puts Steam's game beside a stream's tag, or compares the
 * names of games.
 *
 * What somebody is doing is only for a reader who may watch: the server sends it to nobody else
 * (part of it is Steam's), and for a locked reader it's left out whatever was fetched before the
 * lock (VillaNET's rule). [name] is what the server calls the plugin: the source of a title
 * somebody named in What's on?.
 */
internal fun personsOf(snapshot: VnSnapshot?, accounts: VnAccounts?, canWatch: Boolean, name: String = PRODUCT_NAME): Map<String, PersonFacts> {
    val index = snapshot?.people.orEmpty()
    val doing = if (canWatch) accounts?.people.orEmpty() else emptyMap()
    if (index.isEmpty() && doing.isEmpty()) return emptyMap()

    val out = HashMap<String, PersonFacts>(index.size + doing.size)
    for ((id, p) in index) {
        val facts = PersonFacts(
            live = p.live.isNotEmpty(),
            voice = when (p.voice) { VOICE_OPEN -> VoiceState.Open; VOICE_MUTED -> VoiceState.Muted; else -> null },
            activity = doing[id]?.activity?.let { activityOf(it, name) },
            holdsStatus = p.badge == BADGE_LIVE,
        )
        if (facts != PersonFacts.None) out[id] = facts
    }
    for ((id, person) in doing) {
        if (id !in index) person.activity?.let { activityOf(it, name) }?.let { out[id] = PersonFacts(activity = it) }
    }
    return out
}

/**
 * VillaNET's line about somebody as the rest of the app holds it. A kind this app doesn't know
 * (one a newer VillaNET adds) is nothing to show, rather than a guess at what it means. A title
 * named in the panel's own What's on? is said to come from [name], the server's name for it.
 */
internal fun activityOf(a: VnActivity, name: String = PRODUCT_NAME): UserActivity? {
    val kind = when (a.kind) {
        ACTIVITY_PLAYING -> UserActivity.Kind.Playing
        ACTIVITY_STREAMING -> UserActivity.Kind.Streaming
        else -> return null
    }
    val source = when (a.source) {
        "steam" -> "Steam"
        ACTIVITY_FROM_PANEL -> name
        "twitch" -> "Twitch"
        else -> ""
    }
    return UserActivity(kind, a.title, source)
}

/** Whether VillaNET version [have] ("3.4.0"; "" when unknown) is older than [want]. Unknown counts as older. */
internal fun olderThan(have: String, want: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() }
    val h = parts(have)
    val w = parts(want)
    if (h.any { it == null } || w.any { it == null }) return true
    for (i in 0 until maxOf(h.size, w.size)) {
        val a = h.getOrNull(i) ?: 0
        val b = w.getOrNull(i) ?: 0
        if (a != b) return a < b
    }
    return false
}

internal const val VOICE_OPEN = "open"
internal const val VOICE_MUTED = "muted"
internal const val BADGE_LIVE = "live"
internal const val BADGE_BIRTHDAY = "birthday"
internal const val ACTIVITY_PLAYING = "playing"
internal const val ACTIVITY_STREAMING = "streaming"
/** `activity.source` for a title somebody named in the panel's What's on?. */
internal const val ACTIVITY_FROM_PANEL = "panel"
