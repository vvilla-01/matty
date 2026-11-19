package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.ArrivalOrder
import ceo.villa.matty.plugins.villanet.Later
import ceo.villa.matty.plugins.villanet.OnceLater
import ceo.villa.matty.plugins.villanet.PairState
import ceo.villa.matty.plugins.villanet.SingleFlight
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.askWhilePlaying
import ceo.villa.matty.plugins.villanet.pairState
import ceo.villa.matty.plugins.villanet.worthAnotherTry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often VillaNET is asked things: the round of reads, and the check while a stream plays. */
class VillaNetPaceTest {
    private fun now() = System.nanoTime() / 1_000_000

    @Test
    fun aBurstOfReasonsToCatchUpIsTwoRounds() = runBlocking {
        val starts = ArrayList<Long>()
        // Each round's start as the flight itself took it: the gap is kept by its own clock, and a
        // second reading of the clock a moment later can be a millisecond on.
        lateinit var rounds: SingleFlight
        rounds = SingleFlight(this, gapMs = 150, clock = ::now) { starts += rounds.lastStart!!; delay(20) }

        // Unlocking starts a round. While its reads are out, the event about the unlock lands, the
        // panel opens, a refusal comes back, another event lands.
        rounds.request()
        delay(5)
        assertTrue(rounds.running)
        repeat(4) { rounds.request() }
        rounds.join()
        assertEquals("the one in flight, and one more for everything asked meanwhile", 2, starts.size)
        assertTrue("never two starting within the gap (${starts[1] - starts[0]} ms)", starts[1] - starts[0] >= 150)

        // Asked again straight after: it runs, but not before the gap is up.
        rounds.request()
        rounds.join()
        assertEquals(3, starts.size)
        assertTrue(starts[2] - starts[1] >= 150)

        // Asked several times before a round has begun its reads, that round answers them all.
        delay(200)
        repeat(3) { rounds.request() }
        rounds.join()
        assertEquals(4, starts.size)

        // Long after the last one, there is nothing to wait for.
        delay(200)
        val asked = now()
        rounds.request()
        rounds.join()
        assertEquals(5, starts.size)
        assertTrue("at once (${starts[4] - asked} ms)", starts[4] - asked < 100)
    }

    @Test
    fun aRoundThatFailsDoesNotStopTheNext() = runBlocking {
        var runs = 0
        val rounds = SingleFlight(this, gapMs = 10, clock = ::now) { runs++; if (runs == 1) throw java.io.IOException("offline") }
        rounds.request()
        rounds.join()
        rounds.request()
        rounds.join()
        assertEquals(2, runs)

        // Cancelled (the server left): nothing runs after, and a new start is a clean one.
        rounds.request()
        rounds.cancel()
        yield()
        assertEquals(null, rounds.lastStart)
        assertEquals(false, rounds.running)
    }

    @Test
    fun accessIsAskedAgainForAsLongAsAStreamPlays() = runBlocking {
        val playing = MutableStateFlow<String?>(null)
        var asked = 0
        val watch = launch { askWhilePlaying(playing, everyMs = 30) { asked++ } }

        delay(120)
        assertEquals("nothing plays, nothing is asked", 0, asked)

        playing.value = "h1"
        delay(200)
        val during = asked
        assertTrue("asked again and again while it plays ($during)", during in 3..7)

        // Another stream: the asking carries on, counted from when it started.
        playing.value = "h2"
        delay(100)
        assertTrue(asked > during)

        playing.value = null
        delay(20)
        val after = asked
        delay(120)
        assertEquals("stopped: nothing more is asked", after, asked)
        watch.cancelAndJoin()
    }

