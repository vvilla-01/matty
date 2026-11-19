package ceo.villa.matty

import ceo.villa.matty.push.Confirmed
import ceo.villa.matty.push.FailReason
import ceo.villa.matty.push.PushRegistration
import ceo.villa.matty.push.PushRegistration.Effect
import ceo.villa.matty.push.PushRegistration.Event
import ceo.villa.matty.push.PushRegistration.Phase
import ceo.villa.matty.push.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One registration's life with its distributor and its server, event by event. */
class PushRegistrationTest {
    private val vapid = "B" + "A".repeat(86)
    private val fresh = PushRegistration(
        serverId = "s1", distributor = "org.example.distributor", token = "t0", vapid = vapid, description = "Chat",
        p256dh = "pub", sealedKey = "k", sealedAuth = "a",
    )
    private var made = 0
    private val newToken = { "t${++made}" }

    private fun PushRegistration.after(vararg events: Event): PushRegistration =
        events.fold(this) { r, e -> r.on(e, newToken).registration!! }

    @Test fun startingRegistersTheSameToken() {
        val step = fresh.on(Event.Started, newToken)
        assertEquals(listOf(Effect.Register("t0")), step.effects)
        assertEquals("t0", step.registration!!.token)
        assertEquals(Phase.ASKING, step.registration!!.phase)
    }

    @Test fun aNewEndpointGoesToTheServerUnlessTheServerHasExactlyIt() {
        val first = fresh.on(Event.NewEndpoint("https://push.example.com/a"), newToken)
        assertEquals(listOf(Effect.SendEndpoint("https://push.example.com/a")), first.effects)
        val known = first.registration!!
        assertEquals(Phase.DELIVERING, known.phase)
        assertFalse(known.isConfirmed)
        // Not yet confirmed: the same endpoint again still goes.
        assertEquals(listOf(Effect.SendEndpoint("https://push.example.com/a")), known.on(Event.NewEndpoint("https://push.example.com/a"), newToken).effects)

        val confirmed = known.after(Event.ServerConfirmed("https://push.example.com/a"))
        assertTrue(confirmed.isConfirmed)
        assertEquals(Confirmed("https://push.example.com/a", "pub", vapid), confirmed.confirmed)
        // The ping at every start: nothing to tell the server.
        assertEquals(emptyList<Effect>(), confirmed.on(Event.NewEndpoint("https://push.example.com/a"), newToken).effects)
        // A changed endpoint does go.
        val moved = confirmed.on(Event.NewEndpoint("https://push.example.com/b"), newToken)
        assertEquals(listOf(Effect.SendEndpoint("https://push.example.com/b")), moved.effects)
        assertFalse(moved.registration!!.isConfirmed)
        // A confirmation of an endpoint that is no longer this one changes nothing.
        assertFalse(moved.registration!!.after(Event.ServerConfirmed("https://push.example.com/a")).isConfirmed)
    }

    @Test fun aFailureKeepsATokenThatHadAnEndpoint() {
        val working = fresh.after(Event.NewEndpoint("https://push.example.com/a"))
        val step = working.on(Event.Failed(FailReason.NETWORK), newToken)
        assertEquals(emptyList<Effect>(), step.effects)
        assertEquals("t0", step.registration!!.token)
        assertEquals(FailReason.NETWORK, step.registration!!.failure)
        assertEquals("the old endpoint still delivers", Phase.DELIVERING, step.registration!!.phase)
        // The next endpoint clears it.
        assertNull(step.registration!!.after(Event.NewEndpoint("https://push.example.com/a")).failure)
    }

