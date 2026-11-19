package ceo.villa.matty.service

import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.RequestDeadline
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.api.str
import ceo.villa.matty.data.runCatchingCancellable
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.push.PushPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Who a message notification belongs to, the same for the live connection and for a push. The
 * tag is the saved server's id, so two servers, or two accounts on one server, never share a
 * notification; the id comes from the conversation: the thread's root for a reply under collapsed
 * threads, the channel otherwise. Every PendingIntent carries [data], which counts when Android
 * tells two PendingIntents apart (extras do not), so one server's intents never overwrite
 * another's.
 */
object NoteIdentity {
    const val SCHEME = "matty-notification"

    /** The conversation a post is shown in: [threadRoot] is the root of a collapsed thread, or "". */
    fun conversation(channelId: String, threadRoot: String): String = threadRoot.ifEmpty { channelId }

    fun tag(serverId: String): String = serverId

    fun id(conversation: String): Int = conversation.hashCode()

    fun data(serverId: String, conversation: String): String = "$SCHEME://$serverId/$conversation"

    /** The tag of a server's notices (a working test, a session that ended): kept when its messages go. */
    fun noticeTag(serverId: String): String = "$NOTICE$serverId"

    fun isNotice(tag: String?): Boolean = tag?.startsWith(NOTICE) == true

    private const val NOTICE = "notice:"
}

/** A picture to load for a notification, with the headers the server it is on gets (its token, or none). */
class Avatar(val url: String, val headers: Map<String, String>) {
    override fun toString() = "Avatar"
}

/** What one message notification says, from the live connection or from a push. */
data class MessageNote(
    val serverId: String,
    /** The saved server's own account: the "you" of the conversation. */
    val me: String,
    val channelId: String,
    /** The root of the collapsed thread the post is a reply in, or "". */
    val threadRoot: String,
    val postId: String,
    val senderId: String,
    val senderName: String,
    /** The conversation's title; null for a direct message. */
    val title: String?,
    val group: Boolean,
    val text: String,
    val at: Long,
    val silent: Boolean = false,
    val avatar: Avatar? = null,
    /** The team the conversation is in, or one of the person's for a direct or group message ("": not known). */
    val team: String = "",
) {
    val conversation: String get() = NoteIdentity.conversation(channelId, threadRoot)

    val head: NoteHead get() = NoteHead(serverId, me, channelId, threadRoot, team, title, group)
}

/**
 * What a conversation's notification is about, apart from its lines: enough to post it again
 * with its tap and its actions built afresh.
 */
data class NoteHead(
    val serverId: String,
    val me: String,
    val channelId: String,
    val threadRoot: String,
    val team: String,
    val title: String?,
    val group: Boolean,
) {
    val conversation: String get() = NoteIdentity.conversation(channelId, threadRoot)
}

/** A conversation's title (null for a direct message) and whether it is a group conversation. */
data class NoteKind(val title: String?, val group: Boolean)

/** One small piece of text kept in the app's private preferences for whichever process comes next. */
interface Kept {
    fun read(): String?
    fun write(text: String)
}

/** A map of keys to times, as [Kept] holds it. */
internal object KeptTimes {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    fun encode(map: Map<String, Long>): String = json.encodeToString(serializer, map)

    /** In the order written; nothing for text that isn't such a map. */
    fun decode(text: String?): Map<String, Long> = text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}

/**
 * The posts already shown, by (saved server, post): the live connection and a push can both
 * bring the same post, and only the first shows it. Checked and added in one step. The last
 * [max] are kept, each for a day, in [kept] when there is one, so that a push delivered again to
 * a new process does not show a post a second time.
 */
