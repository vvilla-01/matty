package ceo.villa.matty

import ceo.villa.matty.push.Arrival
import ceo.villa.matty.push.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a push says once opened, and what the receiver does with it. */
class PushPayloadTest {
    private fun parse(json: String) = PushPayload.parse(json.toByteArray())

    private val message = """{"v":1,"type":"message","user":"u1","post":"p1","channel":"c1","root":"r1","team":"t1",
        "sender":"s1","sender_name":"Alice","channel_name":"Town Square","channel_type":"O","text":"Alice: hi …","crt":true,
        "id_loaded":false,"from_webhook":true,"at":1700000000000}"""

    @Test fun everyTypeTheContractGivesIsRead() {
        assertEquals(
            PushPayload.Message("u1", "p1", "c1", "r1", "t1", "s1", "Alice", "Town Square", "O", "Alice: hi …", true, false, true, 1700000000000),
            parse(message),
        )
        assertEquals(PushPayload.Clear("u1", "c1", "", false, 5), parse("""{"v":1,"type":"clear","user":"u1","channel":"c1","root":"","crt":false,"at":5}"""))
        assertEquals(PushPayload.Hello("u1", "n1", 5), parse("""{"v":1,"type":"hello","user":"u1","nonce":"n1","at":5}"""))
        assertEquals(PushPayload.Test("u1", 5), parse("""{"v":1,"type":"test","user":"u1","at":5}"""))
        assertEquals(PushPayload.Ended("u1", 5), parse("""{"v":1,"type":"ended","user":"u1","at":5}"""))
        // An id-loaded message: no sender, no text, still a message.
        assertEquals(
            PushPayload.Message("u1", "p1", "c1", "", "", "", "", "", "", "", false, true, false, 5),
            parse("""{"v":1,"type":"message","user":"u1","post":"p1","channel":"c1","id_loaded":true,"at":5}"""),
        )
        // A field a later server adds is no reason to drop a push.
        assertEquals(PushPayload.Test("u1", 5), parse("""{"v":1,"type":"test","user":"u1","at":5,"badge":3}"""))
    }

    @Test fun anythingElseIsNothing() {
        for ((json, why) in listOf(
            """{"v":2,"type":"test","user":"u1","at":5}""" to "another version",
            """{"type":"test","user":"u1","at":5}""" to "no version",
            """{"v":1,"type":"badge","user":"u1","at":5}""" to "an unknown type",
            """{"v":1,"type":"test","at":5}""" to "no user",
            """{"v":1,"type":"test","user":"u1"}""" to "no time",
            """{"v":1,"type":"hello","user":"u1","at":5}""" to "a hello without its nonce",
            """{"v":1,"type":"message","user":"u1","channel":"c1","at":5}""" to "a message without its post",
            """{"v":1,"type":"message","user":"u1","post":"p1","at":5}""" to "a message without its channel",
            """{"v":1,"type":"clear","user":"u1","at":5}""" to "a clear without its channel",
            """{"v":1,"type":"test","user":["u1"],"at":5}""" to "a field of the wrong type",
            """{"v":1,"type":"test","user":null,"at":5}""" to "a null",
            """{v:1,type:test,user:u1,at:5}""" to "not strict JSON",
            """[1]""" to "not an object",
            "" to "nothing",
        )) assertNull(why, parse(json))
        assertNull("not UTF-8", PushPayload.parse("""{"v":1,"type":"test","user":"u""".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + """","at":5}""".toByteArray()))
    }

    @Test fun theReceiverDecidesByAccountAndAge() {
        val now = 1_000_000_000_000L
        val min = 60_000L
        val hello = PushPayload.Hello("u1", "n1", now - 9 * min)
        assertEquals(Arrival.Confirm("n1"), Arrival.of(hello, "u1", now))
        assertEquals("for another account", Arrival.Drop, Arrival.of(hello, "u2", now))
        assertEquals("no account", Arrival.Drop, Arrival.of(hello, "", now))
        assertEquals(Arrival.Drop, Arrival.of(hello.copy(at = now - 11 * min), "u1", now))
        assertEquals(Arrival.Tested, Arrival.of(PushPayload.Test("u1", now), "u1", now))
        assertEquals(Arrival.Drop, Arrival.of(PushPayload.Test("u1", now - 11 * min), "u1", now))

        val m = parse(message) as PushPayload.Message
        assertEquals(Arrival.Show(m.copy(at = now - min), silent = false), Arrival.of(m.copy(at = now - min), "u1", now))
        assertEquals(Arrival.Show(m.copy(at = now - 6 * min), silent = true), Arrival.of(m.copy(at = now - 6 * min), "u1", now))
        assertEquals(Arrival.Drop, Arrival.of(m.copy(at = now - 25 * 60 * min), "u1", now))
        // A server clock a little ahead of the phone's is not an old push.
        assertEquals(Arrival.Show(m.copy(at = now + min), silent = false), Arrival.of(m.copy(at = now + min), "u1", now))

        val clear = PushPayload.Clear("u1", "c1", "", false, now - 30 * 60 * min)
        assertEquals(Arrival.Cleared(clear), Arrival.of(clear, "u1", now))
        assertEquals(Arrival.Ended, Arrival.of(PushPayload.Ended("u1", now - 30 * 60 * min), "u1", now))
        assertEquals(Arrival.Drop, Arrival.of(PushPayload.Ended("u1", now), "u2", now))
    }

    @Test fun aNonceIsNeverPrinted() {
        assertEquals(false, PushPayload.Hello("u1", "secret-nonce", 5).toString().contains("secret-nonce"))
        assertEquals(false, Arrival.Confirm("secret-nonce").toString().contains("secret-nonce"))
    }
}
