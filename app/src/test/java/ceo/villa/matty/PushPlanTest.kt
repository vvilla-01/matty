package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnPushState
import ceo.villa.matty.push.Clears
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.push.PushPlan
import ceo.villa.matty.push.PushPlan.Next
import ceo.villa.matty.push.PushPlan.Why
import ceo.villa.matty.push.PushRegistration
import ceo.villa.matty.push.PushSide
import ceo.villa.matty.push.PushStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Matty says to a server about one registration, from what the server answered and the clock. */
class PushPlanTest {
    private val vapid = "B" + "A".repeat(86)
    private val endpoint = "https://push.example.com/a"
    private val none = PushRegistration(
        serverId = "s1", distributor = "org.example.distributor", token = "t0", vapid = vapid, description = "Chat",
        p256dh = "pub", sealedKey = "k", sealedAuth = "a",
    )
    private val delivering = none.on(PushRegistration.Event.NewEndpoint(endpoint)) { "t1" }.registration!!
    private val confirmed = delivering.on(PushRegistration.Event.ServerConfirmed(endpoint)) { "t1" }.registration!!
    private val now = 1_800_000_000_000L

    private fun state(s: String, host: String = "", status: Int = 0, kind: String = "", since: Long = 0) = VnPushState(state = s, host = host, status = status, kind = kind, since = since)

    @Test fun aLookThatOnlyReadsLeavesTheOnePutByItselfForLater() {
        val working = PushPlan.atStart(state("confirmed", "ntfy.example.com"), confirmed, PushSide(), now).second
        for (word in listOf("refused", "failing")) {
            val answer = state(word, "ntfy.example.com", status = 403, kind = "answer", since = now)
            val decided = PushPlan.atStart(answer, confirmed, working, now + 10_000)
            assertEquals("an ordinary look sends the address once by itself", Next.PUT, decided.first)
            assertTrue(decided.second.retried)

            val (next, read) = PushPlan.readOnly(decided, answer, working)
            assertEquals(Next.NOTHING, next)
            assertEquals("the server's word is kept, for the line", word, read.state)
            assertEquals("ntfy.example.com", read.host)
            assertFalse("the one PUT by itself is still to be made", read.retried)
            // The next ordinary look makes it, once.
            val later = PushPlan.atStart(answer, confirmed, read, now + 60_000)
            assertEquals(Next.PUT, later.first)
            assertEquals(Next.NOTHING, PushPlan.atStart(answer, confirmed, later.second, now + 120_000).first)
            // One already made: nothing either way.
            val made = PushPlan.atStart(answer, confirmed, later.second, now + 120_000)
            assertEquals(made, PushPlan.readOnly(made, answer, later.second))
        }
        // Everything else goes as an ordinary look decides: an address the server does not hold, one owed, a confirm owed.
        val gone = PushPlan.atStart(state("none"), delivering, PushSide(), now)
        assertEquals(Next.PUT, gone.first)
        assertEquals(gone, PushPlan.readOnly(gone, state("none"), PushSide()))
        val owing = working.copy(owesPut = true)
        val owed = PushPlan.atStart(state("refused", "ntfy.example.com", 403), confirmed, owing, now)
        assertEquals(Next.PUT, owed.first)
        assertEquals(owed, PushPlan.readOnly(owed, state("refused", "ntfy.example.com", 403), owing))
        val nonce = PushSide(owedNonce = "n1")
        val confirm = PushPlan.atStart(state("pending"), delivering, nonce, now)
        assertEquals(Next.CONFIRM, confirm.first)
        assertEquals(confirm, PushPlan.readOnly(confirm, state("pending"), nonce))
    }

    @Test fun aRegistrationWithNoAddressYetWaitsForItsNewEndpoint() {
        assertEquals(Next.NOTHING, PushPlan.atStart(state("none"), none, PushSide(), now).first)
    }

