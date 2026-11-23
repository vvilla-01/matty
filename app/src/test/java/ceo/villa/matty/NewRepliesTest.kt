package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.repo.SendState
import ceo.villa.matty.data.repo.pendingCreateAt
import ceo.villa.matty.ui.chat.NewReplies
import ceo.villa.matty.ui.chat.SeenReply
import ceo.villa.matty.ui.chat.newRepliesBelow
import ceo.villa.matty.ui.chat.newRepliesWords
import ceo.villa.matty.ui.chat.seenWith
import ceo.villa.matty.ui.chat.threadNewestFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A thread counts the replies by others that arrived below the newest reply the reader has had on
 * screen. Alice is the reader; the lists are newest first, as the thread shows them.
 */
class NewRepliesTest {
    private val me = "u-alice"
    private val root = Post(id = "p-root", createAt = 100, userId = "u-dave")
    private val bob1 = Post(id = "p-bob1", createAt = 1_000, userId = "u-bob")
    private val alice1 = Post(id = "p-alice1", createAt = 2_000, userId = me)
    private val bob2 = Post(id = "p-bob2", createAt = 3_000, userId = "u-bob")
    private val carol3 = Post(id = "p-carol3", createAt = 4_000, userId = "u-carol")
    private val thread = listOf(carol3, bob2, alice1, bob1, root)

    /** One of Alice's replies as the phone writes it before the server has it (see sendMessage). */
    private fun pending(phoneClock: Long, newestHeld: Long?): Post {
        val id = "$me:$phoneClock"
        val at = pendingCreateAt(phoneClock, newestHeld)
        return Post(id = id, createAt = at, updateAt = at, userId = me, pendingPostId = id)
    }

    @Test
    fun othersRepliesBelowTheSeenOneCountAndAlicesOwnDoNot() {
        val seen = seenWith(thread, null, bob1.id)
        assertEquals(SeenReply(bob1.id, bob1.createAt), seen)
        assertEquals("Bob's and Carol's, not Alice's", 2, newRepliesBelow(thread, seen, me))
    }

    @Test
    fun nothingIsCountedBeforeAnythingWasOnScreen() {
        assertEquals(0, newRepliesBelow(thread, null, me))
        assertNull("a reply that is not in the list is not on screen", seenWith(thread, null, "p-gone"))
    }

    @Test
    fun alicesOwnRepliesNeverCountSendingSentOrFailed() {
        val sending = pending(5_000, carol3.createAt)
        val failed = pending(4_500, carol3.createAt).copy(id = "$me:4500b", pendingPostId = "$me:4500b")
        val sent = Post(id = "p-alice2", createAt = 6_000, userId = me, pendingPostId = "$me:5900")
        val replies = threadNewestFirst(thread + listOf(sending, failed, sent), mapOf(sending.id to SendState.Sending, failed.id to SendState.Failed))
        val seen = seenWith(replies, null, bob1.id)
        assertEquals(2, newRepliesBelow(replies, seen, me))
    }

    @Test
    fun theCountGoesDownAsRepliesComeIntoViewAndNotBackUp() {
        var seen = seenWith(thread, null, bob1.id)
        assertEquals(2, newRepliesBelow(thread, seen, me))
        seen = seenWith(thread, seen, alice1.id)
        assertEquals("Alice's own on screen changes nothing", 2, newRepliesBelow(thread, seen, me))
        seen = seenWith(thread, seen, bob2.id)
        assertEquals(1, newRepliesBelow(thread, seen, me))
        seen = seenWith(thread, seen, carol3.id)
        assertEquals(0, newRepliesBelow(thread, seen, me))
        // Back up the thread: what was on screen once is seen.
        seen = seenWith(thread, seen, bob1.id)
        assertEquals(SeenReply(carol3.id, carol3.createAt), seen)
        assertEquals(0, newRepliesBelow(thread, seen, me))
    }

    @Test
    fun theSeenReplyIsFoundByItsId() {
        val seen = SeenReply(bob2.id, bob2.createAt)
        assertEquals(1, newRepliesBelow(thread, seen, me))
        // A reply that came in at the same time as a seen one, and sits above it, was on screen too.
        val erin = Post(id = "p-erin", createAt = bob2.createAt, userId = "u-erin")
        assertEquals(1, newRepliesBelow(listOf(carol3, bob2, erin, alice1, bob1, root), seen, me))
    }

