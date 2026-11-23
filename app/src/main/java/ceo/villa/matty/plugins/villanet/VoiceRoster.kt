package ceo.villa.matty.plugins.villanet

/**
 * One voice channel as a screen draws it: its place in the tree ([depth]), its limit ([max], 0
 * for none) and the people in it, in the server's order.
 */
data class VoiceRoom(
    val id: Int,
    val name: String,
    val depth: Int,
    val max: Int,
    val people: List<VoiceMember>,
) {
    /** At its limit: nobody else gets in. */
    val full: Boolean get() = max > 0 && people.size >= max
}

/**
 * Somebody in a voice channel. [userId] is their Mattermost account when they have one here
 * (draw its avatar), "" otherwise (draw [name]'s initial); [name] is that account's name, or
 * their name on the voice server. [speaking] is known only in the channel this phone is in;
 * [me] marks this phone's own seat.
 */
data class VoiceMember(
    val session: Long,
    val userId: String,
    val name: String,
    val muted: Boolean,
    val deaf: Boolean,
    val phone: Boolean,
    val speaking: Boolean,
    val me: Boolean,
)

/**
 * The voice channels in tree order with the people in each, from the snapshot's [voice] roster.
 * [accountName] names a Mattermost account (null when this app doesn't know it), so that people
 * who are somebody here show as that person.
 *
 * This phone's own [seat] is placed at once, from the join's answer and the server's word since,
 * before any snapshot has it, in the channel it is in now and as [muted] and [deafened] as the
 * others hear it now (see [VoiceSession.seenMuted]): a snapshot is up to two seconds behind.
 * Any other entry for the same session, or another phone session of the same account (one voice
 * session per account), is left out. [talking] are the sessions being heard on this phone; this
 * phone's own ring shows only while it is not [muted].
 *
 * Empty when the server is not connected to its voice server, or (before 4.3.0) names no channels.
 */
fun voiceRoomsOf(
    voice: VnVoice?,
    accountName: (String) -> String?,
    seat: VoiceSeat? = null,
    muted: Boolean = false,
    deafened: Boolean = false,
    talking: Set<Long> = emptySet(),
): List<VoiceRoom> {
    if (voice == null || !voice.connected || voice.channels.isEmpty()) return emptyList()
    fun named(userId: String, fallback: String): String =
        userId.takeIf { it.isNotEmpty() }?.let(accountName)?.trim()?.ifEmpty { null } ?: fallback
    val byChannel = LinkedHashMap<Int, MutableList<VoiceMember>>()
    for (p in voice.people) {
        if (seat != null && (p.session == seat.session || (p.phone && seat.userId.isNotEmpty() && p.userId == seat.userId))) continue
        byChannel.getOrPut(p.channel) { ArrayList() } += VoiceMember(
            session = p.session,
            userId = p.userId,
            name = named(p.userId, p.name),
            muted = p.muted || p.deaf,
            deaf = p.deaf,
            phone = p.phone,
            speaking = seat != null && p.session in talking,
            me = false,
        )
    }
    if (seat != null) {
        byChannel.getOrPut(seat.channel) { ArrayList() } += VoiceMember(
            session = seat.session,
            userId = seat.userId,
            name = named(seat.userId, seat.name),
            muted = muted || deafened,
            deaf = deafened,
            phone = true,
            // Shown muted, the ring never says this phone is heard.
            speaking = seat.session in talking && !muted && !deafened,
            me = true,
        )
    }
    return voice.channels.map { c -> VoiceRoom(c.id, c.name, c.depth.coerceAtLeast(0), c.max.coerceAtLeast(0), byChannel[c.id].orEmpty()) }
}
