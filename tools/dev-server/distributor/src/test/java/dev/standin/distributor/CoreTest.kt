package dev.standin.distributor

import android.content.Context
import java.util.Base64
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private class Memory(var text: String? = null) : Saved {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private class Server(var answers: Boolean = true) : StandIn {
        val puts = mutableListOf<Pair<String, String?>>()
        val deletes = mutableListOf<String>()
        override fun put(id: String, vapid: String?) = answers.also { puts += id to vapid }
        override fun delete(id: String) = true.also { deletes += id }
    }

    /** Every broadcast an app would get, as one line. */
    private class Sent : Apps {
        val lines = mutableListOf<String>()
        val endpoints = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        override fun newEndpoint(app: String, token: String, endpoint: String, id: String) { lines += "NEW_ENDPOINT $app $token"; endpoints += endpoint }
        override fun failed(app: String, token: String, reason: String?) { lines += "REGISTRATION_FAILED $app $token $reason" }
        override fun unregistered(app: String, token: String, moveTo: String?) { lines += "UNREGISTERED $app $token $moveTo" }
        override fun tempUnavailable(app: String, token: String, fallback: String?) { lines += "TEMP_UNAVAILABLE $app $token $fallback" }
        override fun message(app: String, token: String, body: ByteArray, id: String) { lines += "MESSAGE $app $token $id"; bodies += body }
    }

    /** What its receiver and link activity were switched to, in order. */
    private class Switched : Components {
        val states = mutableListOf<Boolean>()
        override fun enabled(on: Boolean) { states += on }
    }

    private val saved = Memory()
    private val server = Server()
    private val sent = Sent()
    private val switched = Switched()
    private fun core() = Core(saved, server, sent, switched, Random(7))

    private val app = "org.example.chat"
    private val other = "org.example.other"
    /** A real uncompressed P-256 point's shape: 65 bytes starting 0x04. */
    private val vapid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65) { if (it == 0) 4 else it.toByte() })

    @Test fun `REGISTER tells the stand-in the key first, then hands out an endpoint there`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "chat.example.org")
        val r = core.registrations().single()
        assertEquals(listOf(r.endpointId to vapid), server.puts)
        assertEquals(listOf("NEW_ENDPOINT $app t1"), sent.lines)
        assertEquals("http://localhost:7788/push/${r.endpointId}", sent.endpoints.single())
        assertTrue(r.endpointId.matches(Regex("^[A-Za-z0-9_-]{27}$")))
        assertEquals("chat.example.org", r.description)
        assertEquals(listOf(r.endpointId), core.pollTargets())
    }

    @Test fun `REGISTER again with the same token keeps its endpoint`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        core.register(app, How.SHARED, "t1", vapid, "d")
        assertEquals(1, core.registrations().size)
        assertEquals(2, sent.endpoints.size)
        assertEquals(1, sent.endpoints.distinct().size)
        assertEquals(listOf("NEW_ENDPOINT $app t1", "NEW_ENDPOINT $app t1"), sent.lines)
        assertEquals(1, server.puts.map { it.first }.distinct().size)
    }

    @Test fun `a token another app registered is refused with INTERNAL_ERROR`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        core.register(other, How.SHARED, "t1", vapid, "d")
        assertEquals("REGISTRATION_FAILED $other t1 INTERNAL_ERROR", sent.lines.last())
        assertEquals(app, core.registrations().single().app)
    }

    @Test fun `a request with an extra outside the specification is ignored`() {
        val core = core()
        core.register(app, How.SHARED, "", vapid, "d")
        core.register(app, How.SHARED, null, vapid, "d")
        core.register(app, How.SHARED, "x".repeat(101), vapid, "d")
        core.register(app, How.SHARED, "t1", vapid, "é".repeat(51))
        assertTrue(sent.lines.isEmpty())
        assertTrue(server.puts.isEmpty())
    }

    @Test fun `a VAPID key that is not one registers without a key, as android-distributor does`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid.dropLast(1), "d")
        core.register(app, How.SHARED, "t2", "A".repeat(87), "d") // decodes, but not to a 0x04 point
        assertEquals(listOf(null, null), server.puts.map { it.second })
        assertTrue(core.registrations().all { it.vapid.isEmpty() })
        assertEquals(vapid, Protocol.vapid(vapid))
    }

    @Test fun `when the stand-in cannot be reached REGISTER fails with NETWORK and nothing is kept`() {
        server.answers = false
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        assertEquals(listOf("REGISTRATION_FAILED $app t1 NETWORK"), sent.lines)
        assertTrue(core.registrations().isEmpty())
    }

    @Test fun `FAIL makes the next registrations fail with the reason given, then they work`() {
        val core = core()
        assertEquals("The next 2 registration(s) fail with ACTION_REQUIRED.", core.steer(Steer.Fail("ACTION_REQUIRED", 2)))
        core.register(app, How.SHARED, "t1", vapid, "d")
        core.register(app, How.SHARED, "t2", vapid, "d")
        core.register(app, How.SHARED, "t3", vapid, "d")
        assertEquals(listOf("REGISTRATION_FAILED $app t1 ACTION_REQUIRED", "REGISTRATION_FAILED $app t2 ACTION_REQUIRED", "NEW_ENDPOINT $app t3"), sent.lines)
        core.steer(Steer.Fail(null, 1))
        core.register(app, How.SHARED, "t4", vapid, "d")
        assertEquals("REGISTRATION_FAILED $app t4 null", sent.lines.last())
    }

    @Test fun `UNREGISTER drops the endpoint at the stand-in and answers UNREGISTERED`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val id = core.registrations().single().endpointId
        core.unregister(other, "t1")
        assertEquals(1, core.registrations().size)
        core.unregister(app, "t1")
        assertEquals(listOf(id), server.deletes)
        assertEquals("UNREGISTERED $app t1 null", sent.lines.last())
        assertTrue(core.registrations().isEmpty())
        assertTrue(core.pollTargets().isEmpty())
    }

    @Test fun `UNREGISTER_ALL tells every app, naming where to move when asked`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        core.register(other, How.SHARED, "t2", vapid, "d")
        assertEquals("Unregistered 2, naming org.example.next to move to.", core.steer(Steer.UnregisterAll("org.example.next")))
        assertEquals(listOf("UNREGISTERED $app t1 org.example.next", "UNREGISTERED $other t2 org.example.next"), sent.lines.takeLast(2))
        assertEquals(2, server.deletes.size)
        assertTrue(core.registrations().isEmpty())
        core.register(app, How.SHARED, "t3", vapid, "d")
        core.steer(Steer.UnregisterAll(null))
        assertEquals("UNREGISTERED $app t3 null", sent.lines.last())
    }

    @Test fun `NEW_ENDPOINTS moves every registration to a new endpoint with the same key`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val old = core.registrations().single().endpointId
        assertEquals("New endpoints for 1 of 1.", core.steer(Steer.NewEndpoints))
        val new = core.registrations().single().endpointId
        assertTrue(new != old)
        assertEquals(new to vapid, server.puts.last())
        assertEquals(listOf(old), server.deletes)
        assertEquals("http://localhost:7788/push/$new", sent.endpoints.last())
        assertEquals(listOf(new), core.pollTargets())

        server.answers = false
        assertEquals("New endpoints for 0 of 1.", core.steer(Steer.NewEndpoints))
        assertEquals(new, core.registrations().single().endpointId)
    }

    @Test fun `while temporarily unavailable nothing is polled or delivered, and it all comes back after`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val id = core.registrations().single().endpointId
        core.steer(Steer.Unavailable("org.example.fallback"))
        assertEquals("TEMP_UNAVAILABLE $app t1 org.example.fallback", sent.lines.last())
        assertTrue(core.pollTargets().isEmpty())
        core.register(app, How.SHARED, "t1", vapid, "d")
        assertEquals("TEMP_UNAVAILABLE $app t1 org.example.fallback", sent.lines.last())
        assertTrue(core.deliver(id, "m1", byteArrayOf(1)))
        assertFalse(sent.lines.any { it.startsWith("MESSAGE") })

        assertEquals("Available again; 1 held message(s) delivered.", core.steer(Steer.Available))
        assertEquals(listOf("NEW_ENDPOINT $app t1", "MESSAGE $app t1 m1"), sent.lines.takeLast(2))
        assertEquals(listOf(id), core.pollTargets())
    }

    @Test fun `a message is handed to the app its endpoint belongs to, and acknowledgements are counted`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val id = core.registrations().single().endpointId
        assertTrue(core.deliver(id, "m4", byteArrayOf(9, 8)))
        assertEquals("MESSAGE $app t1 m4", sent.lines.last())
        assertEquals(listOf(9, 8), sent.bodies.single().map(Byte::toInt))
        assertFalse(core.deliver("unknown", "m5", byteArrayOf(1)))

        core.ack(other, "t1", "m4")
        assertTrue(core.describe().contains("Acknowledged: 0;"))
        core.ack(app, "t1", "m4")
        assertTrue(core.describe().contains("Acknowledged: 1;"))
    }

    @Test fun `an endpoint the stand-in dropped is not polled until registered again`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val id = core.registrations().single().endpointId
        core.gone(id)
        assertTrue(core.pollTargets().isEmpty())
        assertFalse(core.deliver(id, "m1", byteArrayOf(1)))
        core.register(app, How.SHARED, "t1", vapid, "d")
        assertEquals(listOf(id), core.pollTargets())
    }

    @Test fun `what it holds survives the process`() {
        val first = core()
        first.register(app, How.PI, "t1", vapid, "a server")
        first.steer(Steer.Fail("NETWORK", 3))
        first.steer(Steer.Unavailable(null))
        first.steer(Steer.Link(true))
        val again = Core(Memory(saved.text), server, sent, switched, Random(8))
        assertEquals(first.registrations(), again.registrations())
        assertTrue(again.linkCancels())
        assertTrue(again.pollTargets().isEmpty())
        assertTrue(again.describe().contains("The next 3 registration(s) fail with NETWORK."))
        again.steer(Steer.Available)
        assertEquals(listOf(first.registrations().single().endpointId), again.pollTargets())
        assertTrue(Core(Memory("nonsense\nmore"), server, sent, switched).registrations().isEmpty())
    }

    @Test fun `it refuses registrations beyond what it keeps with ACTION_REQUIRED`() {
        val core = core()
        repeat(Protocol.MAX_REGISTRATIONS) { core.register(app, How.SHARED, "t$it", null, null) }
        core.register(app, How.SHARED, "one more", null, null)
        assertEquals("REGISTRATION_FAILED $app one more ACTION_REQUIRED", sent.lines.last())
    }

    @Test fun `what it says never holds a token`() {
        val core = core()
        core.register(app, How.APPLICATION, "secret-token-1", vapid, "a server")
        val text = core.describe()
        assertFalse(text.contains("secret-token-1"))
        assertTrue(text.contains("$app \"a server\": endpoint ${core.registrations().single().endpointId}, VAPID key, identity application"))
        assertEquals(text, core.steer(Steer.State))
    }

    @Test fun `the asking app is known from its shared identity, then its pi, then what it says`() {
        assertEquals("a.b" to How.SHARED, Identity.of("a.b", "c.d", "e.f"))
        assertEquals("c.d" to How.PI, Identity.of(null, "c.d", "e.f"))
        assertEquals("e.f" to How.APPLICATION, Identity.of(null, null, "e.f"))
        assertEquals("e.f" to How.APPLICATION, Identity.of("not a package", "", "e.f"))
        assertNull(Identity.of(null, null, null))
    }

    @Test fun `steering commands are read from the broadcast's action and extras`() {
        fun of(action: String, vararg extras: Pair<String, Any>) = Steer.of("dev.standin.distributor.$action") { mapOf(*extras)[it] }
        assertEquals(Steer.Fail("INTERNAL_ERROR", 1), of("FAIL"))
        assertEquals(Steer.Fail("NETWORK", 2), of("FAIL", "reason" to "NETWORK", "times" to 2))
        assertEquals(Steer.Fail("NETWORK", 3), of("FAIL", "reason" to "NETWORK", "times" to "3"))
        assertEquals(Steer.Fail(null, 1), of("FAIL", "reason" to "none"))
        assertNull(of("FAIL", "reason" to "network; rm"))
        assertNull(of("FAIL", "times" to 0))
        assertEquals(Steer.UnregisterAll(null), of("UNREGISTER_ALL"))
        assertEquals(Steer.UnregisterAll("dev.standin.distributor.two"), of("UNREGISTER_ALL", "to" to "dev.standin.distributor.two"))
        assertNull(of("UNREGISTER_ALL", "to" to "not a package"))
        assertEquals(Steer.NewEndpoints, of("NEW_ENDPOINTS"))
        assertEquals(Steer.Unavailable("a.b"), of("UNAVAILABLE", "fallback" to "a.b"))
        assertEquals(Steer.Available, of("AVAILABLE"))
        assertEquals(Steer.Link(true), of("LINK", "cancel" to true))
        assertNull(of("LINK"))
        assertEquals(Steer.SetUp(false), of("SETUP", "on" to false))
        assertEquals(Steer.SetUp(false), of("SETUP", "on" to "off"))
        assertEquals(Steer.SetUp(true), of("SETUP", "on" to "true"))
        assertNull(of("SETUP"))
        assertEquals(Steer.State, of("STATE"))
        assertNull(of("NOTHING"))
        assertNull(Steer.of("FAIL") { null })
        assertNull(Steer.of(null) { null })
    }

    @Test fun `not set up, REGISTER is answered ACTION_REQUIRED and its receiver and link activity go off, once`() {
        val core = core()
        core.register(app, How.SHARED, "t1", vapid, "d")
        val id = core.registrations().single().endpointId
        assertEquals(
            "Not set up: the next REGISTER is answered ACTION_REQUIRED, and its receiver and link activity are then switched off.",
            core.steer(Steer.SetUp(false)),
        )
        assertTrue(switched.states.isEmpty())
        core.register(app, How.SHARED, "t1", vapid, "d")
        core.register(app, How.SHARED, "t2", vapid, "d")
        assertEquals(listOf("REGISTRATION_FAILED $app t1 ACTION_REQUIRED", "REGISTRATION_FAILED $app t2 ACTION_REQUIRED"), sent.lines.takeLast(2))
        assertEquals(listOf(false), switched.states)
        assertTrue(core.describe().contains("Not set up: REGISTER is answered ACTION_REQUIRED; its receiver and link activity are off."))
        // What it already delivers to stays.
        assertEquals(listOf(id), core.pollTargets())

        val again = Core(Memory(saved.text), server, sent, switched, Random(8))
        again.register(app, How.SHARED, "t3", vapid, "d")
        assertEquals("REGISTRATION_FAILED $app t3 ACTION_REQUIRED", sent.lines.last())
        assertEquals(listOf(false), switched.states)

        assertEquals("Set up: its receiver and link activity are on.", again.steer(Steer.SetUp(true)))
        assertEquals(listOf(false, true), switched.states)
        again.register(app, How.SHARED, "t1", vapid, "d")
        assertEquals("NEW_ENDPOINT $app t1", sent.lines.last())
        assertFalse(again.describe().contains("Not set up"))
    }

    @Test fun `a migration names its target only as newDistributor`() {
        assertEquals(listOf("token" to "t1", "newDistributor" to "org.example.next"), unregisteredExtras("t1", "org.example.next"))
        assertEquals(listOf("token" to "t1"), unregisteredExtras("t1", null))
    }

    @Test fun `a message is sent only when the raise-to-foreground service connects first`() {
        assertEquals(Raise.Outcome.SEND, Raise().on(Raise.Event.CONNECTED))
        Raise().let {
            assertEquals(Raise.Outcome.SEND, it.on(Raise.Event.CONNECTED))
            assertNull(it.on(Raise.Event.TIMED_OUT))
        }
        // Five seconds without a connection: dropped, and a late connection sends nothing.
        Raise().let {
            assertEquals(Raise.Outcome.DROP, it.on(Raise.Event.TIMED_OUT))
            assertNull(it.on(Raise.Event.CONNECTED))
        }
        assertEquals(Raise.Outcome.DROP, Raise().on(Raise.Event.NULL_BINDING))
        assertEquals(Raise.Outcome.DROP, Raise().on(Raise.Event.DIED))
        assertEquals(5_000L, RAISE_WAIT_MS)
        assertEquals(Context.BIND_AUTO_CREATE, RAISE_BIND_FLAGS)
    }

    @Test fun `the binding ends five seconds after it was made however late the connection came`() {
        // Connected at 3.5 s: sent, and still bound until the timer set at bind time runs at 5 s.
        Raise().let {
            assertEquals(Raise.Outcome.SEND, it.on(Raise.Event.CONNECTED).also { outcome -> assertFalse(Raise.unbinds(Raise.Event.CONNECTED, outcome)) })
            assertTrue(Raise.unbinds(Raise.Event.TIMED_OUT, it.on(Raise.Event.TIMED_OUT)))
        }
        // Dropped: unbound at once.
        for (event in listOf(Raise.Event.NULL_BINDING, Raise.Event.DIED, Raise.Event.TIMED_OUT)) {
            assertTrue(event.name, Raise.unbinds(event, Raise().on(event)))
        }
        // The Android side unbinds only as Raise says, from the one timer it sets when it binds.
        // Android.kt cannot run in a unit test, so its wiring is checked in its text.
        val source = java.io.File("src/main/java/dev/standin/distributor/Android.kt").readText()
        val start = "override fun message("
        val end = "private fun send("
        assertTrue(source.contains(start) && source.contains(end))
        val message = source.substringAfter(start).substringBefore(end)
        assertEquals(1, Regex("postDelayed\\(").findAll(message).count())
        assertTrue(message.contains("main.postDelayed({ decide(Raise.Event.TIMED_OUT) }, RAISE_WAIT_MS)"))
        assertTrue(message.contains("if (Raise.unbinds(event, outcome)) unbind.run()"))
    }

    @Test fun `messages dropped for want of a connection are counted`() {
        val core = core()
        core.notRaised()
        core.notRaised()
        assertTrue(core.describe().contains("Dropped, the app's raise-to-foreground service not connected within 5 s: 2."))
    }
}