    @Test
    fun aSeenReplyOfAlicesIsFoundAsTheServerGaveItBack() {
        // Alice's reply is on screen while it is sent; the server's time is later than the phone's
        // stamp, and Bob's reply landed on the server first.
        val sending = pending(1_200, bob1.createAt)
        var replies = threadNewestFirst(listOf(root, bob1, sending), mapOf(sending.id to SendState.Sending))
        val seen = seenWith(replies, null, sending.id)
        val bob15 = Post(id = "p-bob15", createAt = 1_400, userId = "u-bob")
        val sent = Post(id = "p-alice", createAt = 1_500, userId = me, pendingPostId = sending.id)
        val bob2 = Post(id = "p-bob2", createAt = 2_000, userId = "u-bob")
        replies = threadNewestFirst(listOf(root, bob1, bob15, sent, bob2), emptyMap())
        assertEquals(listOf(bob2, sent, bob15, bob1, root), replies)
        assertEquals("only the one below Alice's reply", 1, newRepliesBelow(replies, seen, me))
    }

    @Test
    fun whatWasOnScreenStaysSeenWhenAlicesOwnReplyComesBackWithAnEarlierTime() {
        // Alice's reply is being sent, at the newest end; Bob's lands above it, on screen.
        val sending = pending(1_200, bob1.createAt)
        val bobLate = Post(id = "p-bob-late", createAt = 1_300, userId = "u-bob")
        var replies = threadNewestFirst(listOf(root, bob1, sending, bobLate), mapOf(sending.id to SendState.Sending))
        assertEquals(listOf(sending, bobLate, bob1, root), replies)
        val seen = seenWith(replies, null, sending.id)
        assertEquals(SeenReply(sending.id, bobLate.createAt), seen)
        // The server gives Alice's reply back with a time before Bob's.
        val sent = Post(id = "p-alice", createAt = 1_250, userId = me, pendingPostId = sending.id)
        replies = threadNewestFirst(listOf(root, bob1, sent, bobLate), emptyMap())
        assertEquals(listOf(bobLate, sent, bob1, root), replies)
        assertEquals("Bob's reply was on screen: it does not count again", 0, newRepliesBelow(replies, seen, me))
    }

    @Test
    fun aThreadThatIsEmptiedHasNothingBelow() {
        val pill = NewReplies(me)
        pill.replies = listOf(bob1, root)
        pill.decided(bob1, followed = true, onScreen = bob1.id)
        pill.replies = listOf(bob2, bob1, root)
        pill.decided(bob2, followed = false, onScreen = root.id)
        assertEquals(1, pill.count)
        // The thread is deleted while the pill shows: nothing is left to be new.
        pill.replies = emptyList()
        pill.moved(null)
        assertEquals(0, pill.count)
    }

    @Test
    fun aSeenReplyThatIsGoneCountsFromItsTime() {
        val seen = seenWith(thread, null, bob1.id)
        assertEquals(2, newRepliesBelow(thread - bob1, seen, me))
        assertEquals(2, newRepliesBelow(thread.map { if (it == bob1) it.copy(deleteAt = 9_000) else it }, seen, me))
    }

    @Test
    fun aReplyDeletedBeforeItWasSeenStopsCounting() {
        val seen = seenWith(thread, null, bob1.id)
        assertEquals(1, newRepliesBelow(thread - bob2, seen, me))
        assertEquals(1, newRepliesBelow(thread.map { if (it == bob2) it.copy(deleteAt = 9_000) else it }, seen, me))
    }

    @Test
    fun editsDoNotCount() {
        val seen = seenWith(thread, null, bob2.id)
        val edited = thread.map {
            when (it) {
                bob1 -> it.copy(message = "fixed a typo", updateAt = 8_000, editAt = 8_000)
                carol3 -> it.copy(message = "now with a link", updateAt = 9_000, editAt = 9_000)
                else -> it
            }
        }
        assertEquals(1, newRepliesBelow(edited, seen, me))
    }

