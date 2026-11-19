package ceo.villa.matty

import ceo.villa.matty.push.PushRegistration
import ceo.villa.matty.push.PushSide
import ceo.villa.matty.push.PushStore
import ceo.villa.matty.push.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch, what each server last said, and changes to a registration made in one step. */
class PushSidesTest {
    private val vapid = "B" + "A".repeat(86)
    private val prefs = MemoryPrefs()
    private val sealer = FakeSealer()
    private val store = PushStore(prefs, sealer)

    @Test fun pushIsOffUntilSwitchedOnAndStaysAsSet() {
        assertFalse(store.enabled)
        store.setEnabled(true)
        assertTrue(PushStore(prefs, sealer).enabled)
        store.setEnabled(false)
        assertFalse(PushStore(prefs, sealer).enabled)
    }

    @Test fun whatAServerSaidSurvivesARestartAndGoesWithTheServer() {
        store.create("s1", "d", vapid, "Chat")
        store.updateSide("s1") { it.copy(offer = PushSide.OFFER_ON, state = "pending", owesPut = true) }
        store.updateSide("s2") { it.copy(offer = PushSide.OFFER_OFF, reason = "Off here.") }
        val again = PushStore(prefs, sealer)
        assertEquals(PushSide(offer = PushSide.OFFER_ON, state = "pending", owesPut = true), again.side("s1"))
        assertEquals("Off here.", again.side("s2").reason)
        again.forget("s1")
        assertEquals(PushSide(), PushStore(prefs, sealer).side("s1"))
        assertEquals("another server's stays", "Off here.", PushStore(prefs, sealer).side("s2").reason)
        again.forgetSides()
        assertEquals(PushSide(), PushStore(prefs, sealer).side("s2"))
    }

    @Test fun aStepReadsTheRegistrationAsItIsNow() {
        val r = store.create("s1", "d", vapid, "Chat")
        // Somebody else moved it on since `r` was read.
        store.step(r.token, PushRegistration.Event.NewEndpoint("https://push.example.com/a"))
        val (before, step) = store.step(r.token, PushRegistration.Event.ServerConfirmed("https://push.example.com/a"))!!
        assertEquals("https://push.example.com/a", before.endpoint)
        assertTrue(step.registration!!.isConfirmed)
        assertTrue(PushStore(prefs, sealer).byToken(r.token)!!.isConfirmed)
        assertNull("a token nobody holds", store.step("never-made", PushRegistration.Event.Started))
    }

    @Test fun removingOneRegistrationKeepsItsServersNotes() {
        val r = store.create("s1", "d", vapid, "Chat")
        store.updateSide("s1") { it.copy(offer = PushSide.OFFER_ON) }
        store.remove(r)
        assertNull(store.byToken(r.token))
        assertEquals(PushSide.OFFER_ON, store.side("s1").offer)
    }

    @Test fun settingUpAgainClearsWhyItStopped() {
        val r = store.create("s1", "d", vapid, "Chat")
        store.step(r.token, PushRegistration.Event.DistributorRemoved)
        assertEquals(Stop.REMOVED, store.stop("s1"))
        store.clearStops()
        assertNull(PushStore(prefs, sealer).stop("s1"))
    }

    @Test fun aDeleteOwedOutlivesARestartAndSwitchingOffUntilPaidOrTheServerGoes() {
        store.oweDelete("s1")
        store.oweDelete("s2")
        store.forgetSides()
        assertEquals(setOf("s1", "s2"), PushStore(prefs, sealer).owedDeletes())
        store.paidDelete("s1")
        assertEquals(setOf("s2"), PushStore(prefs, sealer).owedDeletes())
        // The server goes: nothing is owed to it any more.
        store.forget("s2")
        assertEquals(emptySet<String>(), PushStore(prefs, sealer).owedDeletes())
    }

    @Test fun theChosenDistributorIsRememberedWithItsSigners() {
        store.rememberDistributor("org.example.distributor", "ab".repeat(32))
        assertEquals("ab".repeat(32), PushStore(prefs, sealer).distributorSigners)
        // Another choice whose signers could not be read keeps nothing of the first one's.
        store.rememberDistributor("org.example.other")
        assertEquals("org.example.other", PushStore(prefs, sealer).distributor)
        assertNull(PushStore(prefs, sealer).distributorSigners)
    }

    @Test fun everyWriteIsHeard() {
        val before = store.changes.value
        store.setEnabled(true)
        store.updateSide("s1") { it.copy(state = "none") }
        store.rememberDistributor("d")
        assertTrue(store.changes.value >= before + 3)
    }
}
