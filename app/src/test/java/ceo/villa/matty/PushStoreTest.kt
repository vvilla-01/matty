package ceo.villa.matty

import ceo.villa.matty.PushTestKit.encrypt
import ceo.villa.matty.PushTestKit.pad
import ceo.villa.matty.push.Arrival
import ceo.villa.matty.push.PushGate
import ceo.villa.matty.push.PushRegistration
import ceo.villa.matty.push.PushStore
import ceo.villa.matty.push.Stop
import ceo.villa.matty.push.WebPush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registrations on disk: what is sealed, what survives a restart, and what a push opens with. */
class PushStoreTest {
    private val vapid = "B" + "A".repeat(86)
    private val prefs = MemoryPrefs()
    private val sealer = FakeSealer()
    private val store = PushStore(prefs, sealer)

    @Test fun aNewRegistrationHasItsOwnTokenAndKeysAndTheyAreSealed() {
        val a = store.create("s1", "org.example.distributor", vapid, "Chat")
        val b = store.create("s2", "org.example.distributor", vapid, "Chat")
        assertNotEquals(a.token, b.token)
        assertNotEquals(a.p256dh, b.p256dh)
        assertEquals(36, a.token.length)
        val keys = store.keys(a)!!
        assertEquals(a.p256dh, keys.p256dh)
        // Nothing secret is on disk in the clear: not the private key, not the authentication secret.
        val disk = prefs.values.values.joinToString()
        assertFalse(disk.contains(PushTestKit.b64(ceo.villa.matty.push.P256.scalar(keys.private))))
        assertFalse(disk.contains(keys.authText))
        assertTrue(disk.contains(a.p256dh))
    }

    @Test fun whatTheServerSendsToTheStoredKeysOpens() {
        val r = store.create("s1", "org.example.distributor", vapid, "Chat")
        val keys = store.keys(r)!!
        val json = """{"v":1,"type":"test","user":"u1","at":${System.currentTimeMillis()}}"""
        val body = encrypt(keys.public, keys.auth, pad(json.toByteArray()))
        // Read back by a new store, as a new process would.
        val again = PushStore(prefs, sealer)
        val stored = again.byToken(r.token)!!
        assertArrayEquals(json.toByteArray(), WebPush.decrypt(body, again.keys(stored)!!))
        assertEquals(Arrival.Tested, PushGate.open(again, stored, body, "u1", System.currentTimeMillis()))
        assertEquals("another account", Arrival.Drop, PushGate.open(again, stored, body, "u2", System.currentTimeMillis()))
        assertEquals("not a push", Arrival.Drop, PushGate.open(again, stored, body.copyOf(body.size - 1), "u1", System.currentTimeMillis()))
        // Keys that no longer open (the Keystore key went): nothing opens, nothing breaks.
        sealer.opens = false
        assertNull(again.keys(stored))
        assertEquals(Arrival.Drop, PushGate.open(again, stored, body, "u1", System.currentTimeMillis()))
    }

    @Test fun aRestartReadsEverythingBack() {
        val r = store.create("s1", "org.example.distributor", vapid, "Chat")
        store.rememberDistributor("org.example.distributor")
        val step = r.on(PushRegistration.Event.NewEndpoint("https://push.example.com/a"), store::newToken)
        store.apply(r, step)
        val again = PushStore(prefs, sealer)
        assertEquals(listOf(step.registration), again.all())
        assertEquals("org.example.distributor", again.distributor)
        assertEquals("org.example.distributor", again.distributorOf(r.token))
        assertNull(again.distributorOf("never-made"))
        assertTrue("every write is on disk before the receiver finishes", prefs.commits > 0)
    }

    @Test fun oneRegistrationPerServerAndDistributor() {
        val first = store.create("s1", "org.example.distributor", vapid, "Chat")
        val second = store.create("s1", "org.example.distributor", vapid, "Chat")
        store.create("s1", "org.example.other", vapid, "Chat")
        assertEquals(2, store.all().size)
        assertNull(store.byToken(first.token))
        assertEquals(second, store.registration("s1", "org.example.distributor"))
    }

    @Test fun anEndedRegistrationGoesAndSaysWhy() {
        val r = store.create("s1", "org.example.distributor", vapid, "Chat")
        store.create("s2", "org.example.distributor", vapid, "Chat")
        store.apply(r, r.on(PushRegistration.Event.Unregistered(null), store::newToken))
        assertNull(store.byToken(r.token))
        assertEquals(Stop.STOPPED, store.stop("s1"))
        assertNull(store.stop("s2"))
        assertEquals(Stop.STOPPED, PushStore(prefs, sealer).stop("s1"))
        // Setting it up again clears the note.
        store.create("s1", "org.example.distributor", vapid, "Chat")
        assertNull(store.stop("s1"))
    }

    @Test fun forgettingAServerDeletesItsTokensAndKeys() {
        val r = store.create("s1", "org.example.distributor", vapid, "Chat")
        store.create("s1", "org.example.other", vapid, "Chat")
        val other = store.create("s2", "org.example.distributor", vapid, "Chat")
        store.forget("s1")
        assertEquals(listOf(other), store.all())
        assertFalse(prefs.values.values.joinToString().contains(r.sealedKey))
    }

    @Test fun aDamagedFileIsNoRegistrations() {
        prefs.edit().putString("registrations", "{not json").commit()
        assertEquals(emptyList<PushRegistration>(), PushStore(prefs, sealer).all())
        assertNotNull(PushStore(prefs, sealer).create("s1", "d", vapid, "Chat"))
    }

    @Test fun whatAServerSaidIsReadBackFromAnEarlierVersionToo() {
        // As an earlier Matty wrote it, with a field this one no longer has.
        prefs.edit().putString("sides", """{"s1":{"offer":"on","state":"refused","host":"ntfy.example.com","status":403,"episode":"refused:ntfy.example.com:403::1","owesPut":true}}""").commit()
        val side = PushStore(prefs, sealer).side("s1")
        assertEquals("refused", side.state)
        assertEquals(403, side.status)
        assertTrue(side.owesPut)
        assertFalse(side.retried)
    }
}