class ShownPosts(
    private val max: Int = 500,
    private val kept: Kept? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var seen: LinkedHashMap<String, Long>? = null

    private fun loaded(): LinkedHashMap<String, Long> {
        val map = seen ?: LinkedHashMap(KeptTimes.decode(runCatching { kept?.read() }.getOrNull())).also { seen = it }
        val oldest = now() - KEPT_MS
        map.entries.removeAll { it.value < oldest }
        return map
    }

    /** Whether [postId] of [serverId] was shown already, without counting this as showing it. */
    @Synchronized
    fun seen(serverId: String, postId: String): Boolean = postId.isNotEmpty() && "$serverId/$postId" in loaded()

    /** True the first time [postId] of [serverId] is asked about (an empty id always shows). */
    @Synchronized
    fun firstShow(serverId: String, postId: String): Boolean {
        if (postId.isEmpty()) return true
        val map = loaded()
        val key = "$serverId/$postId"
        if (key in map) return false
        map[key] = now()
        while (map.size > max) map.remove(map.keys.first())
        kept?.let { k -> runCatching { k.write(KeptTimes.encode(map)) } }
        return true
    }

    companion object {
        /** A message older than a day is not shown at all, so a day is as long as one needs remembering. */
        const val KEPT_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * The lines each conversation's notification was last posted with, and what it is about, in
 * memory: read before the notification showing, which can lag behind a post just made. Bounded:
 * the conversations posted to longest ago are forgotten first.
 */
class LastLines<L>(private val max: Int = 64, private val now: () -> Long = System::currentTimeMillis) {
    /**
     * [at]: when it was posted here (0: read back from the notification showing). [shownAt]: when
     * a message was last shown in it from here; Matty's own posts of it again (the person's reply,
     * "Couldn't send") keep the earlier time (0: none).
     */
    class Entry<L>(val head: NoteHead, val lines: List<L>, val at: Long, val shownAt: Long = 0L)

    /** What is known of a conversation's notification here. */
    sealed interface Recall<out L> {
        /** Posted from this process: these are its lines. */
        class Here<L>(val entry: Entry<L>) : Recall<L>
        /** Posted from this process, then dismissed by the person: it starts afresh. */
        data object Dismissed : Recall<Nothing>
        /** Not posted from this process: only the notification showing can tell. */
        data object Unknown : Recall<Nothing>
    }

    private val map = object : LinkedHashMap<String, Entry<L>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<L>>?) = size > max
    }

    /** [key] was posted with [lines]; [shown]: to show a message, not Matty's own post of it again. */
    @Synchronized
    fun put(key: String, head: NoteHead, lines: List<L>, shown: Boolean = false) {
        val at = now()
        map[key] = Entry(head, lines, at, shownAt = if (shown) at else map[key]?.shownAt ?: 0L)
    }

    /**
     * [key]'s lines. [showing] is whether its notification is among the active ones: one posted
     * moments ago may not be there yet, but one posted longer ago and gone was dismissed.
     */
    @Synchronized
    fun recall(key: String, showing: Boolean): Recall<L> {
        val entry = map[key] ?: return Recall.Unknown
        if (showing || now() - entry.at <= SETTLE_MS) return Recall.Here(entry)
        map.remove(key)
        return Recall.Dismissed
    }

    @Synchronized
    fun forget(key: String) { map.remove(key) }

    @Synchronized
    fun forgetWhere(test: (String) -> Boolean) { map.keys.removeAll(test) }

    companion object {
        /** Longer than Android takes to list a notification just posted among the active ones. */
        const val SETTLE_MS = 2_000L
    }
}

/** The rules a notification follows that need no Android to decide. */
object NoteRules {
    /** Said in a conversation's notification when Reply or Mark as read did not reach the server. */
    const val COULD_NOT_SEND = "Couldn't send"

    /** Who says [COULD_NOT_SEND]: Matty, not the person, so it never reads as their own message. */
    const val NOTICE_KEY = "matty:notice"
    const val NOTICE_NAME = "Matty"

    /**
     * Android's mark on a notification the person replied from (Notification's hidden
     * FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY). While it is there the app's cancel is ignored;
     * the app's next post of the notification takes it off.
     */
    const val HELD_BY_REPLY = 0x00010000

    /** Notification.FLAG_ONLY_ALERT_ONCE. */
    private const val ONLY_ALERT_ONCE = 0x00000008

    fun heldByReply(flags: Int): Boolean = flags and HELD_BY_REPLY != 0

    /** The flags to post a held notification again with: quietly, and not held. */
    fun released(flags: Int): Int = (flags and HELD_BY_REPLY.inv()) or ONLY_ALERT_ONCE

    /** One notification, as [stayGone] sees it. */
    interface Showing {
        /** Its flags, or null while it isn't showing. */
        fun flags(): Int?
        /** Posts it again, quietly, so that Android lets go of it ([released]). */
        suspend fun release()
        fun cancel()
    }

