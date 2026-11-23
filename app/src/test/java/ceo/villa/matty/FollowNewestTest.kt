package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.repo.SendState
import ceo.villa.matty.data.repo.pendingCreateAt
import ceo.villa.matty.ui.chat.NEAR_NEWEST
import ceo.villa.matty.ui.chat.arrivedNewest
import ceo.villa.matty.ui.chat.followsNewest
import ceo.villa.matty.ui.chat.followsNow
import ceo.villa.matty.ui.chat.threadNewestFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A chat and a thread follow their newest message. The rule is a function; that both screens use
 * it, keyed by their own conversation, is held by the source itself (as EndedServerTest does).
 */
class FollowNewestTest {
    private val chat = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty/ui/chat") }.first { it.isDirectory }

    /** Whether [call] is in [source] on a line of code, not in a comment. */
    private fun calls(source: String, call: String): Boolean = source.lines().any { line ->
        val code = line.trimStart()
        !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") && call in code.substringBefore("//")
    }

    @Test
    fun yourOwnMessageThatArrivedIsAlwaysFollowed() {
        assertTrue(followsNewest(following = false, firstVisible = 40, mine = true))
        assertTrue(followsNewest(following = true, firstVisible = 0, mine = true))
    }

    @Test
    fun somebodyElsesIsFollowedAtTheNewestEnd() {
        for (at in 0..NEAR_NEWEST) assertTrue("at $at", followsNewest(following = false, firstVisible = at, mine = false))
        assertTrue("following, even mid-glide further up", followsNewest(following = true, firstVisible = 9, mine = false))
    }

    @Test
    fun somebodyElsesIsNotFollowedWhileYouHaveScrolledAway() {
        assertFalse(followsNewest(following = false, firstVisible = NEAR_NEWEST + 1, mine = false))
        assertFalse(followsNewest(following = false, firstVisible = 30, mine = false))
    }

    private val bob = Post(id = "p-bob", createAt = 2_000, userId = "u-bob")
    private val carol = Post(id = "p-carol", createAt = 3_000, userId = "u-carol")
    private val yoursBefore = Post(id = "p-alice-old", createAt = 1_000, userId = "u-alice")
    /** Alice's message as this phone writes it: its id is the one it is sent with. */
    private val sending = Post(id = "u-alice:4000", createAt = 4_000, userId = "u-alice", pendingPostId = "u-alice:4000")
    /** The same message as the server gives it back, carrying the id it was sent with. */
    private val sent = sending.copy(id = "p-alice", createAt = 4_100, updateAt = 4_100)

    @Test
    fun yourMessageJustSentHasArrived() {
        assertTrue(arrivedNewest(sending, listOf(carol, bob, yoursBefore)))
        // Sent again after it failed: a new id the list never held.
        val again = Post(id = "u-alice:5000", createAt = 5_000, userId = "u-alice", pendingPostId = "u-alice:5000")
        assertTrue(arrivedNewest(again, listOf(carol, bob)))
    }

    @Test
    fun yourMessageAsTheServerGivesItBackHasNotArrived() {
        assertFalse(arrivedNewest(sent, listOf(sending, carol, bob)))
        // For a moment the server's copy can stand beside the phone's, either way up.
        assertFalse(arrivedNewest(sent, listOf(sending, sent, carol)))
        assertFalse(arrivedNewest(sending, listOf(sent, carol, bob)))
        // Already there under its own id.
        assertFalse(arrivedNewest(sent, listOf(carol, sent, bob)))
    }

    @Test
    fun yourMessageFromAnotherDeviceHasArrived() {
        val elsewhere = Post(id = "p-alice-tablet", createAt = 4_200, userId = "u-alice", pendingPostId = "u-alice:4150")
        assertTrue(arrivedNewest(elsewhere, listOf(sending, carol, bob)))
        assertTrue(arrivedNewest(elsewhere, listOf(sent, carol, bob)))
        // Without the id it was sent with, too.
        assertTrue(arrivedNewest(elsewhere.copy(pendingPostId = ""), listOf(sent, carol, bob)))
    }

    @Test
    fun yourOlderMessageLeftAtTheNewestEndHasNotArrived() {
        // Carol's newest message is deleted: Alice's older one is the newest now.
        assertFalse(arrivedNewest(yoursBefore, listOf(carol, yoursBefore, bob)))
        // A failed message of Alice's is removed: the one before it is the newest now.
        assertFalse(arrivedNewest(yoursBefore, listOf(sending, yoursBefore, bob)))
    }

    @Test
    fun whatAListOpensWithHasArrived() {
        assertTrue(arrivedNewest(yoursBefore, emptyList()))
        assertTrue(arrivedNewest(carol, emptyList()))
    }