    @Test fun aRefusedTokenIsNeverRegisteredAgain() {
        // An error a second try may get past: a new token, registered at once, once per ask.
        val step = fresh.on(Event.Failed(FailReason.INTERNAL_ERROR), newToken)
        assertEquals(listOf(Effect.Unregister("org.example.distributor", "t0"), Effect.Register("t1")), step.effects)
        val replaced = step.registration!!
        assertEquals("t1", replaced.token)
        assertEquals(Phase.FAILED, replaced.phase)
        assertEquals("keys stay with the registration", "pub", replaced.p256dh)
        // A second failure in the same ask: shown, its token given back, and not the one asked with next.
        val again = replaced.on(Event.Failed(FailReason.ACTION_REQUIRED), newToken)
        assertEquals(listOf(Effect.Unregister("org.example.distributor", "t1")), again.effects)
        assertEquals("t2", again.registration!!.token)
        assertEquals(FailReason.ACTION_REQUIRED, again.registration!!.failure)
        // Asked again (the next start, Settings): the new token, which may be replaced once more.
        val started = again.registration!!.on(Event.Started, newToken)
        assertEquals(listOf(Effect.Register("t2")), started.effects)
        assertEquals(listOf(Effect.Unregister("org.example.distributor", "t2"), Effect.Register("t3")), started.registration!!.on(Event.Failed(FailReason.INTERNAL_ERROR), newToken).effects)
    }

    @Test fun aDistributorThatNeedsSettingUpOrANetworkIsNotAskedAgainAtOnce() {
        for (reason in listOf(FailReason.ACTION_REQUIRED, FailReason.NETWORK)) {
            val step = fresh.on(Event.Failed(reason), newToken)
            // The token given up is taken back (the distributor may have kept it), and nothing is REGISTERed yet.
            assertEquals("$reason", listOf(Effect.Unregister("org.example.distributor", "t0")), step.effects)
            assertEquals(reason, step.registration!!.failure)
            assertTrue("$reason: a new token for the next REGISTER", step.registration!!.token != "t0")
            assertEquals(Phase.FAILED, step.registration!!.phase)
            // When it is asked again, the new token goes.
            assertEquals(listOf(Effect.Register(step.registration!!.token)), step.registration!!.on(Event.Started, newToken).effects)
        }
    }

    @Test fun aRegisterHeldBackIsSentOnceTheDistributorTakesRegisterAgain() {
        val working = fresh.after(Event.NewEndpoint("https://push.example.com/a"))
        assertFalse("answered: nothing to ask on return", working.asksOnReturn)
        val held = working.on(Event.HeldBack, newToken)
        assertEquals(emptyList<Effect>(), held.effects)
        val kept = held.registration!!
        assertTrue(kept.owesRegister)
        assertEquals("the same token and endpoint are kept", working.copy(owesRegister = true), kept)
        assertTrue(kept.asksOnReturn)
        // Asked again: the same token, and nothing owed any more.
        val started = kept.on(Event.Started, newToken)
        assertEquals(listOf(Effect.Register("t0")), started.effects)
        assertFalse(started.registration!!.owesRegister)
        // An endpoint that comes ends it too.
        assertFalse(kept.after(Event.NewEndpoint("https://push.example.com/a")).owesRegister)
        // A new server key REGISTERs a new token: nothing of the old one is owed.
        assertFalse(kept.after(Event.VapidChanged("B" + "Q".repeat(86))).owesRegister)
    }

    @Test fun comingBackAsksAgainWhatTheDistributorHasNotAnsweredButNotWhatWaitsForANetwork() {
        assertTrue("never answered", fresh.asksOnReturn)
        assertTrue("needs setting up", fresh.copy(failure = FailReason.ACTION_REQUIRED).asksOnReturn)
        assertTrue(fresh.copy(failure = FailReason.INTERNAL_ERROR).asksOnReturn)
        assertFalse("the network coming back asks it", fresh.copy(failure = FailReason.NETWORK).asksOnReturn)
        assertFalse(fresh.copy(endpoint = "https://push.example.com/a", failure = FailReason.NETWORK).asksOnReturn)
        assertTrue("held back", fresh.copy(failure = FailReason.NETWORK, owesRegister = true).asksOnReturn)
    }

    @Test fun aPushThatComesThroughEndsTemporarilyUnavailable() {
        val down = fresh.after(Event.NewEndpoint("https://push.example.com/a"), Event.TempUnavailable)
        val step = down.on(Event.Delivered, newToken)
        assertEquals(emptyList<Effect>(), step.effects)
        assertEquals(Phase.DELIVERING, step.registration!!.phase)
        assertEquals("nothing else changes", down.copy(unavailable = false), step.registration)
    }