    /**
     * Cancels a notification the person replied from, and sees that it stays gone. Android
     * ignores the cancel while it holds the notification, and moments after a reply it can put
     * the held version back (seen on Android 17): so each time it is there it is let go of when
     * held and cancelled again, until it has stayed away [away] looks in a row, [stepMs] apart,
     * or [tries] looks were spent (3 seconds by default).
     */
    suspend fun stayGone(note: Showing, tries: Int = 30, away: Int = 5, stepMs: Long = 100) {
        var missing = 0
        repeat(tries) {
            val flags = note.flags()
            if (flags == null) {
                if (++missing >= away) return
            } else {
                missing = 0
                if (heldByReply(flags)) note.release()
                note.cancel()
            }
            delay(stepMs)
        }
    }

    /** Whether a line read back from a notification is Matty's [COULD_NOT_SEND], which is never carried on. */
    fun isNotice(text: CharSequence?, personKey: String?): Boolean = personKey == NOTICE_KEY && text?.toString() == COULD_NOT_SEND

    /**
     * Whether the notification a reply or a mark was sent from stays after it: this process
     * showed a message in it ([postedAt], [LastLines.Entry.shownAt]; null when nothing is
     * showing) since the action began ([since]), so it holds a message the person has not seen.
     */
    fun keptAfterReply(postedAt: Long?, since: Long): Boolean = postedAt != null && postedAt >= since

    /**
     * The time a thread is marked read up to: the newest line its notification showed, in the
     * server's time ([shownAt]); the phone's [now] only when none is known (0).
     */
    fun readUpTo(shownAt: Long, now: Long): Long = if (shownAt > 0) shownAt else now

    /**
     * Whose a message notification of [serverId] is, said under it while more than one server is
     * saved ([saved]): the server's name, and the account before it when another saved server
     * has that name too; when that one's account has the same username as well (both without
     * case), the server is named by its address's host instead. Null with one server.
     */
    fun whose(serverId: String, saved: List<SavedServer>): String? {
        if (saved.size < 2) return null
        val server = saved.firstOrNull { it.id == serverId } ?: return null
        val twins = saved.filter { it.id != server.id && it.name.equals(server.name, ignoreCase = true) }
        if (twins.isEmpty()) return server.name
        val where = if (twins.any { it.username.equals(server.username, ignoreCase = true) }) host(server.url) else server.name
        return "@${server.username} · $where"
    }

    /**
     * The title of the "Signed out" notice of [server]: its name, and the account after it when
     * another saved server ([saved]) has that name too.
     */
    fun signedOutTitle(server: SavedServer, saved: List<SavedServer>): String {
        val twin = saved.any { it.id != server.id && it.name.equals(server.name, ignoreCase = true) }
        return if (twin) "Signed out of ${server.name} (@${server.username})" else "Signed out of ${server.name}"
    }

    private fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.ifEmpty { null } ?: url

    /**
     * A live message's title and kind when the live session does not know its channel yet (a
     * direct message just made, say): from the `posted` event's own [channelType] and
     * [displayName]. A direct message ("D") has no title and is not a group; a group message
     * ("G"), and anything else, is a group under the event's name for it.
     */
    fun unknownChannel(channelType: String?, displayName: String?): NoteKind = when (channelType) {
        "D" -> NoteKind(title = null, group = false)
        else -> NoteKind(title = displayName.orEmpty(), group = true)
    }

    /** Whether a "Signed out" notice about [noticeAddress] is about the server at [address] (compared without case). */
    fun sameAddress(noticeAddress: String?, address: String): Boolean =
        noticeAddress != null && noticeAddress.trimEnd('/').equals(address.trimEnd('/'), ignoreCase = true)

    /**
     * The thread whose notification goes for live event [e], seen by [me]: a thread of theirs
     * read (here or on another device) with no reply left unread. Null for anything else.
     */
    fun threadRead(e: WsEvent, me: String): String? {
        if (e.event != "thread_read_changed" || me.isEmpty() || e.userId != me) return null
        if (e.data.str("unread_replies")?.toLongOrNull() != 0L) return null
        return e.data.str("thread_id")?.ifEmpty { null }
    }
}

/** How many lines of a conversation a notification shows. */
const val NOTE_LINES = 6

/** The lines a notification shows: the latest of [earlier], then [new], [max] in all. */
fun <T> noteLines(earlier: List<T>, new: T, max: Int = NOTE_LINES): List<T> = earlier.takeLast(max - 1) + new