    @Test
    fun scrolledAwayOnlyYourMessageThatArrivedIsFollowed() {
        val far = NEAR_NEWEST + 10
        val mine = { newest: Post, before: List<Post> -> newest.userId == "u-alice" && arrivedNewest(newest, before) }
        assertTrue("just sent", followsNewest(false, far, mine(sending, listOf(carol, bob))))
        assertFalse("given back", followsNewest(false, far, mine(sent, listOf(sending, carol, bob))))
        assertFalse("left at the newest end", followsNewest(false, far, mine(yoursBefore, listOf(carol, yoursBefore, bob))))
        assertTrue("from another device", followsNewest(false, far, mine(sent.copy(id = "p-x", pendingPostId = "u-alice:1"), listOf(carol, bob))))
        // At the newest end, or following, every newest message is followed as before.
        assertTrue(followsNewest(false, NEAR_NEWEST, mine(sent, listOf(sending, carol, bob))))
        assertTrue(followsNewest(true, far, mine(yoursBefore, listOf(carol, yoursBefore, bob))))
    }

    @Test
    fun theChatAndTheThreadBothFollow() {
        val chatScreen = File(chat, "ChatScreen.kt").readText()
        val threadScreen = File(chat, "ThreadScreen.kt").readText()
        val chatCall = "FollowNewest(listState, ch.id, rows, repo.myId)"
        val threadCall = "FollowNewest(threadList, rootId, rows, repo.myId)"
        assertTrue(calls(chatScreen, chatCall))
        assertTrue(calls(threadScreen, threadCall))
        assertFalse("a call in a comment is no call", calls(threadScreen.replace(threadCall, "// $threadCall"), threadCall))
        assertFalse("nor after code on its line", calls("val x = 1 // $threadCall", threadCall))
        assertFalse("the rule is written once", calls(threadScreen, "animateScrollToItem"))
        assertTrue("the thread's rows are in the thread's order", calls(threadScreen, "threadNewestFirst(ids.orEmpty()"))
    }

    @Test
    fun aListYourFingerIsOnIsNotBroughtDown() {
        // Whatever would bring it down: your own message that arrived, following, or being near.
        assertFalse(followsNow(dragging = true, following = true, firstVisible = 0, mine = true))
        assertFalse(followsNow(dragging = true, following = false, firstVisible = 30, mine = true))
        assertFalse(followsNow(dragging = true, following = false, firstVisible = 1, mine = false))
        // Let go, the rule is the list's own.
        for (following in listOf(true, false)) for (firstVisible in listOf(0, 2, 3, 30)) for (mine in listOf(true, false)) {
            assertEquals(followsNewest(following, firstVisible, mine), followsNow(false, following, firstVisible, mine))
        }
    }

