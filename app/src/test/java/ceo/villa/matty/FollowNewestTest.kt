package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.repo.SendState
import ceo.villa.matty.data.repo.pendingCreateAt
import ceo.villa.matty.ui.chat.NEAR_NEWEST
import ceo.villa.matty.ui.chat.followsNewest
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
    fun yourOwnMessageIsAlwaysFollowed() {
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

    @Test
    fun theChatAndTheThreadBothFollow() {
        val chatScreen = File(chat, "ChatScreen.kt").readText()
        val threadScreen = File(chat, "ThreadScreen.kt").readText()
        val chatCall = "FollowNewest(listState, ch.id, rows.firstOrNull()?.post, repo.myId)"
        val threadCall = "FollowNewest(threadList, rootId, rows.firstOrNull()?.post, repo.myId)"
        assertTrue(calls(chatScreen, chatCall))
        assertTrue(calls(threadScreen, threadCall))
        assertFalse("a call in a comment is no call", calls(threadScreen.replace(threadCall, "// $threadCall"), threadCall))
        assertFalse("nor after code on its line", calls("val x = 1 // $threadCall", threadCall))
        assertFalse("the rule is written once", calls(threadScreen, "animateScrollToItem"))
        assertTrue("the thread's rows are in the thread's order", calls(threadScreen, "threadNewestFirst(ids.orEmpty()"))
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