/** Notifications built from a push, which carries everything a notification shows (or the ids to fetch it by). */
object PushNotes {
    /** What Mattermost's own push says for a sender it doesn't name (id-loaded mode). */
    const val SOMEONE = "Someone"

    /**
     * The notification for [m], for the saved server [serverId] at [serverUrl] signed in as [me]:
     * the payload's own title, sender and text, with Mattermost's "sender: " lead taken off the
     * text when the sender is named beside it. [avatarHeaders] are the server's own (its token).
     */
    fun note(
        serverId: String, serverUrl: String, me: String, m: PushPayload.Message, silent: Boolean,
        avatarHeaders: Map<String, String> = emptyMap(),
    ): MessageNote {
        val sender = m.senderName.trim().trimStart('@').ifEmpty { SOMEONE }
        val text = m.text.let { t ->
            val lead = m.senderName.trim() + ": "
            if (m.senderName.isNotBlank() && t.startsWith(lead)) t.removePrefix(lead) else t
        }.trim().ifEmpty { "New message" }
        val threadRoot = if (m.crt) m.root else ""
        val title = when (m.channelType) {
            "D" -> null
            "G" -> m.channelName.ifBlank { null }
            // Under collapsed threads Mattermost puts the thread's own title there.
            else -> m.channelName.ifBlank { null }?.let { if (threadRoot.isNotEmpty()) it else "#$it" }
        }
        return MessageNote(
            serverId = serverId, me = me, channelId = m.channel, threadRoot = threadRoot, postId = m.post,
            senderId = m.sender, senderName = sender, title = title, group = m.channelType != "D",
            text = text, at = m.at, silent = silent, team = m.team,
            avatar = m.sender.takeIf { it.isNotEmpty() }?.let {
                Avatar("${serverUrl.trimEnd('/')}/api/v4/users/$it/image?_=0", avatarHeaders)
            },
        )
    }

    /** How long an id-loaded push may spend asking the server: a push has seconds in all. */
    const val ID_LOADED_MS = 4_000L

    /**
     * An id-loaded push filled in from the server: the post and its channel, asked side by side,
     * then the post's sender, read through [client], all within [budgetMs]. Anything that can't
     * be read in time leaves [m] as it came (Mattermost's generic sentence and no sender) or the
     * part it belongs to unnamed. A request still on the wire when time is up ends by the
     * client's own call timeout (or the caller's [RequestDeadline]), unwaited for.
     */
    suspend fun idLoaded(client: MattermostClient, m: PushPayload.Message, budgetMs: Long = ID_LOADED_MS): PushPayload.Message {
        if (!m.idLoaded) return m
        val until = System.nanoTime() + budgetMs * 1_000_000
        // At least a moment: an answer already in is still taken once the time is up.
        fun left() = ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        // Not this coroutine's children: a blocking call is never waited for past the budget. Its
        // requests are held to this coroutine's deadline all the same, when it has one.
        val asks = CoroutineScope(SupervisorJob() + Dispatchers.IO + (currentCoroutineContext()[RequestDeadline] ?: EmptyCoroutineContext))
        val post = asks.async { runCatchingCancellable { client.post(m.post) }.getOrNull() }
        val channel = asks.async { runCatchingCancellable { client.channel(m.channel) }.getOrNull() }
        val got = withTimeoutOrNull(left()) { post.await() } ?: return m.also { channel.cancel() }
        val user = asks.async { runCatchingCancellable { client.user(got.userId) }.getOrNull() }
        val where = withTimeoutOrNull(left()) { channel.await() }
        val sender = withTimeoutOrNull(left()) { user.await() }
        user.cancel(); channel.cancel()
        return filled(m, got, sender, where)
    }

    /** [m] with what the server said about its post, sender and channel. */
    fun filled(m: PushPayload.Message, post: Post, user: User?, channel: Channel?): PushPayload.Message {
        val name = user?.displayName?.ifBlank { null }
        val text = post.message.trim().ifEmpty { if (post.fileIds.isNotEmpty()) "Sent an attachment" else "" }
        return m.copy(
            sender = post.userId,
            senderName = name ?: m.senderName,
            channelName = channel?.let { if (it.type == "D") name.orEmpty() else it.displayName } ?: m.channelName,
            channelType = channel?.type ?: m.channelType,
            text = text.ifEmpty { m.text },
        )
    }
}