    @Test
    fun theDecisionJudgesYoursAgainstTheRowsBeforeTheNewestChanged() {
        val chatScreen = File(chat, "ChatScreen.kt").readText()
        val follow = chatScreen.substring(chatScreen.indexOf("internal fun FollowNewest("), chatScreen.indexOf("private class HeldRows"))
            .lines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.isEmpty() }
        val signature = follow.indexOf("state: LazyListState, key: Any?, rows: List<MessageRowInfo>, myId: String,")
        val newest = follow.indexOf("val newest = rows.firstOrNull()?.post")
        val held = follow.indexOf("val held = remember(key) { HeldRows() }")
        val before = follow.indexOf("val before = held.rows")
        val publish = follow.indexOf("androidx.compose.runtime.SideEffect { held.rows = rows }")
        val effect = follow.indexOf("LaunchedEffect(newest?.id) {")
        val mine = follow.indexOf("val mine = newest.userId == myId && arrivedNewest(newest, before.map { it.post })")
        val decision = follow.indexOf("val follows = followsNow(dragging, following, state.firstVisibleItemIndex, mine)")
        assertTrue("the rows come in", signature >= 0 && newest > signature)
        // Read in composition, then published, so the effect sees the rows from before the newest changed.
        assertTrue("held is the conversation's own", held in 0 until before)
        assertTrue("read before it is published", before in 0 until publish)
        assertTrue("both before the effect", publish in 0 until effect)
        assertTrue("the decision uses the arrived rule", mine > effect && decision == mine + 1)
        assertFalse("the effect never reads what was published since", follow.subList(effect, follow.size).any { "held.rows" in it })
        assertEquals("what the list held is published in one place only", listOf(publish), follow.indices.filter { "held.rows =" in follow[it] })
        // The list is told the decision, and only then moves; only the glide to your own message
        // that arrived is following, so the server's copy of it does not stop the glide.
        assertEquals(
            listOf("onDecided(newest, follows)", "if (!follows) return@LaunchedEffect", "if (mine) following = true", "state.animateScrollToItem(0)"),
            follow.subList(decision + 1, decision + 5),
        )
        assertEquals("following is set true in that one place only", 1, follow.count { it == "if (mine) following = true" || it == "following = true" })
        // Your finger on the list: following ends at once, and the list is not brought down until you let go.
        assertTrue(follow.contains("is androidx.compose.foundation.interaction.DragInteraction.Start -> { following = false; dragging = true }"))
        assertTrue(follow.contains("is androidx.compose.foundation.interaction.DragInteraction.Cancel -> dragging = false"))
        val rest = follow.indexOf("following = state.firstVisibleItemIndex <= NEAR_NEWEST")
        assertTrue("at rest the finger is off", rest >= 0 && follow[rest + 1] == "dragging = false")
    }

    @Test
    fun yourRepliesOnTheirWaySitAtTheNewestEndOfTheThread() {
        // The phone's clock is behind the server's: the reply being sent carries an older time
        // than Bob's newest reply, and still sits at the newest end, where the list follows it.
        val bob = Post(id = "p-bob", createAt = 2_000, userId = "u-bob")
        val carol = Post(id = "p-carol", createAt = 1_000, userId = "u-carol")
        val sending = Post(id = "u-victor:1500", createAt = 1_500, userId = "u-victor")
        val failed = Post(id = "u-victor:1200", createAt = 1_200, userId = "u-victor")
        assertEquals(
            listOf(sending, bob, failed, carol),
            threadNewestFirst(listOf(carol, bob, failed, sending), mapOf(sending.id to SendState.Sending, failed.id to SendState.Failed)),
        )
        // One that failed sorts by its own time: later replies pass it, as in the channel.
        val failedFirst = Post(id = "u-victor:1000", createAt = 1_000, userId = "u-victor")
        val bob1 = Post(id = "p-bob1", createAt = 5_000, userId = "u-bob")
        val bob2 = Post(id = "p-bob2", createAt = 6_000, userId = "u-bob")
        assertEquals(listOf(bob2, bob1, failedFirst), threadNewestFirst(listOf(failedFirst, bob1, bob2), mapOf(failedFirst.id to SendState.Failed)))
        // Once the server has it, its own time places it.
        val sent = sending.copy(id = "p-victor", createAt = 2_500)
        assertEquals(listOf(sent, bob, carol), threadNewestFirst(listOf(carol, sent, bob), emptyMap()))
    }

    @Test
    fun aReplyThatFailedKeepsItsPlaceOnAPhoneWhoseClockIsBehind() {
        // The phone's clock is 60 s behind: Bob replied at 100 s (the server's time), Victor at
        // 40 s by his phone. The reply is stamped just after the newest the phone holds.
        val bob = Post(id = "p-bob", createAt = 100_000, userId = "u-bob", channelId = "c-1")
        val at = pendingCreateAt(clock = 40_000, newestHeld = bob.createAt)
        assertEquals(100_001L, at)
        val reply = Post(id = "u-victor:40000", createAt = at, userId = "u-victor", channelId = "c-1")
        assertEquals("being sent", listOf(reply, bob), threadNewestFirst(listOf(bob, reply), mapOf(reply.id to SendState.Sending)))
        assertEquals("failed, it stays below Bob's", listOf(reply, bob), threadNewestFirst(listOf(bob, reply), mapOf(reply.id to SendState.Failed)))
        // A clock that is ahead, or nothing held yet: the phone's clock.
        assertEquals(200_000L, pendingCreateAt(clock = 200_000, newestHeld = 100_000))
        assertEquals(40_000L, pendingCreateAt(clock = 40_000, newestHeld = null))
        assertEquals(40_001L, pendingCreateAt(clock = 40_000, newestHeld = 40_000))
    }

    @Test
    fun aMessageBeingSentIsStampedNoEarlierThanWhatTheChannelHolds() {
        val repo = File(chat, "../../data/repo/ChatRepository.kt").readText()
        val send = repo.substring(repo.indexOf("fun sendMessage("), repo.indexOf("fun retry("))
        assertTrue("the pending id keeps the phone's clock", calls(send, "val pendingId = \"\$myId:\$now\""))
        assertTrue(calls(send, "val at = pendingCreateAt(now, posts.value.values.filter { it.channelId == channelId }.maxOfOrNull { it.createAt })"))
        assertTrue(calls(send, "id = pendingId, createAt = at, updateAt = at,"))
    }
}