    @Test fun aServerThatHoldsNothingOrAnOldKeyIsGivenTheAddress() {
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), delivering, PushSide(), now).first)
        assertEquals(Next.PUT, PushPlan.atStart(state("refresh"), confirmed, PushSide(), now).first)
    }

    @Test fun aConfirmedAddressThatIsThisOneNeedsNothing() {
        val (next, side) = PushPlan.atStart(state("confirmed", "push.example.com"), confirmed, PushSide(puts = 2, unheard = true), now)
        assertEquals(Next.NOTHING, next)
        assertEquals("confirmed", side.state)
        assertEquals("push.example.com", side.host)
        assertEquals(0, side.puts)
        assertFalse(side.unheard)
        // The server confirmed some address, but not one known to be this: the same PUT again costs nothing.
        assertEquals(Next.PUT, PushPlan.atStart(state("confirmed"), delivering, PushSide(), now).first)
    }

    @Test fun aHelloIsWaitedForThenAskedForAgainAtMostTwiceMore() {
        val first = PushPlan.afterPut(state("pending", "push.example.com"), PushSide(), endpoint, Why.ENDPOINT, now)
        assertEquals(1, first.puts)
        assertEquals(now, first.pendingAt)
        assertEquals(endpoint, first.sent)
        // Not yet: the hello has 45 seconds.
        assertFalse(PushPlan.helloDue(first, now + 44_000))
        assertEquals(Next.NOTHING, PushPlan.helloMissing(first, state("pending"), false, now + 44_000).first)
        assertEquals(Next.NOTHING, PushPlan.atStart(state("pending"), delivering, first, now + 44_000).first)
        // Then, while the server still waits, the same address again, twice more.
        assertTrue(PushPlan.helloDue(first, now + PushPlan.HELLO_WAIT_MS))
        assertEquals(Next.PUT, PushPlan.helloMissing(first, state("pending"), false, now + PushPlan.HELLO_WAIT_MS).first)
        val second = PushPlan.afterPut(state("pending"), first, endpoint, Why.REPEAT, now + 46_000)
        assertEquals(2, second.puts)
        val third = PushPlan.afterPut(state("pending"), second, endpoint, Why.REPEAT, now + 92_000)
        assertEquals(PushPlan.MAX_PUTS, third.puts)
        // And then, the server still waiting, the distributor is said not to deliver, and nothing more is sent by itself.
        val (next, unheard) = PushPlan.helloMissing(third, state("pending"), false, now + 200_000)
        assertEquals(Next.NOTHING, next)
        assertTrue(unheard.unheard)
        assertEquals(Next.NOTHING, PushPlan.atStart(state("pending"), delivering, unheard, now + 10_000_000).first)
        // The next start after a process that died while waiting does it too.
        assertEquals(Next.PUT, PushPlan.atStart(state("pending"), delivering, second, now + 200_000).first)
        assertTrue(PushPlan.atStart(state("pending"), delivering, third, now + 200_000).second.unheard)
    }

    @Test fun aNewAddressStartsTheCountAgain() {
        val tired = PushSide(state = "pending", pendingAt = now, puts = 3, unheard = true, sent = endpoint)
        val fresh = PushPlan.afterPut(state("pending"), tired, "https://push.example.com/b", Why.ENDPOINT, now + 1)
        assertEquals(1, fresh.puts)
        assertFalse(fresh.unheard)
        assertEquals("https://push.example.com/b", fresh.sent)
    }

    @Test fun aConfirmEndsTheWait() {
        val waiting = PushSide(state = "pending", pendingAt = now, puts = 2, owedNonce = "n")
        val after = PushPlan.afterConfirm(state("confirmed", "push.example.com"), waiting)
        assertEquals("confirmed", after.state)
        assertEquals(0L, after.pendingAt)
        assertEquals(0, after.puts)
        assertEquals("", after.owedNonce)
        assertEquals(Next.NOTHING, PushPlan.helloMissing(after, state("pending"), false, now + 1_000_000).first)
    }

    @Test fun anOverdueHelloAsksTheServerWhyBeforeBlamingTheDistributor() {
        val waiting = PushPlan.afterPut(state("pending", "ntfy.example.com"), PushSide(), endpoint, Why.ENDPOINT, now)
        val later = now + PushPlan.HELLO_WAIT_MS
        // The push server refused the hello: kept as the server says, nothing sent again, and not "not delivered".
        val (next, refused) = PushPlan.helloMissing(waiting, state("refused", "ntfy.example.com", 403, since = now + 3_000), false, later)
        assertEquals(Next.NOTHING, next)
        assertEquals("refused", refused.state)
        assertEquals(403, refused.status)
        assertFalse(refused.unheard)
        assertFalse("no longer waiting", PushPlan.helloDue(refused, later + 1))
        // ... and that is this refusal's one answer: the next start does not send it again.
        assertEquals(Next.NOTHING, PushPlan.atStart(state("refused", "ntfy.example.com", 403, since = now + 3_000), delivering, refused, later + 2).first)
        // Failing too.
        val (again, failing) = PushPlan.helloMissing(waiting, state("failing", "ntfy.example.com", kind = "unreachable"), false, later)
        assertEquals(Next.NOTHING, again)
        assertEquals("failing", failing.state)
        // Confirmed for this very endpoint after all: the wait is over.
        val (done, confirmed) = PushPlan.helloMissing(waiting, state("confirmed"), true, later)
        assertEquals(Next.NOTHING, done)
        assertEquals("confirmed", confirmed.state)
        assertEquals(0L, confirmed.pendingAt)
        // The server holds none (its wait ran out) or another one: the address again.
        assertEquals(Next.PUT, PushPlan.helloMissing(waiting, state("none"), false, later).first)
        assertEquals(Next.PUT, PushPlan.helloMissing(waiting, state("confirmed"), false, later).first)
        // After the last try, the distributor is blamed only while the server still waits.
        val last = waiting.copy(puts = PushPlan.MAX_PUTS)
        assertTrue(PushPlan.helloMissing(last, state("pending"), false, later).second.unheard)
        assertFalse(PushPlan.helloMissing(last, state("none"), false, later).second.unheard)
        assertEquals(Next.NOTHING, PushPlan.helloMissing(last, state("none"), false, later).first)
    }

    @Test fun aRefusalAfterTheServerConfirmedGetsItsOwnPut() {
        val first = state("refused", "ntfy.example.com", 403, since = now)
        val (_, side) = PushPlan.atStart(first, confirmed, PushSide(), now)
        assertEquals(now, side.since)
        assertTrue(side.retried)
        // Fixed and confirmed: the one PUT is owed again for the next refusal, weeks later, with the same status.
        val (_, working) = PushPlan.atStart(state("confirmed"), confirmed, side, now + 1)
        assertFalse(working.retried)
        val again = state("refused", "ntfy.example.com", 403, since = now + 3_000_000_000L)
        assertEquals(Next.PUT, PushPlan.atStart(again, confirmed, working, now + 3_000_000_001L).first)
    }

    @Test fun anAddressTheServerRefusedForGoodIsNotSentAgain() {
        val rejected = PushSide(offer = PushSide.OFFER_ON, refusal = "This server sends pushes only to the push servers its admin listed.", rejected = endpoint)
        assertEquals(Next.NOTHING, PushPlan.atStart(state("none"), delivering, rejected, now).first)
        assertEquals("the sentence stays", rejected.refusal, PushPlan.atStart(state("none"), delivering, rejected, now).second.refusal)
        // Another endpoint goes, and once the server takes one the refusal is over.
        val moved = delivering.on(PushRegistration.Event.NewEndpoint("https://push.example.com/b")) { "t9" }.registration!!
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), moved, rejected, now).first)
        val taken = PushPlan.afterPut(state("pending"), rejected, "https://push.example.com/b", Why.ENDPOINT, now)
        assertEquals("", taken.refusal)
        assertEquals("", taken.rejected)
        // The app check's refusal is owed, and the PUT goes again.
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), delivering, PushSide(refusal = "Only the official Matty.", owesPut = true), now).first)
        assertFalse("the address is never in what is logged", rejected.toString().contains(endpoint))
    }

    @Test fun aDeleteIsSettledOnlyByAnAnswer() {
        assertTrue(PushPlan.deleteDone(null))
        // Mattermost's 404 while the plugin is not running: the plugin itself answers 200 for nothing there.
        assertFalse(PushPlan.deleteDone(VillaNetException("", "Not found", 404)))
        assertFalse(PushPlan.deleteDone(java.io.IOException("offline")))
        assertFalse(PushPlan.deleteDone(VillaNetException("app_unverified", "no", 403)))
        assertFalse(PushPlan.deleteDone(VillaNetException("", "Unavailable", 503)))
    }

    @Test fun anOwedDeleteIsPaidOnlyWhenTheServerAnswers() {
        val store = PushStore(MemoryPrefs(), FakeSealer())
        store.oweDelete("s1")
        assertFalse(PushPlan.settleDelete(store, "s1", java.io.IOException("offline")))
        assertEquals(setOf("s1"), store.owedDeletes())
        assertFalse(PushPlan.settleDelete(store, "s1", VillaNetException("", "Not found", 404)))
        assertEquals(setOf("s1"), store.owedDeletes())
        assertTrue(PushPlan.settleDelete(store, "s1", null))
        assertEquals(emptySet<String>(), store.owedDeletes())
    }

    @Test fun theStartSendsAnOwedDeleteWithPushOnOrOff() {
        val off = PushPlan.atProcessStart(enabled = false, owed = setOf("s1"))
        assertEquals(setOf("s1"), off.deletes)
        assertTrue("listened for, to pay it when the network is back", off.listen)
        assertFalse(off.register)
        val on = PushPlan.atProcessStart(enabled = true, owed = emptySet())
        assertEquals(emptySet<String>(), on.deletes)
        assertTrue(on.listen)
        assertTrue(on.register)
        assertFalse(PushPlan.atProcessStart(enabled = false, owed = emptySet()).listen)
    }

    @Test fun whatARefusalLeavesOfTheSide() {
        val owe = { s: PushSide -> s.copy(owesPut = true) }
        val side = PushSide(offer = PushSide.OFFER_ON)
        // An address the server will not take: its sentence, kept from being sent again, not owed.
        val listed = PushPlan.afterRefusal(side, VillaNetException("bad_endpoint", "Not on the list.", 400, "Not on the list."), owe, endpoint)
        assertEquals("Not on the list.", listed.refusal)
        assertEquals(endpoint, listed.rejected)
        assertFalse(listed.owesPut)
        assertEquals(PushPlan.REJECTED, PushPlan.afterRefusal(side, VillaNetException("bad_endpoint", "", 400, " "), owe, endpoint).refusal)
        // The app check: owed with its sentence.
        val check = PushPlan.afterRefusal(side, VillaNetException("app_unverified", "Only the official Matty.", 403, "Only the official Matty."), owe, endpoint)
        assertTrue(check.owesPut)
        assertEquals("Only the official Matty.", check.refusal)
        assertEquals("", check.rejected)
        // No network, a limit, and Mattermost's 404 while the plugin is not running: owed.
        assertEquals(side.copy(owesPut = true), PushPlan.afterRefusal(side, java.io.IOException("offline"), owe, endpoint))
        assertEquals(side.copy(owesPut = true), PushPlan.afterRefusal(side, VillaNetException("rate_limited", "Wait.", 429, "Wait.", 5_000), owe, endpoint))
        assertEquals(side.copy(owesPut = true), PushPlan.afterRefusal(side, VillaNetException("", "Not found", 404), owe, endpoint))
        val nonce = PushPlan.afterRefusal(side, VillaNetException("", "Not found", 404), { s: PushSide -> s.copy(owedNonce = "n") })
        assertEquals("n", nonce.owedNonce)
        // A key that could not be replaced is owed, not refused for good, though it is a 400.
        val key = PushPlan.afterRefusal(side, VillaNetException("push_key", "Old key.", 400, "Old key."), owe, endpoint)
        assertTrue(key.owesPut)
        assertEquals("", key.rejected)
        // Push switched off there: its sentence, and nothing owed.
        val off = PushPlan.afterRefusal(side, VillaNetException("unavailable", "Push is off here.", 503, "Push is off here."), owe, endpoint)
        assertEquals(PushSide.OFFER_OFF, off.offer)
        assertEquals("Push is off here.", off.reason)
        assertFalse(off.owesPut)
    }

    @Test fun anOwedPutIsTriedAgainAfterTheServersWaitThenTwiceMoreDoubling() {
        assertEquals(30_000L, PushPlan.owedWait(0, 0))
        assertEquals(60_000L, PushPlan.owedWait(1, 0))
        assertEquals(120_000L, PushPlan.owedWait(2, 0))
        assertEquals(null, PushPlan.owedWait(3, 0))
        // The server named its wait (a 429's retry_after_ms).
        assertEquals(5_000L, PushPlan.owedWait(0, 5_000))
        assertEquals(10_000L, PushPlan.owedWait(1, 5_000))
        // Ten minutes at most.
        assertEquals(PushPlan.OWED_MAX_WAIT_MS, PushPlan.owedWait(2, 400_000))
        assertTrue(PushPlan.owesSend(PushSide(owesPut = true)))
        assertTrue(PushPlan.owesSend(PushSide(owedNonce = "n")))
        assertFalse("a look is the start's and the network's", PushPlan.owesSend(PushSide(unreachable = true)))
    }

    @Test fun tryAgainSendsAnAddressTheServerRefusedForGood() {
        val rejected = PushSide(refusal = "Not on the list.", rejected = endpoint)
        val again = PushPlan.beforeTryAgain(rejected)
        assertEquals("", again.rejected)
        assertEquals("", again.refusal)
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), delivering, again, now).first)
        // The app check's sentence stays until the server takes the address.
        val check = PushSide(refusal = "Only the official Matty.", owesPut = true)
        assertEquals(check, PushPlan.beforeTryAgain(check))
    }

    @Test fun pushWorksOnlyOnTheServersConfirmedWord() {
        val on = PushSide(offer = PushSide.OFFER_ON, state = "confirmed")
        assertTrue(PushPlan.works(confirmed, on))
        for (state in listOf("pending", "none", "refused", "failing", "refresh", "")) assertFalse(state, PushPlan.works(confirmed, on.copy(state = state)))
        assertFalse(PushPlan.works(confirmed, on.copy(offer = PushSide.OFFER_OFF)))
        assertFalse("not confirmed here", PushPlan.works(delivering, on))
        assertFalse(PushPlan.works(confirmed.on(PushRegistration.Event.TempUnavailable) { "x" }.registration!!, on))
    }

    @Test fun aRefusedOrFailingRegistrationGetsOnePutUntilTheServerSaysOtherwise() {
        val s0 = state("refused", "ntfy.example.com", 403, since = now - 86_400_000)
        val (next, side) = PushPlan.atStart(s0, confirmed, PushSide(), now)
        assertEquals(Next.PUT, next)
        assertEquals("refused", side.state)
        assertEquals(403, side.status)
        assertTrue(side.retried)
        assertEquals(Next.NOTHING, PushPlan.atStart(s0, confirmed, side, now + 1).first)
        // That PUT's hello is refused too, and the server speaks of it with its own since; later, of the main side's again.
        val waiting = PushPlan.afterPut(state("pending"), side, endpoint, Why.START, now + 2)
        val s1 = state("refused", "ntfy.example.com", 403, since = now + 3_000)
        val (none, heard) = PushPlan.helloMissing(waiting, s1, false, now + 2 + PushPlan.HELLO_WAIT_MS)
        assertEquals(Next.NOTHING, none)
        assertEquals(Next.NOTHING, PushPlan.atStart(s0, confirmed, heard, now + 20 * 60_000).first)
        assertEquals(Next.NOTHING, PushPlan.atStart(s1, confirmed, heard, now + 20 * 60_000).first)
        // Failing after refused, or another status: still the one PUT made.
        assertEquals(Next.NOTHING, PushPlan.atStart(state("failing", "ntfy.example.com", kind = "unreachable"), confirmed, heard, now + 4).first)
        assertEquals(Next.NOTHING, PushPlan.atStart(state("refused", "ntfy.example.com", 401), confirmed, heard, now + 5).first)
        // A hello refused when no PUT was made at a start counts as that PUT.
        val fresh = PushPlan.afterPut(state("pending"), PushSide(), endpoint, Why.ENDPOINT, now)
        val (_, refusedHello) = PushPlan.helloMissing(fresh, s1, false, now + PushPlan.HELLO_WAIT_MS)
        assertEquals(Next.NOTHING, PushPlan.atStart(s0, confirmed, refusedHello, now + 20 * 60_000).first)
        // The server answers confirmed or refresh: the next refusal gets its one PUT.
        for (answer in listOf("confirmed", "refresh")) {
            val (_, cleared) = PushPlan.atStart(state(answer), confirmed, heard, now + 6)
            assertFalse(answer, cleared.retried)
            assertEquals(answer, Next.PUT, PushPlan.atStart(s0, confirmed, cleared, now + 7).first)
        }
        // Its none for this same address is the same try; for another address, a fresh one.
        assertEquals(Next.NOTHING to heard, PushPlan.atStart(state("none"), confirmed, heard, now + 6))
        val (_, other) = PushPlan.atStart(state("none"), confirmed, heard.copy(sent = "https://push.example.com/old"), now + 6)
        assertFalse(other.retried)
        assertEquals(Next.PUT, PushPlan.atStart(s0, confirmed, other, now + 7).first)
        assertFalse(PushPlan.helloMissing(waiting, state("none"), false, now + 2 + PushPlan.HELLO_WAIT_MS).second.retried)
        assertFalse(PushPlan.afterConfirm(state("confirmed"), heard).retried)
        // A new address: the one PUT is owed again.
        assertFalse(PushPlan.afterPut(state("pending"), heard, "https://push.example.com/b", Why.ENDPOINT, now + 8).retried)
        assertTrue("the same address again keeps it", PushPlan.afterPut(state("pending"), heard, endpoint, Why.REPEAT, now + 8).retried)
        // The start's PUT (and Try again's) is that one PUT: it keeps it, whatever it is answered.
        for (answer in listOf("pending", "refused")) {
            val started = PushPlan.afterPut(state(answer), heard, endpoint, Why.START, now + 8)
            assertTrue(answer, started.retried)
            assertEquals(answer, Next.NOTHING, PushPlan.atStart(s0, confirmed, started.copy(state = "refused"), now + 9).first)
        }
        // A PUT owed is sent whatever the server says.
        assertEquals(Next.PUT, PushPlan.atStart(s0, confirmed, heard.copy(owesPut = true), now + 9).first)
    }

    @Test fun theSameAddressAfterARefusalIsTheSameTry() {
        // A first registration: the hello for its address is refused, or cannot be delivered.
        val fresh = PushPlan.afterPut(state("pending"), PushSide(), endpoint, Why.ENDPOINT, now)
        for (s1 in listOf(state("refused", "ntfy.example.com", 403, since = now + 3_000), state("failing", "ntfy.example.com", kind = "unreachable"))) {
            val (_, tried) = PushPlan.helloMissing(fresh, s1, false, now + PushPlan.HELLO_WAIT_MS)
            assertTrue(s1.state, tried.retried)
            assertTrue(s1.state, PushPlan.sameTry(tried, endpoint))
            // The UnifiedPush app gives the same address again at a start: the same try, so the PUT keeps it.
            assertTrue(s1.state, PushPlan.afterPut(state("pending"), tried, endpoint, Why.ENDPOINT, now + 50_000).retried)
            // Ten minutes on, the server let it go: nothing is sent, and the line (and its Try again) stays.
            val (next, kept) = PushPlan.atStart(state("none"), delivering, tried, now + 11 * 60_000)
            assertEquals(s1.state, Next.NOTHING, next)
            assertEquals(s1.state, tried, kept)
            // A new address starts afresh.
            assertFalse(s1.state, PushPlan.sameTry(tried, "https://push.example.com/b"))
            val moved = delivering.on(PushRegistration.Event.NewEndpoint("https://push.example.com/b")) { "t9" }.registration!!
            assertEquals(s1.state, Next.PUT, PushPlan.atStart(state("none"), moved, tried, now + 11 * 60_000).first)
            assertFalse(s1.state, PushPlan.afterPut(state("pending"), tried, "https://push.example.com/b", Why.ENDPOINT, now + 50_000).retried)
            // The server's confirmed or refresh starts afresh too.
            for (answer in listOf("confirmed", "refresh")) {
                assertEquals("${s1.state} then $answer", Next.PUT, PushPlan.atStart(state(answer), delivering, tried, now + 11 * 60_000).first)
            }
        }
        // Not the same try: the one PUT not yet made, a hello still awaited, no address.
        val refused = PushSide(state = "refused", sent = endpoint, retried = true)
        assertFalse(PushPlan.sameTry(refused.copy(retried = false), endpoint))
        assertFalse(PushPlan.sameTry(refused.copy(state = "pending"), endpoint))
        assertFalse(PushPlan.sameTry(refused, null))
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), delivering, refused.copy(retried = false), now).first)
        // A PUT that did not reach the server is still owed.
        assertEquals(Next.PUT, PushPlan.atStart(state("none"), delivering, refused.copy(owesPut = true), now).first)
    }

    @Test fun comingToTheFrontAsksAServerThatOwesOrHasNoRegistrationYet() {
        val on = PushSide.OFFER_ON
        assertTrue("owes", PushPlan.askedAtFront(owes = true, usable = false, registered = true, stopped = false, offer = on))
        assertTrue("owes, whatever else", PushPlan.askedAtFront(owes = true, usable = false, registered = false, stopped = true, offer = PushSide.OFFER_NONE))
        assertTrue("not registered yet, the app takes REGISTER", PushPlan.askedAtFront(owes = false, usable = true, registered = false, stopped = false, offer = on))
        assertTrue("not registered yet, its offer not known yet", PushPlan.askedAtFront(owes = false, usable = true, registered = false, stopped = false, offer = ""))
        assertFalse("registered, nothing owed", PushPlan.askedAtFront(owes = false, usable = true, registered = true, stopped = false, offer = on))
        assertFalse("the app takes no REGISTER", PushPlan.askedAtFront(owes = false, usable = false, registered = false, stopped = false, offer = on))
        assertFalse("stopped", PushPlan.askedAtFront(owes = false, usable = true, registered = false, stopped = true, offer = on))
        // A server that does not offer push can never be registered: not asked at every return.
        for (offer in listOf(PushSide.OFFER_NONE, PushSide.OFFER_OFF, PushSide.OFFER_OLD)) {
            assertFalse("offer $offer", PushPlan.askedAtFront(owes = false, usable = true, registered = false, stopped = false, offer = offer))
        }
    }

    @Test fun whatAPushCouldNotSendIsSentAtTheNextStart() {
        assertEquals(Next.PUT, PushPlan.atStart(state("confirmed"), confirmed, PushSide(owesPut = true), now).first)
        assertEquals(Next.CONFIRM, PushPlan.atStart(state("pending"), delivering, PushSide(owedNonce = "n", pendingAt = now), now + 1).first)
        // A put that went through clears what was owed.
        assertFalse(PushPlan.afterPut(state("pending"), PushSide(owesPut = true), endpoint, Why.START, now).owesPut)
    }

    @Test fun neverTheEndpointOrANonceInWhatIsLogged() {
        val side = PushSide(sent = endpoint, owedNonce = "secret-nonce", host = "push.example.com")
        assertFalse(side.toString().contains(endpoint))
        assertFalse(side.toString().contains("secret-nonce"))
    }

    // ---- clears ------------------------------------------------------------------------------

    private fun message(channel: String, root: String, crt: Boolean, at: Long) = PushPayload.Message(
        user = "u", post = "p$at", channel = channel, root = root, team = "t", sender = "b", senderName = "bob",
        channelName = "Town Square", channelType = "O", text = "hi", crt = crt, idLoaded = false, fromWebhook = false, at = at,
    )

    @Test fun aMessageReadElsewhereBeforeItCameIsNotShown() {
        val clears = Clears()
        clears.remember("s1", PushPayload.Clear("u", "c1", "", false, at = 100))
        assertTrue(clears.hides("s1", message("c1", "", false, at = 99)))
        assertTrue("not newer is not shown", clears.hides("s1", message("c1", "", false, at = 100)))
        assertFalse(clears.hides("s1", message("c1", "", false, at = 101)))
        assertFalse("another server's channel", clears.hides("s2", message("c1", "", false, at = 99)))
        assertFalse("another channel", clears.hides("s1", message("c2", "", false, at = 99)))
        // An older clear arriving late doesn't move it back.
        clears.remember("s1", PushPayload.Clear("u", "c1", "", false, at = 50))
        assertTrue(clears.hides("s1", message("c1", "", false, at = 99)))
    }

    @Test fun underCollapsedThreadsAThreadIsItsOwnConversation() {
        val clears = Clears()
        clears.remember("s1", PushPayload.Clear("u", "c1", "r1", true, at = 100))
        assertTrue(clears.hides("s1", message("c1", "r1", true, at = 90)))
        assertFalse("the channel itself wasn't read", clears.hides("s1", message("c1", "", true, at = 90)))
        assertFalse("another thread", clears.hides("s1", message("c1", "r2", true, at = 90)))
        // Without collapsed threads a reply is the channel's.
        clears.remember("s1", PushPayload.Clear("u", "c3", "", false, at = 100))
        assertTrue(clears.hides("s1", message("c3", "r9", false, at = 90)))
    }

    @Test fun theClearsAreBounded() {
        val clears = Clears(max = 2)
        clears.remember("s1", PushPayload.Clear("u", "a", "", false, 100))
        clears.remember("s1", PushPayload.Clear("u", "b", "", false, 100))
        clears.remember("s1", PushPayload.Clear("u", "c", "", false, 100))
        assertFalse("the oldest went", clears.hides("s1", message("a", "", false, 1)))
        assertTrue(clears.hides("s1", message("c", "", false, 1)))
    }
}
