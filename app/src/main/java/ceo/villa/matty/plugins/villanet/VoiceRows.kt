package ceo.villa.matty.plugins.villanet

/*
 * What the voice screens show, worked out apart from drawing it: the channel list's rows, the
 * join sheet's button, the bar, the voice screen's tiles and its notice. Pure, so the tests run
 * it as it is.
 */

/** In a voice channel, or on the way to one: connecting, connected or reconnecting. */
internal val VoiceStatus.inCall: Boolean
    get() = this == VoiceStatus.Connecting || this == VoiceStatus.Connected || this == VoiceStatus.Reconnecting

/** One row of the channel list's voice category: a channel, or somebody in the channel above it. */
internal sealed interface VoiceListRow {
    /** Unique within the category: the list's key. */
    val key: String

    /** A channel; [here] when this phone is in it. */
    data class Channel(val room: VoiceRoom, val here: Boolean, override val key: String) : VoiceListRow

    /** Somebody in [channel], drawn under it at its [depth]. */
    data class Person(val member: VoiceMember, val channel: Int, val depth: Int, override val key: String) : VoiceListRow
}

/**
 * The voice category's rows: every channel in tree order with the people in it under it. While
 * [folded], only the channel this phone is in ([mine]), as a folded category still shows its
 * unread channels. Keys are unique even if the roster names a session twice.
 */
internal fun voiceListRows(rooms: List<VoiceRoom>, folded: Boolean, mine: Int?): List<VoiceListRow> {
    val out = ArrayList<VoiceListRow>()
    val seen = HashSet<String>()
    fun unique(base: String): String {
        var key = base
        var n = 1
        while (!seen.add(key)) key = "$base#${n++}"
        return key
    }
    for (r in rooms) {
        val here = r.id == mine
        if (folded && !here) continue
        out += VoiceListRow.Channel(r, here, unique("voice:ch:${r.id}"))
        for (m in r.people) out += VoiceListRow.Person(m, r.id, r.depth, unique("voice:p:${m.session}"))
    }
    return out
}

/** "2/4" for a channel with a limit; null for one without. */
internal fun voiceCountOf(room: VoiceRoom): String? = if (room.max > 0) "${room.people.size}/${room.max}" else null

/** Who is in a channel, in a few words. */
internal fun voicePeopleWords(room: VoiceRoom): String {
    val who = when (val n = room.people.size) {
        0 -> "Nobody is here yet"
        1 -> "1 person here"
        else -> "$n people here"
    }
    return if (room.full) "$who · Full" else who
}

/** Channel [id]'s name in the roster; null when there is no such channel or it has no name. */
internal fun voiceChannelName(voice: VnVoice?, id: Int?): String? =
    id?.let { i -> voice?.channels?.firstOrNull { it.id == i }?.name?.trim()?.ifEmpty { null } }

/** Where a session stands, in the words the screens use. */
internal fun voiceStatusWords(status: VoiceStatus): String = when (status) {
    VoiceStatus.Idle -> ""
    VoiceStatus.Connecting -> "Connecting…"
    VoiceStatus.Connected -> "Voice Connected"
    VoiceStatus.Reconnecting -> "Reconnecting…"
    is VoiceStatus.Failed -> "Voice Disconnected"
}

/**
 * Where a session stands as the screens and the notification show it: a connected one whose link
 * is down while the core waits to see whether it comes back ([VoiceCore.linkDown]) shows as
 * reconnecting.
 */
internal fun voiceShownStatus(status: VoiceStatus, linkDown: Boolean): VoiceStatus =
    if (status == VoiceStatus.Connected && linkDown) VoiceStatus.Reconnecting else status

// ---- the join sheet --------------------------------------------------------------------------

/**
 * The join sheet's button: none (the server lets nobody talk from Matty), join, move there from
 * another channel, open the voice screen (already in it), a move there on its way, or full.
 */
internal enum class VoiceJoinAction { None, Join, Move, Open, Moving, Full }

/**
 * The join sheet's button for [channel], [full] or not, when the server lets phones talk
 * ([talkOn]) and the session is [status], in [inChannel] with a move to [moving] on its way.
 */
internal fun voiceJoinActionOf(talkOn: Boolean, status: VoiceStatus, inChannel: Int?, moving: Int?, channel: Int, full: Boolean): VoiceJoinAction {
    val inCall = status.inCall
    return when {
        inCall && moving == channel -> VoiceJoinAction.Moving
        inCall && moving == null && inChannel == channel -> VoiceJoinAction.Open
        !talkOn -> VoiceJoinAction.None
        full -> VoiceJoinAction.Full
        inCall -> VoiceJoinAction.Move
        else -> VoiceJoinAction.Join
    }
}