    @Test
    fun aReplyThatLandsOnScreenIsSeen() {
        // Alice's reply is being sent, at the newest end, and on screen. Bob's lands above it,
        // on screen too, as the thread shows replies being sent first.
        val sending = pending(1_200, bob1.createAt)
        val bob2 = Post(id = "p-bob2", createAt = 1_300, userId = "u-bob")
        val replies = threadNewestFirst(listOf(root, bob1, bob2, sending), mapOf(sending.id to SendState.Sending))
        assertEquals(listOf(sending, bob2, bob1, root), replies)
        val seen = seenWith(replies, seenWith(replies, null, bob1.id), sending.id)
        assertEquals(0, newRepliesBelow(replies, seen, me))
    }

    /** The same replies as the server tells them; only the stamp of Alice's failed reply differs. */
    private fun countWithAFailedReply(phoneClockAhead: Long): List<Int> {
        val bob1 = Post(id = "p-bob1", createAt = 100_000, userId = "u-bob")
        val failed = pending(100_500 + phoneClockAhead, bob1.createAt)
        val states = mapOf(failed.id to SendState.Failed)
        var replies = threadNewestFirst(listOf(root, bob1, failed), states)
        // At the newest end, then back up the thread to the start: the failed reply stays the newest seen.
        var seen = seenWith(replies, null, failed.id)
        seen = seenWith(replies, seen, root.id)
        val bob2 = Post(id = "p-bob2", createAt = 130_000, userId = "u-bob")
        replies = threadNewestFirst(listOf(root, bob1, failed, bob2), states)
        val withIt = newRepliesBelow(replies, seen, me)
        // Alice deletes the failed reply: the count goes on from its time.
        val withoutIt = newRepliesBelow(replies - failed, seen, me)
        return listOf(withIt, withoutIt)
    }

    @Test
    fun aPhoneWhoseClockIsAheadCountsTheSame() {
        assertEquals(listOf(1, 1), countWithAFailedReply(phoneClockAhead = 0))
        assertEquals(listOf(1, 1), countWithAFailedReply(phoneClockAhead = 60_000))
    }

    @Test
    fun aPhoneWhoseClockIsBehindCountsTheSame() {
        assertEquals(listOf(1, 1), countWithAFailedReply(phoneClockAhead = -60_000))
    }

    @Test
    fun aReplyTheListIsAboutToFollowNeverShows() {
        val pill = NewReplies(me)
        pill.replies = listOf(bob1, root)
        pill.decided(bob1, followed = true, onScreen = bob1.id)
        assertEquals(0, pill.count)
        pill.moved(root.id)
        // Bob's reply lands while Alice reads back up the thread; the list moves before it decides.
        pill.replies = listOf(bob2, bob1, root)
        pill.moved(root.id)
        assertEquals("nothing is written before the list decides", 0, pill.count)
        pill.decided(bob2, followed = false, onScreen = root.id)
        assertEquals(1, pill.count)
        assertEquals("1 new reply", pill.words)
        // Carol's lands, and the list follows it: seen at once, with all below it.
        pill.replies = listOf(carol3, bob2, bob1, root)
        pill.moved(root.id)
        assertEquals(1, pill.count)
        pill.decided(carol3, followed = true, onScreen = root.id)
        assertEquals(0, pill.count)
        assertEquals("the words stay while the pill fades out", "1 new reply", pill.words)
    }

    @Test
    fun aThreadOpensWithNothingCounted() {
        val pill = NewReplies(me)
        pill.replies = thread
        pill.moved(bob1.id)
        assertEquals("before the list decides on its newest reply", 0, pill.count)
        pill.decided(carol3, followed = true, onScreen = null)
        assertEquals(0, pill.count)
        pill.moved(root.id)
        assertEquals(0, pill.count)
    }

    @Test
    fun theWords() {
        assertEquals("1 new reply", newRepliesWords(1))
        assertEquals("2 new replies", newRepliesWords(2))
        assertEquals("99 new replies", newRepliesWords(99))
        assertEquals("99+ new replies", newRepliesWords(100))
        assertEquals("99+ new replies", newRepliesWords(1_234))
    }
}