    @Test fun reasonsAreTheProtocolsAndAnythingElseIsInternal() {
        assertEquals(FailReason.NETWORK, FailReason.of("NETWORK"))
        assertEquals(FailReason.ACTION_REQUIRED, FailReason.of("ACTION_REQUIRED"))
        assertEquals(FailReason.VAPID_REQUIRED, FailReason.of("VAPID_REQUIRED"))
        assertEquals(FailReason.INTERNAL_ERROR, FailReason.of(null))
        assertEquals(FailReason.INTERNAL_ERROR, FailReason.of("network"))
        assertEquals(FailReason.INTERNAL_ERROR, FailReason.of("TOO_MANY_PENDING_REQUESTS"))
    }

    @Test fun aPlainUnregisteredEndsItWithoutRegisteringAgain() {
        val step = fresh.after(Event.NewEndpoint("https://push.example.com/a")).on(Event.Unregistered(null), newToken)
        assertNull(step.registration)
        assertEquals(listOf(Effect.DeleteAtServer, Effect.Stopped(Stop.STOPPED)), step.effects)
    }

    @Test fun aMigrationMovesToTheNamedDistributor() {
        val step = fresh.on(Event.Unregistered("org.example.other"), newToken)
        assertNull(step.registration)
        assertEquals(listOf(Effect.Unregister("org.example.distributor", "t0"), Effect.MoveTo("org.example.other")), step.effects)
        // Naming itself is no migration.
        assertEquals(listOf(Effect.DeleteAtServer, Effect.Stopped(Stop.STOPPED)), fresh.on(Event.Unregistered("org.example.distributor"), newToken).effects)
    }

    @Test fun temporarilyUnavailableUntilTheNextEndpoint() {
        val down = fresh.after(Event.NewEndpoint("https://push.example.com/a"), Event.TempUnavailable)
        assertEquals(Phase.UNAVAILABLE, down.phase)
        assertEquals("https://push.example.com/a", down.endpoint)
        assertEquals(Phase.DELIVERING, down.after(Event.NewEndpoint("https://push.example.com/a")).phase)
    }

    @Test fun aRemovedDistributorEndsItLikeAPlainUnregistered() {
        val step = fresh.on(Event.DistributorRemoved, newToken)
        assertNull(step.registration)
        assertEquals(listOf(Effect.DeleteAtServer, Effect.Stopped(Stop.REMOVED)), step.effects)
    }

    @Test fun aNewServerKeyRegistersAfreshUnderANewToken() {
        val confirmed = fresh.after(Event.NewEndpoint("https://push.example.com/a"), Event.ServerConfirmed("https://push.example.com/a"))
        val other = "B" + "Q".repeat(86)
        val step = confirmed.on(Event.VapidChanged(other), newToken)
        assertEquals(listOf(Effect.Unregister("org.example.distributor", "t0"), Effect.Register("t1")), step.effects)
        val next = step.registration!!
        assertEquals(other, next.vapid)
        assertNull(next.endpoint)
        // Its first endpoint goes to the server even when the push server hands out the same address.
        assertEquals(listOf(Effect.SendEndpoint("https://push.example.com/a")), next.on(Event.NewEndpoint("https://push.example.com/a"), newToken).effects)
        // The same key again is nothing.
        assertEquals(emptyList<Effect>(), confirmed.on(Event.VapidChanged(vapid), newToken).effects)
    }

    @Test fun switchingOffTellsTheServerAndTheDistributor() {
        val step = fresh.after(Event.NewEndpoint("https://push.example.com/a")).on(Event.SwitchedOff, newToken)
        assertNull(step.registration)
        assertEquals(listOf(Effect.DeleteAtServer, Effect.Unregister("org.example.distributor", "t0")), step.effects)
    }

    @Test fun neverPrintsTheTokenOrTheEndpoint() {
        val r = fresh.after(Event.NewEndpoint("https://push.example.com/secret-topic"), Event.ServerConfirmed("https://push.example.com/secret-topic"))
        for (text in listOf(r.toString(), r.confirmed.toString(), Event.NewEndpoint("https://push.example.com/secret-topic").toString(),
            Effect.SendEndpoint("https://push.example.com/secret-topic").toString(), Effect.Register("t0").toString(), Effect.Unregister("d", "t0").toString())) {
            assertFalse(text, text.contains("secret-topic") || text.contains("t0"))
        }
    }
}
