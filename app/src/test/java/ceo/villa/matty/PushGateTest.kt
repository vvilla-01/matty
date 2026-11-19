package ceo.villa.matty

import ceo.villa.matty.push.FailReason
import ceo.villa.matty.push.Inbound
import ceo.villa.matty.push.PushGate
import ceo.villa.matty.push.PushRegistration.Event
import ceo.villa.matty.push.UnifiedPush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the exported receiver turns away before it does any work. */
class PushGateTest {
    private val chosen = "org.example.distributor"
    private val tokens = mapOf("t1" to chosen, "t-old" to "org.example.old")
    private fun admit(inbound: Inbound, chosenNow: String? = chosen) = PushGate.admit(inbound, tokens::get, chosenNow)

    private fun changed(inbound: Inbound): Event = (admit(inbound) as PushGate.Admitted.Changed).event

    @Test fun aKnownTokensEventsAreLetThrough() {
        assertEquals(Event.NewEndpoint("https://push.example.com/a"), changed(Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1", endpoint = "https://push.example.com/a")))
        assertEquals(Event.Failed(FailReason.ACTION_REQUIRED), changed(Inbound(UnifiedPush.ACTION_REGISTRATION_FAILED, "t1", reason = "ACTION_REQUIRED")))
        assertEquals(Event.Failed(FailReason.INTERNAL_ERROR), changed(Inbound(UnifiedPush.ACTION_REGISTRATION_FAILED, "t1")))
        assertEquals(Event.Unregistered(null), changed(Inbound(UnifiedPush.ACTION_UNREGISTERED, "t1")))
        // The migration target under either name; the library's name first.
        assertEquals(Event.Unregistered("org.example.b"), changed(Inbound(UnifiedPush.ACTION_UNREGISTERED, "t1", useDistributor = "org.example.b")))
        assertEquals(Event.Unregistered("org.example.a"), changed(Inbound(UnifiedPush.ACTION_UNREGISTERED, "t1", newDistributor = "org.example.a", useDistributor = "org.example.b")))
        // What real distributors send: `newDistributor` alone.
        assertEquals(Event.Unregistered("org.example.a"), changed(Inbound(UnifiedPush.ACTION_UNREGISTERED, "t1", newDistributor = "org.example.a")))
        assertEquals(Event.TempUnavailable, changed(Inbound(UnifiedPush.ACTION_TEMP_UNAVAILABLE, "t1", useDistributor = "org.example.fallback")))

        val m = admit(Inbound(UnifiedPush.ACTION_MESSAGE, "t1", id = "m1", bytes = ByteArray(4096))) as PushGate.Admitted.Message
        assertEquals("m1", m.id)
        assertEquals(chosen, m.distributor)
        assertArrayEquals(ByteArray(4096), m.body)
        assertTrue(admit(Inbound(UnifiedPush.ACTION_MESSAGE, "t1", bytes = ByteArray(1))) is PushGate.Admitted.Message)
    }

    @Test fun everythingElseIsIgnored() {
        val ok = Inbound(UnifiedPush.ACTION_MESSAGE, "t1", bytes = ByteArray(200))
        for ((inbound, why) in listOf(
            Inbound(UnifiedPush.ACTION_MESSAGE, "nobody", bytes = ByteArray(200)) to "an unknown token",
            Inbound(UnifiedPush.ACTION_MESSAGE, "t-old", bytes = ByteArray(200)) to "a token of a distributor no longer chosen",
            Inbound(UnifiedPush.ACTION_MESSAGE, null, bytes = ByteArray(200)) to "no token",
            Inbound(UnifiedPush.ACTION_MESSAGE, "x".repeat(101), bytes = ByteArray(200)) to "a token over 100 bytes",
            Inbound(UnifiedPush.ACTION_MESSAGE, "t1") to "a message without its bytes",
            Inbound(UnifiedPush.ACTION_MESSAGE, "t1", bytes = ByteArray(0)) to "an empty message",
            Inbound(UnifiedPush.ACTION_MESSAGE, "t1", bytes = ByteArray(4097)) to "a message over 4096 bytes",
            Inbound(UnifiedPush.ACTION_MESSAGE, "t1", id = "i".repeat(101), bytes = ByteArray(200)) to "an id over 100 bytes",
            Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1") to "an endpoint missing",
            Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1", endpoint = "") to "an empty endpoint",
            Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1", endpoint = "https://e/" + "a".repeat(991)) to "an endpoint over 1000 bytes",
            Inbound(UnifiedPush.ACTION_REGISTER, "t1") to "an action Matty does not hear",
            Inbound(null, "t1") to "no action",
        )) assertNull(why, admit(inbound))
        assertNull("no distributor chosen", admit(ok, chosenNow = null))
        assertNull("another distributor chosen", admit(ok, chosenNow = "org.example.old"))
        // At the limits, still let through.
        assertTrue(admit(Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1", endpoint = "https://e/" + "a".repeat(990))) is PushGate.Admitted.Changed)
        assertTrue(admit(Inbound(UnifiedPush.ACTION_MESSAGE, "t1", id = "i".repeat(100), bytes = ByteArray(200))) is PushGate.Admitted.Message)
    }

    @Test fun onlyTheActionAndTheTokenAreReadUntilTheTokenIsKnown() {
        val extras = mapOf(
            UnifiedPush.EXTRA_TOKEN to "t1", UnifiedPush.EXTRA_ENDPOINT to "https://push.example.com/a",
            UnifiedPush.EXTRA_NEW_DISTRIBUTOR to "org.example.a", UnifiedPush.EXTRA_ID to "m1",
        )
        val read = mutableListOf<String>()
        fun inbound(action: String?, known: Boolean) = Inbound.read(action, { read += it; extras[it] }, { read += it; ByteArray(8) }, { known })

        assertNull("an unknown token", inbound(UnifiedPush.ACTION_NEW_ENDPOINT, known = false))
        assertEquals(listOf(UnifiedPush.EXTRA_TOKEN), read)
        read.clear()
        assertNull("an action Matty does not hear", inbound("org.example.SOMETHING", known = true))
        assertEquals(emptyList<String>(), read)
        // A known token: the rest is read.
        val got = inbound(UnifiedPush.ACTION_UNREGISTERED, known = true)!!
        assertEquals("t1", got.token)
        assertEquals("org.example.a", got.newDistributor)
        assertEquals(UnifiedPush.EXTRA_TOKEN, read.first())
        assertTrue(UnifiedPush.EXTRA_BYTES_MESSAGE in read)
    }

    @Test fun neverPrintsWhatItCarries() {
        val m = admit(Inbound(UnifiedPush.ACTION_MESSAGE, "t1", bytes = ByteArray(200)))!!
        val e = admit(Inbound(UnifiedPush.ACTION_NEW_ENDPOINT, "t1", endpoint = "https://push.example.com/secret"))!!
        for (text in listOf(m.toString(), e.toString(), Inbound(UnifiedPush.ACTION_MESSAGE, "t1").toString())) {
            assertTrue(text, !text.contains("t1") && !text.contains("secret"))
        }
    }
}
