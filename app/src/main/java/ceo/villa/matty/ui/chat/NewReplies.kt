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
 * What the reader has had on screen of a thread: the newest reply that was on screen, by its [id],
 * and [at], the server's time it stands at (see [serverTimeAt]).
 */
internal data class SeenReply(val id: String, val at: Long)

/** One of the reader's own replies as the phone wrote it, which the server has not given back: its time is the phone's. */
private val Post.onItsWay: Boolean get() = pendingPostId.isNotEmpty() && pendingPostId == id

/**
 * The server's time the reply at [index] of [replies] (newest first) stands at: its own, or for one
 * of the reader's own that the server has not given back, that of the newest reply the server gave
 * at or above it in the list. The phone's clock never comes into it.
 */
internal fun serverTimeAt(replies: List<Post>, index: Int): Long {
    for (i in index until replies.size) if (!replies[i].onItsWay) return replies[i].createAt
    return 0L
}

/**
 * The server's time [seen] stands at in [replies]: that of the reply itself, or of the one that
 * took its place (the reader's own reply as the server gave it back, whose pendingPostId is its id),
 * and otherwise the time it was seen at. It is never earlier than the time it was seen at: a reply
 * that was on screen stays seen when the server gives the reader's own back with an earlier time.
 */
private fun markOf(replies: List<Post>, seen: SeenReply): Long {
    val reply = replies.firstOrNull { it.id == seen.id } ?: replies.firstOrNull { it.pendingPostId == seen.id }
    return if (reply != null && !reply.onItsWay) maxOf(reply.createAt, seen.at) else seen.at
}

/**
 * How many replies by others in [replies] are newer than the newest reply the reader has had on
 * screen ([seen]); none before anything was. The reader's own never count, nor deleted ones, and an
 * edit leaves a reply's time as it was.
 */
internal fun newRepliesBelow(replies: List<Post>, seen: SeenReply?, myId: String): Int {
    if (seen == null) return 0
    val mark = markOf(replies, seen)
    return replies.count { it.userId != myId && it.deleteAt == 0L && it.createAt > mark }
}

/**
 * [seen] once the reply whose id is [onScreen] has been on screen, with every reply above it. It
 * only moves towards the newest reply: scrolling back up the thread leaves it where it was.
 */
internal fun seenWith(replies: List<Post>, seen: SeenReply?, onScreen: String?): SeenReply? {
    val i = replies.indexOfFirst { it.id == onScreen }
    if (i < 0) return seen
    val now = SeenReply(replies[i].id, serverTimeAt(replies, i))
    return if (seen == null || now.at >= markOf(replies, seen)) now else seen
}

/** The words of a thread's pill for [count] new replies. */
internal fun newRepliesWords(count: Int): String = when {
    count == 1 -> "1 new reply"
    count > 99 -> "99+ new replies"
    else -> "$count new replies"
}

/**
 * A thread's count of new replies below what the reader has seen, for its pill. It is written only
 * where the list's state is known: when the list has decided whether to follow a new newest reply
 * ([decided]), and when its first visible reply or its number of items moves ([moved]). While a new
 * newest reply waits for that decision nothing is written, so a reply the list is about to follow
 * never shows in the count.
 */
@Stable
internal class NewReplies(private val myId: String) {
    /** How many replies by others are below what the reader has seen. */
    var count by mutableIntStateOf(0)
        private set
    /** The pill's words for the last count above none, kept while the pill fades out. */
    var words by mutableStateOf("")
        private set
    /** The thread's replies, newest first, as the list has them now. */
    internal var replies: List<Post> = emptyList()
    private var seen: SeenReply? = null
    private var decided: String? = null

    /** The list has decided on [newest]: [followed] it is seen, as everything is once the list is down there. */
    fun decided(newest: Post, followed: Boolean, onScreen: String?) {
        decided = newest.id
        if (followed) seen = seenWith(replies, seen, newest.id)
        update(onScreen)
    }

    /** The list's first visible reply is now the one whose id is [onScreen]. */
    fun moved(onScreen: String?) = update(onScreen)

    private fun update(onScreen: String?) {
        // A new newest reply waits for the list's decision. A thread with nothing in it has nothing below.
        val newest = replies.firstOrNull()
        if (newest != null && newest.id != decided) return
        seen = seenWith(replies, seen, onScreen)
        count = newRepliesBelow(replies, seen, myId)
        if (count > 0) words = newRepliesWords(count)
    }
}

/** The key of the item at [LazyListState.firstVisibleItemIndex], as the list last laid it out. */
internal fun LazyListState.firstVisibleKey(): Any? {
    val first = firstVisibleItemIndex
    return layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.key
}

/**
 * The thread keyed [key]'s count of new replies, kept for its list [state] and [rows]. The list's
 * decision on a new newest reply goes to [NewReplies.decided] (see [FollowNewest]).
 */
@Composable
internal fun rememberNewReplies(key: Any?, state: LazyListState, rows: List<MessageRowInfo>, myId: String): NewReplies {
    val newReplies = remember(key) { NewReplies(myId) }
    val replies = remember(rows) { rows.map { it.post } }
    SideEffect { newReplies.replies = replies }
    LaunchedEffect(state, newReplies) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.totalItemsCount }
            .collect { newReplies.moved(state.firstVisibleKey() as? String) }
    }
    return newReplies
}

/**
 * The thread's pill for the replies below: the channel's "Jump to present" pill in look, saying how
 * many there are. A tap glides to the newest reply. For a screen reader it is a button, and a polite
 * live region, so it is spoken when it shows and when its number changes.
 */
@Composable
internal fun NewRepliesPill(newReplies: NewReplies, state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    NewestPill(
        newReplies.count > 0, newReplies.words, modifier,
        actionLabel = "Jump to the newest reply", role = Role.Button, announce = true,
    ) { scope.launch { state.glideToPresent() } }
}
