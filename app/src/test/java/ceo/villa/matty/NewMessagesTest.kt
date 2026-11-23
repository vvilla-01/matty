package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.repo.pendingCreateAt
import ceo.villa.matty.ui.chat.NewReplies
import ceo.villa.matty.ui.chat.PillWords
import ceo.villa.matty.ui.chat.SeenReply
import ceo.villa.matty.ui.chat.channelPillWords
import ceo.villa.matty.ui.chat.newMessagesBelow
import ceo.villa.matty.ui.chat.newMessagesWords
import ceo.villa.matty.ui.chat.seenByPlace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A channel counts the messages by others that stand below the newest message the reader has had
 * on screen, by their place in its list. Alice is the reader; the lists are newest first, in the
 * order the phone received them, as the channel shows them.
 */
class NewMessagesTest {
    private val me = "u-alice"
    private val dave0 = Post(id = "p-dave0", createAt = 500, userId = "u-dave")
    private val bob1 = Post(id = "p-bob1", createAt = 1_000, userId = "u-bob")
    private val bob2 = Post(id = "p-bob2", createAt = 3_000, userId = "u-bob")
    private val carol3 = Post(id = "p-carol3", createAt = 4_000, userId = "u-carol")

    /** One of Alice's messages as the phone writes it before the server has it (see sendMessage). */
    private fun pending(phoneClock: Long, newestHeld: Long?): Post {
        val id = "$me:$phoneClock"
        val at = pendingCreateAt(phoneClock, newestHeld)
        return Post(id = id, createAt = at, updateAt = at, userId = me, pendingPostId = id)
    }

    @Test
    fun theWords() {
        assertEquals("1 new message", newMessagesWords(1))
        assertEquals("2 new messages", newMessagesWords(2))
        assertEquals("99 new messages", newMessagesWords(99))
        assertEquals("99+ new messages", newMessagesWords(100))
        assertEquals("99+ new messages", newMessagesWords(1_234))
    }

    @Test
    fun systemMessagesNeverCount() {
        val joined = Post(id = "p-erin-joined", createAt = 2_000, userId = "u-erin", type = "system_join_channel")
        val header = Post(id = "p-header", createAt = 3_500, userId = "u-carol", type = "system_header_change")
        val channel = listOf(carol3, header, bob2, joined, bob1, dave0)
        val seen = seenByPlace(channel, null, bob1.id)
        assertEquals("Bob's and Carol's, not who joined or the header", 2, newMessagesBelow(channel, seen, me))
    }

    /** Alice's failed message in the middle of the channel, with Bob's and Carol's after it. */
    private fun countAroundAFailedMessage(phoneClockAhead: Long): List<Int> {
        val failed = pending(1_200 + phoneClockAhead, bob1.createAt)
        val channel = listOf(carol3, bob2, failed, bob1, dave0)
        var seen = seenByPlace(channel, null, bob1.id)
        val fromBob = newMessagesBelow(channel, seen, me)
        // The failed message on screen stands at the time of Bob's above it.
        seen = seenByPlace(channel, seen, failed.id)
        assertEquals(SeenReply(failed.id, bob1.createAt), seen)
        val fromFailed = newMessagesBelow(channel, seen, me)
        seen = seenByPlace(channel, seen, bob2.id)
        val fromBob2 = newMessagesBelow(channel, seen, me)
        // Back up the channel: what was on screen once stays seen.
        seen = seenByPlace(channel, seen, dave0.id)
        return listOf(fromBob, fromFailed, fromBob2, newMessagesBelow(channel, seen, me))
    }

    @Test
    fun aFailedMessageInTheMiddleOfTheChannelNeverCountsAndMovesNothing() {
        assertEquals(listOf(2, 2, 1, 1), countAroundAFailedMessage(phoneClockAhead = 0))
        assertEquals("a phone whose clock is ahead", listOf(2, 2, 1, 1), countAroundAFailedMessage(phoneClockAhead = 60_000))
        assertEquals("a phone whose clock is behind", listOf(2, 2, 1, 1), countAroundAFailedMessage(phoneClockAhead = -60_000))
    }

    @Test
    fun olderMessagesAddedAtTheFarEndChangeNothing() {
        val channel = listOf(carol3, bob2, bob1)
        val seen = seenByPlace(channel, null, bob1.id)
        assertEquals(2, newMessagesBelow(channel, seen, me))
        val older = listOf(
            Post(id = "p-erin-old", createAt = 400, userId = "u-erin"),
            Post(id = "p-dave-old", createAt = 300, userId = "u-dave"),
        )
        val grown = channel + older
        assertEquals(2, newMessagesBelow(grown, seen, me))
        assertEquals("an older message on screen moves nothing", seen, seenByPlace(grown, seen, "p-dave-old"))
    }

    @Test
    fun aChannelReplacedWholeCountsFromTheSeenMessagesTime() {
        // Alice saw Bob's message, then was away a long time: the channel's newest page holds none
        // of what she had, only newer messages, her own among them, and somebody joining.
        val seen = seenByPlace(listOf(bob2, bob1, dave0), null, bob1.id)
        val fresh = (1..60).map { n ->
            when {
                n % 10 == 0 -> Post(id = "p-alice$n", createAt = 10_000L + n, userId = me)
                n == 5 -> Post(id = "p-joined", createAt = 10_005, userId = "u-erin", type = "system_join_channel")
                else -> Post(id = "p-carol$n", createAt = 10_000L + n, userId = "u-carol")
            }
        }.reversed()
        assertEquals(53, newMessagesBelow(fresh, seen, me))
        // Alice's own sent message, gone from the page, counts from its time too.
        val sent = Post(id = "p-alice-sent", createAt = 10_030, userId = me, pendingPostId = "$me:9000")
        val seenSent = seenByPlace(listOf(sent, bob1), null, sent.id)
        assertEquals(27, newMessagesBelow(fresh, seenSent, me))
    }