    @Test
    fun anOlderVillaNetIsKnownBeforeItIsAsked() {
        // By the version Mattermost's own plugin list gives, with nothing asked of the plugin.
        assertEquals(PairState.TooOld, pairState("3.8.0", null))
        assertEquals(PairState.TooOld, pairState("3.2.1", null))
        assertEquals(PairState.Ok, pairState(VnAccess.SINCE, null))
        assertEquals(PairState.Ok, pairState("4.0.0", null))
        assertEquals(PairState.Ok, pairState("4.0.0+standin", null))
        // The list didn't say: ask, and go by the contract it speaks.
        assertEquals(PairState.Ok, pairState("", null))
        assertEquals(PairState.TooOld, pairState("", VnAccess.CONTRACT - 1))
        assertEquals(PairState.Ok, pairState("", VnAccess.CONTRACT))
        // The contract decides, whatever the version: additions never raise it.
        assertEquals(PairState.Ok, pairState("4.3.0", VnAccess.CONTRACT))
        assertEquals(PairState.Newer, pairState("5.0.0", VnAccess.CONTRACT + 1))
        assertEquals(PairState.TooOld, pairState("3.8.0", VnAccess.CONTRACT - 1))
        // And an old version is old even if something answered for it.
        assertEquals(PairState.TooOld, pairState("3.4.0", VnAccess.CONTRACT))
    }

    @Test
    fun anAnswerThatSaysAskAgainIsAskedAgainOnceInAWhile() = runBlocking {
        var asked = 0
        lateinit var again: OnceLater
        // "unknown" twice more, then an answer: each "unknown" asks for one more read.
        again = OnceLater(this, waitMs = 100) { asked++; if (asked < 3) again.request() }
        again.request()
        again.request()
        again.request()
        delay(40)
        assertEquals("not at once", 0, asked)
        delay(110)
        assertEquals("once, however often it was asked for", 1, asked)
        delay(250)
        assertEquals("and again for each answer that asked for it, until one didn't", 3, asked)
        delay(150)
        assertEquals(3, asked)

        // An answer that settles it first: the read waiting is not made.
        again.request()
        again.cancel()
        delay(150)
        assertEquals(3, asked)
    }

    @Test
    fun aReadSetForLaterIsSetOnceAndANewWaitReplacesIt() = runBlocking {
        var done = 0
        val later = Later(this) { done++ }
        // Set for a while, then set again sooner: only the new wait stands.
        later.after(400)
        delay(100)
        later.after(100)
        assertTrue(later.waiting)
        delay(60)
        assertEquals("not before its wait", 0, done)
        delay(100)
        assertEquals("once, at the new wait", 1, done)
        assertFalse(later.waiting)
        delay(300)
        assertEquals("the wait it replaced does nothing", 1, done)
        // Set again later: the new wait counts from now.
        later.after(200)
        delay(100)
        later.after(200)
        delay(150)
        assertEquals(1, done)
        delay(100)
        assertEquals(2, done)
        // Cancelled (the person left this server): nothing.
        later.after(100)
        later.cancel()
        assertFalse(later.waiting)
        delay(200)
        assertEquals(2, done)
    }

    @Test
    fun snapshotsAreShownInTheOrderTheyArrived() {
        val order = ArrivalOrder()
        // Two pushes of the same poll; the second is decoded first.
        val first = order.pushed()
        val second = order.pushed()
        assertTrue(order.mayShowPush(second)); order.showed(second)
        assertFalse("the earlier one never replaces it", order.mayShowPush(first))

        // A read that started before a push that was then shown loses to it…
        val early = order.reading()
        val third = order.pushed()
        assertTrue(order.mayShowPush(third)); order.showed(third)
        assertFalse(order.mayShowRead(early))
        // …and one that started after it doesn't, and is newer than any push that came before.
        val late = order.reading()
        assertTrue(order.mayShowRead(late)); order.showed(late)
        assertFalse(order.mayShowPush(third))
        // A push that came after the read started is newer than its answer.
        val fourth = order.pushed()
        assertTrue(order.mayShowPush(fourth))
    }

    @Test
    fun aReadThatFailedIsTriedOnceMoreUnlessItWasRefused() {
        assertTrue(worthAnotherTry(java.io.IOException("offline")))
        assertTrue(worthAnotherTry(VillaNetException("", "VillaNET returned 502", 502)))
        assertTrue(worthAnotherTry(VillaNetException("store", "Steam could not be read just now.", 503, "Steam could not be read just now.")))
        assertFalse(worthAnotherTry(VillaNetException("locked", "VillaNET is locked.", 403, "VillaNET is locked.")))
        assertFalse(worthAnotherTry(VillaNetException("", "VillaNET returned 404", 404)))
    }
}