/** What the join sheet's button says; null when there is none. */
internal fun voiceJoinLabel(a: VoiceJoinAction): String? = when (a) {
    VoiceJoinAction.None -> null
    VoiceJoinAction.Join -> "Join Voice"
    VoiceJoinAction.Move -> "Move Here"
    VoiceJoinAction.Open -> "Open Voice"
    VoiceJoinAction.Moving -> "Moving…"
    VoiceJoinAction.Full -> "Channel Full"
}

/**
 * Whether the reader [access] describes may join voice: the server lets phones talk, and the
 * reader may watch (linked or let in), as the voice routes ask.
 */
internal fun voiceMayJoin(access: VnAccess?): Boolean = voiceTalkOn(access) && access?.canWatch == true

/**
 * Why there is no button: the reader must unlock first, or the server's own sentence, or a
 * general one.
 */
internal fun voiceNoTalkWords(access: VnAccess?): String = when {
    voiceTalkOn(access) && access?.canWatch != true -> "Unlock ${nameOf(access)} to join voice."
    else -> access?.voice?.reason?.trim()?.ifEmpty { null } ?: "Talking from Matty isn't available on this server."
}

/**
 * What the join sheet says under Join Voice about the microphone when joining unmuted without it:
 * refused for good, that the person joins muted and Settings is where it is allowed; otherwise
 * that it will be asked for. Null when it is allowed.
 */
internal fun voiceJoinMicWords(mic: MicPermission): VoiceNotice? = when (mic) {
    MicPermission.Granted -> null
    MicPermission.Refused -> VoiceNotice("Matty can't use the microphone, so you'll join muted. Allow it in Settings.", true)
    MicPermission.NotAsked, MicPermission.Declined -> VoiceNotice("Matty will ask to use the microphone when you join.", false)
}

// ---- the bar -----------------------------------------------------------------------------------

/**
 * The bar while in voice: [title] ("Voice Connected", "Connecting…", or that the session ended),
 * [place] (the channel and the server, or the sentence saying why it ended), whether to draw it
 * as connected, and whether it has the microphone and Leave ([controls]) or only a way to put
 * the ended session away.
 */
internal data class VoiceBar(val title: String, val place: String, val connected: Boolean, val controls: Boolean)

/** The bar for a session that is [status], in the channel called [channelName], on the server called [site]; null while not in voice. */
internal fun voiceBarOf(status: VoiceStatus, channelName: String?, site: String): VoiceBar? = when (status) {
    VoiceStatus.Idle -> null
    is VoiceStatus.Failed -> VoiceBar(voiceStatusWords(status), status.sentence, connected = false, controls = false)
    else -> VoiceBar(
        voiceStatusWords(status),
        listOfNotNull(channelName ?: "Voice", site.trim().ifEmpty { null }).joinToString(" · "),
        connected = status == VoiceStatus.Connected,
        controls = true,
    )
}

// ---- the voice screen --------------------------------------------------------------------------

/**
 * The voice screen's tiles: the people in [channel], this phone's own first. Until the join is
 * answered the roster has no seat for this phone, so [me] (drawn from the account) stands in.
 */
internal fun voiceTilesOf(rooms: List<VoiceRoom>, channel: Int?, me: VoiceMember?): List<VoiceMember> {
    var people = rooms.firstOrNull { it.id == channel }?.people.orEmpty()
    if (me != null && people.none { it.me }) {
        // An older phone session of this account, still in the roster, is this one's.
        people = people.filterNot { me.userId.isNotEmpty() && it.phone && it.userId == me.userId } + me
    }
    return people.sortedByDescending { it.me }.distinctBy { it.session }
}

/** A line over the controls: why the microphone is not heard, or why voice waits to join, and whether a tap opens Android's settings. */
internal data class VoiceNotice(val text: String, val toSettings: Boolean)

/**
 * What the voice screen says over its controls, if anything: the server's sentence while a join
 * it refused is waited out ([waiting]), a phone call, the voice server's doing, or the permission.
 */
internal fun voiceNoticeOf(held: Boolean, serverMuted: Boolean, suppressed: Boolean, mic: MicPermission, waiting: String? = null): VoiceNotice? = when {
    waiting != null -> VoiceNotice(waiting, false)
    held -> VoiceNotice("On hold while a phone call has the audio.", false)
    serverMuted -> VoiceNotice("The voice server has muted you.", false)
    suppressed -> VoiceNotice("You can't speak in this channel.", false)
    mic == MicPermission.Refused -> VoiceNotice("Matty can't use the microphone, so you're muted. Allow it in Settings.", true)
    else -> null
}

/** What a person's row or tile reads out: their name, and how they are in voice. */
internal fun voiceMemberWords(m: VoiceMember, speaking: Boolean): String = listOfNotNull(
    m.name,
    "you".takeIf { m.me },
    "speaking".takeIf { speaking },
    "deafened".takeIf { m.deaf },
    "muted".takeIf { m.muted && !m.deaf },
    "on a phone".takeIf { m.phone && !m.me },
).joinToString(", ")
