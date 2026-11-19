package ceo.villa.matty

import ceo.villa.matty.ui.lock.LockExits
import ceo.villa.matty.ui.lock.WaitingBehindLock
import ceo.villa.matty.ui.lock.lockExits
import ceo.villa.matty.ui.lock.lockSheetHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ways off a lock screen other than an unlock, and what becomes of what waited behind it. */
class LockExitsTest {
    /** Stands in for an Intent: only its identity counts. */
    private class Pending(val name: String)

    @Test
    fun switchServerIsOfferedOnlyWithAnotherServerSignedIn() {
        assertEquals(LockExits(switchTo = emptyList()), lockExits("a", listOf("a")))
        assertEquals(listOf("a", "c"), lockExits("b", listOf("a", "b", "c")).switchTo)
        assertEquals(listOf("b"), lockExits("a", listOf("a", "b", "b")).switchTo)
    }

    @Test
    fun signOutIsAlwaysOffered() {
        assertTrue(lockExits("a", listOf("a")).signOut)
        assertTrue(lockExits("a", listOf("a", "b")).signOut)
        // Even when the list no longer has it (it is being removed), the person can still leave.
        assertTrue(lockExits("a", emptyList()).signOut)
    }

    @Test
    fun aSwitchLeavesWhatWaitedForTheLockedServer() {
        val w = WaitingBehindLock<Pending>()
        val tap = Pending("notification of a")
        // Before any exit, nothing is held: the server on screen decides as before.
        assertFalse(w.waitsElsewhere(tap, "b"))
        w.switchedAway(tap, "a")
        // On the other server it is not acted on (a notification of a would switch straight back).
        assertTrue(w.waitsElsewhere(tap, "b"))
        // Back on a, it goes on after a's unlock.
        assertFalse(w.waitsElsewhere(tap, "a"))
        w.done(tap)
        assertFalse(w.waitsElsewhere(tap, "b"))
    }

    @Test
    fun aSecondSwitchKeepsItWaitingForTheFirstServer() {
        val w = WaitingBehindLock<Pending>()
        val share = Pending("share")
        w.switchedAway(share, "a")
        // From b's lock on to c: it still waits for a.
        w.switchedAway(share, "b")
        assertTrue(w.waitsElsewhere(share, "b"))
        assertTrue(w.waitsElsewhere(share, "c"))
        assertFalse(w.waitsElsewhere(share, "a"))
    }

    @Test
    fun somethingNewArrivingAfterTheSwitchIsNotHeld() {
        val w = WaitingBehindLock<Pending>()
        w.switchedAway(Pending("old"), "a")
        assertFalse(w.waitsElsewhere(Pending("new"), "b"))
    }

    @Test
    fun signingOutDropsWhatWaitedForThatServer() {
        val w = WaitingBehindLock<Pending>()
        // Waiting behind a's lock, never switched away from: dropped with a.
        assertTrue(w.signedOut(Pending("tap"), "a"))
        // Held for a after a switch, then signed out of a: dropped too, and no longer held.
        val held = Pending("held")
        w.switchedAway(held, "a")
        assertTrue(w.signedOut(held, "a"))
        assertFalse(w.waitsElsewhere(held, "b"))
        // Nothing pending: nothing to drop.
        assertFalse(w.signedOut(null, "a"))
    }

    @Test
    fun signingOutOfAnotherServerKeepsWhatWaitsForTheFirst() {
        val w = WaitingBehindLock<Pending>()
        val held = Pending("held for a")
        w.switchedAway(held, "a")
        // Signed out of b from b's lock: it was a's, so it stays, still waiting for a.
        assertFalse(w.signedOut(held, "b"))
        assertTrue(w.waitsElsewhere(held, "c"))
        assertFalse(w.waitsElsewhere(held, "a"))
    }

    @Test
    fun aServerWithItsOwnLockShowsOnlyItsNameInTheList() {
        assertEquals("@victor · chat.example.com", lockSheetHint(asksUnlock = false, username = "victor", url = "https://chat.example.com"))
        assertNull(lockSheetHint(asksUnlock = true, username = "victor", url = "https://chat.example.com"))
    }
}
