package ceo.villa.matty.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import ceo.villa.matty.data.api.Post
import kotlinx.coroutines.launch

/**
 * What the reader has had on screen of a thread or a channel: the newest reply or message that was
 * on screen, by its [id], and [at], the server's time it stands at (see [serverTimeAt]).
 */
internal data class SeenReply(val id: String, val at: Long)

/** One of the reader's own messages as the phone wrote it, which the server has not given back: its time is the phone's. */
private val Post.onItsWay: Boolean get() = pendingPostId.isNotEmpty() && pendingPostId == id

/**
 * The server's time the message at [index] of [replies] (a thread's replies or a channel's messages,
 * newest first) stands at: its own, or for one of the reader's own that the server has not given
 * back, that of the newest message the server gave at or above it in the list. The phone's clock
 * never comes into it.
 */
internal fun serverTimeAt(replies: List<Post>, index: Int): Long {
    for (i in index until replies.size) if (!replies[i].onItsWay) return replies[i].createAt
    return 0L
}

/**
 * The server's time [seen] stands at in [replies]: that of the message itself, or of the one that
 * took its place (the reader's own message as the server gave it back, whose pendingPostId is its
 * id), and otherwise the time it was seen at. It is never earlier than the time it was seen at: a
 * message that was on screen stays seen when the server gives the reader's own back with an earlier
 * time.
 */
private fun markOf(replies: List<Post>, seen: SeenReply): Long {
    val reply = replies.firstOrNull { it.id == seen.id } ?: replies.firstOrNull { it.pendingPostId == seen.id }
    return if (reply != null && !reply.onItsWay) maxOf(reply.createAt, seen.at) else seen.at
}

/**
 * How many messages by others in [replies], a list sorted by time (a thread's replies), are newer
 * than the newest one the reader has had on screen ([seen]); none before anything was. The reader's
 * own never count, nor deleted ones, nor system messages (somebody joined or left, a header changed),
 * as the "NEW" line of [buildRows] leaves them out; an edit leaves a message's time as it was. A
 * channel counts by place instead ([newMessagesBelow]).
 */
internal fun newRepliesBelow(replies: List<Post>, seen: SeenReply?, myId: String): Int {
    if (seen == null) return 0
    val mark = markOf(replies, seen)
    return replies.count { it.countsFor(myId) && it.createAt > mark }
}

/**
 * [seen] once the message whose id is [onScreen] has been on screen, with every message above it,
 * in a list sorted by time (a thread). It only moves towards the newest message: scrolling back up
 * the list leaves it where it was.
 */
internal fun seenWith(replies: List<Post>, seen: SeenReply?, onScreen: String?): SeenReply? {
    val i = replies.indexOfFirst { it.id == onScreen }
    if (i < 0) return seen
    val now = SeenReply(replies[i].id, serverTimeAt(replies, i))
    return if (seen == null || now.at >= markOf(replies, seen)) now else seen
}

/** Whether a message can count as new for the reader [myId]: somebody else's, not deleted, not a system message. */
private fun Post.countsFor(myId: String): Boolean = userId != myId && deleteAt == 0L && !isSystem

/**
 * Where [seen] is in [messages]: the index of the message itself, or of the one that took its place
 * (the reader's own message as the server gave it back), or -1 when it is no longer in the list.
 */
private fun placeOf(messages: List<Post>, seen: SeenReply): Int {
    val i = messages.indexOfFirst { it.id == seen.id }
    return if (i >= 0) i else messages.indexOfFirst { it.pendingPostId == seen.id }
}

/**
 * [newRepliesBelow] for a list kept in the order the phone received its messages (a channel): how
 * many messages by others stand below the newest one the reader has had on screen ([seen]), by
 * their place in the list. A message that reached the phone late stands where it arrived, whatever
 * its time, and the reader's own message keeps its place when the server gives it back. When the
 * seen message is no longer in the list (deleted, or the list replaced whole), the count is of the
 * messages newer than the time it was seen at.
 */
internal fun newMessagesBelow(messages: List<Post>, seen: SeenReply?, myId: String): Int {
    if (seen == null) return 0
    val place = placeOf(messages, seen)
    return if (place >= 0) messages.subList(0, place).count { it.countsFor(myId) }
    else messages.count { it.countsFor(myId) && it.createAt > seen.at }
}

/**
 * [seenWith] for a list kept in the order the phone received its messages (a channel): [seen] once
 * the message whose id is [onScreen] has been on screen, with every message above it. It only
 * moves towards the newest message, by place in the list, and by time when the seen message is no
 * longer in the list.
 */
