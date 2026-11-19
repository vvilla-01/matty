package dev.standin.distributor

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test fun `the long poll's answers`() {
        val message = PollAnswer.of(200, """{"id":"m3","body":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"}""")
        assertTrue(message is PollAnswer.Message)
        message as PollAnswer.Message
        assertEquals("m3", message.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), message.body)
        assertEquals(PollAnswer.Nothing, PollAnswer.of(204, null))
        assertEquals(PollAnswer.Gone, PollAnswer.of(404, """{"error":"this endpoint was dropped"}"""))
        assertEquals(PollAnswer.Retry, PollAnswer.of(503, null))
        assertEquals(PollAnswer.Retry, PollAnswer.of(200, """{"id":"m3","body":"not base64!"}"""))
        assertEquals(PollAnswer.Retry, PollAnswer.of(200, """{"id":"m3","body":""}"""))
        assertEquals(PollAnswer.Retry, PollAnswer.of(200, "<html>"))
    }

    @Test fun `flat JSON objects are read, and nothing else`() {
        assertEquals(mapOf("a" to "x\"y\\z/\né", "c" to ""), FlatJson.strings(""" { "a" : "x\"y\\z\/\né", "n": -1.5e3, "t": true, "f": false, "z": null, "c": "" } """))
        assertEquals(emptyMap<String, String>(), FlatJson.strings("{}"))
        assertNull(FlatJson.strings("""{"a":{"b":"c"}}"""))
        assertNull(FlatJson.strings("""{"a":"b"} x"""))
        assertNull(FlatJson.strings("""{"a":"b""""))
        assertNull(FlatJson.strings("""{"a":"\u12"}"""))
        assertNull(FlatJson.strings("[]"))
    }

    @Test fun `failed polls back off from one second to thirty`() {
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), listOf(0, 1, 2, 3, 4, 5, 6, 60).map(::backoffMs))
    }

    @Test fun `endpoints are at the stand-in push server's push address`() {
        assertEquals("http://localhost:7788/push/abc", endpointUrl("abc"))
    }

    // ---- against a server answering as build/fakebb's stand-in push server does ----------------

    private val asked = mutableListOf<String>()
    private var nextStatus = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            asked += "${exchange.requestMethod} ${exchange.requestURI}"
            val body = when {
                exchange.requestURI.path.endsWith("/next") && nextStatus == 200 -> """{"id":"m1","body":"${Base64.getEncoder().encodeToString("hi".toByteArray())}"}"""
                else -> """{"id":"x"}"""
            }.toByteArray()
            if (nextStatus == 204) exchange.sendResponseHeaders(204, -1) else exchange.sendResponseHeaders(nextStatus, body.size.toLong())
            if (nextStatus != 204) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }
    private val standIn = HttpStandIn("http://127.0.0.1:${server.address.port}")

    @After fun stop() = server.stop(0)

    @Test fun `PUT tells the key, DELETE drops the endpoint`() {
        assertTrue(standIn.put("abc_-1", "BKey"))
        assertTrue(standIn.put("abc_-1", null))
        assertTrue(standIn.delete("abc_-1"))
        assertEquals(listOf("PUT /fake/push/abc_-1?vapid=BKey", "PUT /fake/push/abc_-1?vapid=", "DELETE /fake/push/abc_-1"), asked)
        nextStatus = 503
        assertFalse(standIn.put("abc", null))
    }

    @Test fun `the long poll asks for the next message and waits as long as it says`() {
        val answer = standIn.poll("abc", 25)
        assertEquals("GET /fake/push/abc/next?wait=25", asked.single())
        assertEquals("hi", (answer as PollAnswer.Message).body.decodeToString())
        nextStatus = 204
        assertEquals(PollAnswer.Nothing, standIn.poll("abc", 25))
        nextStatus = 404
        assertEquals(PollAnswer.Gone, standIn.poll("abc", 25))
    }

    @Test fun `a stand-in that is not there is asked again later`() {
        server.stop(0)
        assertEquals(PollAnswer.Retry, standIn.poll("abc", 1))
        assertFalse(standIn.put("abc", null))
        assertFalse(standIn.delete("abc"))
    }

    @Test fun `only an endpoint id goes in the address`() {
        assertTrue(runCatching { standIn.put("../log", null) }.isFailure)
        assertTrue(asked.isEmpty())
    }
}
