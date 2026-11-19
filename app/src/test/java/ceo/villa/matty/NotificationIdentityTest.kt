package ceo.villa.matty

import ceo.villa.matty.service.NoteIdentity
import ceo.villa.matty.service.ShownPosts
import ceo.villa.matty.service.noteLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Who a notification belongs to: one per saved server and conversation, for the live path and pushes alike. */
class NotificationIdentityTest {
    @Test
    fun aCollapsedThreadIsItsOwnConversationAndEverythingElseIsTheChannels() {
        assertEquals("root1", NoteIdentity.conversation("chan1", "root1"))
        assertEquals("chan1", NoteIdentity.conversation("chan1", ""))
        assertNotEquals(NoteIdentity.id("chan1"), NoteIdentity.id("root1"))
    }

    @Test
    fun twoServersOrTwoAccountsNeverShareANotificationOrAnIntent() {
        // Alice and Bob saved on one server: the same channel id, different saved servers.
        val alice = "server-alice"; val bob = "server-bob"
        assertNotEquals(NoteIdentity.tag(alice), NoteIdentity.tag(bob))
        assertEquals(NoteIdentity.id("chan1"), NoteIdentity.id("chan1"))
        // The data counts when Android tells PendingIntents apart; extras do not.
        assertNotEquals(NoteIdentity.data(alice, "chan1"), NoteIdentity.data(bob, "chan1"))
        assertNotEquals(NoteIdentity.data(alice, "chan1"), NoteIdentity.data(alice, "root1"))
        assertEquals("matty-notification://server-alice/chan1", NoteIdentity.data(alice, "chan1"))
    }

    @Test
    fun noticesAreKeptApartFromMessages() {
        val tag = NoteIdentity.noticeTag("server-alice")
        assertTrue(NoteIdentity.isNotice(tag))
        assertNotEquals(NoteIdentity.tag("server-alice"), tag)
        assertFalse(NoteIdentity.isNotice(NoteIdentity.tag("server-alice")))
        assertFalse(NoteIdentity.isNotice(null))
    }

    @Test
    fun aPostIsShownOncePerSavedServer() {
        val shown = ShownPosts()
        assertTrue(shown.firstShow("alice", "post1"))
        assertFalse("the live path and a push bring the same post", shown.firstShow("alice", "post1"))
        assertTrue("the same post mentions Bob too, on his own saved server", shown.firstShow("bob", "post1"))
        assertTrue(shown.firstShow("alice", ""))
        assertTrue(shown.firstShow("alice", ""))
    }

    @Test
    fun theShownSetForgetsTheOldestPastItsBound() {
        val shown = ShownPosts(max = 3)
        listOf("a", "b", "c", "d").forEach { assertTrue(shown.firstShow("s", it)) }
        assertTrue("the oldest went", shown.firstShow("s", "a"))
        assertFalse(shown.firstShow("s", "d"))
    }

    @Test
    fun checkingAndAddingIsOneStep() {
        val shown = ShownPosts()
        val winners = AtomicInteger()
        val go = CountDownLatch(1)
        val threads = (1..8).map { Thread { go.await(); if (shown.firstShow("s", "p")) winners.incrementAndGet() } }
        threads.forEach { it.start() }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }

    @Test
    fun aNotificationKeepsTheLatestSixLines() {
        assertEquals(listOf(1), noteLines(emptyList(), 1))
        assertEquals(listOf(5, 6, 7, 8, 9, 10), noteLines((1..9).toList(), 10))
        assertEquals(listOf(1, 2, 3), noteLines(listOf(1, 2), 3))
    }
}