    @Test
    fun aMessageTheChannelFollowsNeverShowsAndTheWordsAreTheChannels() {
        val pill = NewReplies(me, ::newMessagesWords, byPlace = true)
        pill.replies = listOf(bob1, dave0)
        pill.decided(bob1, followed = true, onScreen = bob1.id)
        assertEquals("a channel opens with nothing counted", 0, pill.count)
        // Bob's lands three messages up, and the channel does not follow it.
        pill.moved(dave0.id)
        pill.replies = listOf(bob2, bob1, dave0)
        pill.moved(dave0.id)
        assertEquals("nothing is written before the list decides", 0, pill.count)
        pill.decided(bob2, followed = false, onScreen = dave0.id)
        assertEquals(1, pill.count)
        assertEquals("1 new message", pill.words)
        // Carol's lands, and the channel follows it: seen at once, with all below it.
        pill.replies = listOf(carol3, bob2, bob1, dave0)
        pill.moved(dave0.id)
        assertEquals(1, pill.count)
        pill.decided(carol3, followed = true, onScreen = dave0.id)
        assertEquals(0, pill.count)
        assertEquals("the words stay while the pill fades out", "1 new message", pill.words)
    }

    @Test
    fun alicesOwnMessageGivenBackInItsPlaceLeavesTheCountAsItWas() {
        val pill = NewReplies(me, ::newMessagesWords, byPlace = true)
        val sending = pending(1_200, bob1.createAt)
        pill.replies = listOf(sending, bob1, dave0)
        pill.decided(sending, followed = true, onScreen = sending.id)
        assertEquals(0, pill.count)
        // Alice reads back up before the server answers; Carol's message arrives and is not followed.
        pill.moved(dave0.id)
        val carol = Post(id = "p-carol", createAt = 1_400, userId = "u-carol")
        pill.replies = listOf(carol, sending, bob1, dave0)
        pill.decided(carol, followed = false, onScreen = dave0.id)
        assertEquals(1, pill.count)
        // The server gives Alice's message back, saved after Carol's, in the place it had.
        val sent = Post(id = "p-alice", createAt = 1_500, userId = me, pendingPostId = sending.id)
        pill.replies = listOf(carol, sent, bob1, dave0)
        pill.moved(dave0.id)
        assertEquals("Carol's is still below and unseen", 1, pill.count)
        // Down at Alice's own message, Carol's is the one left; at Carol's, none.
        pill.moved(sent.id)
        assertEquals(1, pill.count)
        pill.moved(carol.id)
        assertEquals(0, pill.count)
    }

    @Test
    fun messagesThatReachedThePhoneOutOfOrderCountByTheirPlace() {
        // Carol's was saved after Bob's but reached the phone first: it stands above Bob's.
        val bob = Post(id = "p-bob-late", createAt = 1_100, userId = "u-bob")
        val carol = Post(id = "p-carol-first", createAt = 1_101, userId = "u-carol")
        val channel = listOf(bob, carol, bob1, dave0)
        var seen = seenByPlace(channel, null, bob1.id)
        assertEquals(2, newMessagesBelow(channel, seen, me))
        seen = seenByPlace(channel, seen, bob.id)
        assertEquals("at the newest message nothing is below, whatever the times say", 0, newMessagesBelow(channel, seen, me))
        // A message that reaches the phone late, with an earlier time, counts where it stands.
        val late = Post(id = "p-erin-late", createAt = 900, userId = "u-erin")
        assertEquals(1, newMessagesBelow(listOf(late) + channel, seen, me))
    }

    @Test
    fun aSeenMessageThatIsDeletedCountsFromItsTime() {
        val seen = seenByPlace(listOf(bob2, bob1, dave0), null, bob2.id)
        assertEquals(SeenReply(bob2.id, bob2.createAt), seen)
        val after = listOf(carol3, bob1, dave0)
        assertEquals("Carol's is newer than the deleted message Alice saw", 1, newMessagesBelow(after, seen, me))
        assertEquals("an older message on screen moves nothing", seen, seenByPlace(after, seen, bob1.id))
        assertEquals("Carol's on screen is seen", SeenReply(carol3.id, carol3.createAt), seenByPlace(after, seen, carol3.id))
    }

    @Test
    fun thePillSaysTheCountWhileItShowsOneAndItsLastWordsWhileItFadesOut() {
        val jump = PillWords("Jump to present", counting = false)
        assertEquals(PillWords("2 new messages", counting = true), channelPillWords(show = true, count = 2, countWords = "2 new messages", said = jump))
        assertEquals("far up with nothing new", jump, channelPillWords(true, 0, "2 new messages", PillWords("2 new messages", true)))
        assertEquals("fading out from a number", PillWords("1 new message", true), channelPillWords(false, 0, "1 new message", PillWords("1 new message", true)))
        assertEquals(
            "fading out after another conversation took this one's place: its fresh count has no words",
            PillWords("3 new messages", true), channelPillWords(false, 0, "", PillWords("3 new messages", true)),
        )
        assertEquals("fading out from Jump to present", jump, channelPillWords(false, 0, "", jump))
    }
}
