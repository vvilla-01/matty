package ceo.villa.matty

import ceo.villa.matty.data.session.ServerPolicy
import ceo.villa.matty.ui.lock.UNLOCK_KEPT_MS
import ceo.villa.matty.ui.lock.Unlocks
import ceo.villa.matty.ui.lock.WhileLocked
import ceo.villa.matty.ui.lock.whileLocked
import ceo.villa.matty.ui.lock.windowSecure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a server that asks for an unlock is locked, and what waits behind its lock. */
class AppLockTest {
    private val min = 60_000L

    @Test
    fun fiveMinutesAsInMattermostsOwnApp() = assertEquals(5 * min, UNLOCK_KEPT_MS)

    @Test
    fun aColdStartIsLocked() {
        // A process that died starts with a new, empty book: every server locked.
        val u = Unlocks()
        assertTrue(u.isLocked("a", 0))
        assertTrue(u.shown("a", 1_000))
        // Shown again without an unlock, it stays locked.
        assertTrue(u.shown("a", 2_000))
    }

    @Test
    fun theBackgroundUnderFiveMinutesKeepsItOpen() {
        val u = Unlocks()
        u.unlocked("a", 0)
        // Hours in front: its time doesn't run while it is on screen.
        assertFalse(u.isLocked("a", 3 * 60 * min))
        u.hidden("a", 3 * 60 * min)
        assertFalse(u.shown("a", 3 * 60 * min + 5 * min - 1_000))
        u.hidden("a", 4 * 60 * min)
        // 4:59 away: still open; 5:00 away: locked.
        assertFalse(u.isLocked("a", 4 * 60 * min + 4 * min + 59_000))
        assertTrue(u.shown("a", 4 * 60 * min + 5 * min))
        // And it stays locked, however soon it is hidden and shown again, until unlocked.
        u.hidden("a", 4 * 60 * min + 5 * min)
        assertTrue(u.shown("a", 4 * 60 * min + 5 * min + 1))
        u.unlocked("a", 4 * 60 * min + 6 * min)
        assertFalse(u.shown("a", 4 * 60 * min + 6 * min))
    }

    @Test
    fun switchingServers() {
        val u = Unlocks()
        u.unlocked("a", 0)
        // A server never unlocked is locked when switched to.
        assertTrue(u.shown("b", 10 * min))
        u.hidden("a", 10 * min)
        u.unlocked("b", 11 * min)
        // Back to the first within five minutes of leaving it: open; later: locked.
        u.hidden("b", 12 * min)
        assertFalse(u.shown("a", 14 * min))
        u.hidden("a", 14 * min)
        assertTrue(u.shown("b", 17 * min))
    }

    @Test
    fun theNextServerShownFirstDoesntSpoilTheLastOnesTime() {
        val u = Unlocks()
        u.unlocked("a", 0)
        u.unlocked("b", 1 * min)
        // "b" took the screen before "a" was reported hidden: "a" isn't the open one any more.
        u.hidden("a", 2 * min)
        assertFalse(u.isLocked("b", 60 * min))
    }

    @Test
    fun theServerLeftIsTimedFromWhenItLeftThoughTheNextIsShownFirst() {
        // Both servers ask. The new server's gate asks shown() before the old one's says hidden().
        val u = Unlocks()
        assertTrue(u.shown("a", 1 * min)); u.unlocked("a", 1 * min)
        assertTrue(u.shown("b", 6 * min)); u.hidden("a", 6 * min); u.unlocked("b", 6 * min)
        assertFalse(u.shown("a", 7 * min)); u.hidden("b", 7 * min)
        // "b" left the screen at 7: four and a half minutes later it is still open.
        assertFalse(u.shown("b", 11 * min + 30_000)); u.hidden("a", 11 * min + 30_000)
        // And "a", which left just now, too; five minutes after "b" left, "b" is locked.
        assertFalse(u.isLocked("a", 12 * min))
        u.hidden("b", 12 * min)
        assertTrue(u.isLocked("b", 17 * min))
    }

    @Test
    fun aClockThatWentBackIsNoProof() {
        val u = Unlocks()
        u.unlocked("a", 10 * min)
        u.hidden("a", 10 * min)
        assertTrue(u.isLocked("a", 9 * min))
    }

    @Test
    fun theWindowIsSecureWhenTheServerSaysOrTheLockShows() {
        assertFalse(windowSecure(ServerPolicy(), locked = false))
        assertTrue(windowSecure(ServerPolicy(blocksCapture = true), locked = false))
        assertTrue(windowSecure(ServerPolicy(asksUnlock = true), locked = true))
        assertFalse(windowSecure(ServerPolicy(asksUnlock = true), locked = false))
    }

    @Test
    fun whatWaitsBehindTheLock() {
        val signedIn = setOf("a", "b")
        // A share, the launcher, another app's intent, a notification of this server: wait for the unlock.
        assertEquals(WhileLocked.Waits, whileLocked(false, null, "a", signedIn))
        assertEquals(WhileLocked.Waits, whileLocked(false, "b", "a", signedIn))
        assertEquals(WhileLocked.Waits, whileLocked(true, "a", "a", signedIn))
        assertEquals(WhileLocked.Waits, whileLocked(true, null, "a", signedIn))
        // A server signed out of since: nothing to switch to.
        assertEquals(WhileLocked.Waits, whileLocked(true, "c", "a", signedIn))
        // Matty's own notification of another server: that server, behind its own lock if it has one.
        assertEquals(WhileLocked.Switch("b"), whileLocked(true, "b", "a", signedIn))
    }
}