internal fun seenByPlace(messages: List<Post>, seen: SeenReply?, onScreen: String?): SeenReply? {
    val i = messages.indexOfFirst { it.id == onScreen }
    if (i < 0) return seen
    val now = SeenReply(messages[i].id, serverTimeAt(messages, i))
    if (seen == null) return now
    val place = placeOf(messages, seen)
    return if (if (place >= 0) i <= place else now.at >= seen.at) now else seen
}

/** The words of a thread's pill for [count] new replies. */
internal fun newRepliesWords(count: Int): String = when {
    count == 1 -> "1 new reply"
    count > 99 -> "99+ new replies"
    else -> "$count new replies"
}

/** The words of a channel's pill for [count] new messages. */
internal fun newMessagesWords(count: Int): String = when {
    count == 1 -> "1 new message"
    count > 99 -> "99+ new messages"
    else -> "$count new messages"
}

/**
 * A thread's count of new replies, or a channel's of new messages, below what the reader has seen,
 * for its pill, in the words [wordsFor] gives. It is written only where the list's state is known:
 * when the list has decided whether to follow a new newest message ([decided]), and when its first
 * visible message or its number of items moves ([moved]). While a new newest message waits for that
 * decision nothing is written, so a message the list is about to follow never shows in the count.
 * A list sorted by time (a thread) counts by time; one kept in the order the phone received its
 * messages (a channel, [byPlace]) counts by place in the list ([newMessagesBelow]).
 */
@Stable
internal class NewReplies(
    private val myId: String, private val wordsFor: (Int) -> String = ::newRepliesWords, private val byPlace: Boolean = false,
) {
    /** How many messages by others are below what the reader has seen. */
    var count by mutableIntStateOf(0)
        private set
    /** The pill's words for the last count above none, kept while the pill fades out. */
    var words by mutableStateOf("")
        private set
    /** The list's messages, newest first, as the list has them now. */
    internal var replies: List<Post> = emptyList()
    private var seen: SeenReply? = null
    private var decided: String? = null

    /** The list has decided on [newest]: [followed] it is seen, as everything is once the list is down there. */
    fun decided(newest: Post, followed: Boolean, onScreen: String?) {
        decided = newest.id
        if (followed) seen = seenOnce(newest.id)
        update(onScreen)
    }

    /** The list's first visible message is now the one whose id is [onScreen]. */
    fun moved(onScreen: String?) = update(onScreen)

    private fun update(onScreen: String?) {
        // A new newest message waits for the list's decision. A list with nothing in it has nothing below.
        val newest = replies.firstOrNull()
        if (newest != null && newest.id != decided) return
        seen = seenOnce(onScreen)
        count = if (byPlace) newMessagesBelow(replies, seen, myId) else newRepliesBelow(replies, seen, myId)
        if (count > 0) words = wordsFor(count)
    }

    private fun seenOnce(onScreen: String?): SeenReply? =
        if (byPlace) seenByPlace(replies, seen, onScreen) else seenWith(replies, seen, onScreen)
}

/** The key of the item at [LazyListState.firstVisibleItemIndex], as the list last laid it out. */
internal fun LazyListState.firstVisibleKey(): Any? {
    val first = firstVisibleItemIndex
    return layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.key
}

/**
 * The conversation keyed [key]'s count of new messages, kept for its list [state] and [rows] and
 * worded by [wordsFor]: only what is a row of the list counts, by time, or by place in the list
 * with [byPlace] (see [NewReplies]). The list's decision on a new newest message goes to
 * [NewReplies.decided] (see [FollowNewest]).
 */
@Composable
internal fun rememberNewReplies(
    key: Any?, state: LazyListState, rows: List<MessageRowInfo>, myId: String,
    wordsFor: (Int) -> String = ::newRepliesWords, byPlace: Boolean = false,
): NewReplies {
    val newReplies = remember(key) { NewReplies(myId, wordsFor, byPlace) }
    val replies = remember(rows) { rows.map { it.post } }
    SideEffect { newReplies.replies = replies }
    LaunchedEffect(state, newReplies) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.totalItemsCount }
            .collect { newReplies.moved(state.firstVisibleKey() as? String) }
    }
    return newReplies
}

/**
 * The thread's pill for the replies below: the channel's pill in look, saying how many there are.
 * A tap glides to the newest reply. For a screen reader it is a button, and a polite live region,
 * so it is spoken when it shows and when its number changes.
 */
@Composable
internal fun NewRepliesPill(newReplies: NewReplies, state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    NewestPill(
        newReplies.count > 0, newReplies.words, modifier,
        actionLabel = "Jump to the newest reply", role = Role.Button, announce = true,
    ) { scope.launch { state.glideToPresent() } }
}
