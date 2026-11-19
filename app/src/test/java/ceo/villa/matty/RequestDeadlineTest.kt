package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.RequestDeadline
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.service.PushNotes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Work that Android gives a few seconds in all carries a [RequestDeadline], and no request it
 * makes through [MattermostClient] runs past it: a receiver's work never outlives the broadcast.
 */
class RequestDeadlineTest {
    private val reached = AtomicInteger()

    /** A server that takes the request and holds its answer until the call is cancelled (or 10 s). */
    private fun silent(callTimeoutMs: Long) = OkHttpClient.Builder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).addInterceptor { chain ->
        reached.incrementAndGet()
        val until = System.nanoTime() + 10_000_000_000L
        while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(5)
        if (chain.call().isCanceled()) throw IOException("Canceled")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x")
            .body("""{"id":"victor-id","username":"victor"}""".toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun nowMs() = System.nanoTime() / 1_000_000
    private fun inMs(ms: Long) = RequestDeadline(nowMs() + ms, clock = ::nowMs)

    /** How long [block] took to fail with an IOException, in ms. */
    private fun failsIn(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        try {
            runBlocking { block() }
            fail("it answered")
        } catch (e: IOException) {
            // expected
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun underADeadlineACallEndsWhenTheTimeIsUpNotByTheClientsOwnTimeout() {
        val client = MattermostClient("https://chat.example.test", "token", silent(callTimeoutMs = 5_000))
        val took = failsIn { kotlinx.coroutines.withContext(inMs(300)) { client.me() } }
        assertEquals(1, reached.get())
        assertTrue("about 300 ms, not the client's 5 s: $took ms", took in 250..2_000)
    }

    @Test
    fun withTheTimeUpACallNeverGoesOut() {
        val client = MattermostClient("https://chat.example.test", "token", silent(callTimeoutMs = 5_000))
        val took = failsIn { kotlinx.coroutines.withContext(inMs(-1)) { client.me() } }
        assertEquals("the server is never asked", 0, reached.get())
        assertTrue("$took ms", took < 1_000)
    }

    @Test
    fun withoutADeadlineTheClientsOwnTimeoutHolds() {
        val client = MattermostClient("https://chat.example.test", "token", silent(callTimeoutMs = 1_000))
        val took = failsIn { client.me() }
        assertEquals(1, reached.get())
        assertTrue("the client's 1 s: $took ms", took in 900..4_000)
        reached.set(0)
        val later = failsIn { kotlinx.coroutines.withContext(inMs(60_000)) { client.me() } }
        assertTrue("a deadline further off than the client's own timeout changes nothing: $later ms", later in 900..4_000)
    }

    @Test
    fun theMattyPluginsRoutesAreHeldToItToo() {
        val client = MattermostClient("https://chat.example.test", "token", silent(callTimeoutMs = 5_000))
        failsIn { kotlinx.coroutines.withContext(inMs(-1)) { VillaNetApi(client).pushState() } }
        assertEquals("never asked", 0, reached.get())
        val took = failsIn { kotlinx.coroutines.withContext(inMs(300)) { VillaNetApi(client).pushState() } }
        assertTrue("about 300 ms: $took ms", took in 250..2_000)
    }

    @Test
    fun anIdLoadedPushsAsksAreHeldToItsDeadline() = runBlocking {
        val client = MattermostClient("https://chat.example.test", "token", silent(callTimeoutMs = 5_000))
        val m = PushPayload.Message(
            user = "victor-id", post = "post1", channel = "chan1", root = "", team = "team1", sender = "",
            senderName = "", channelName = "", channelType = "", text = "generic", crt = false,
            idLoaded = true, fromWebhook = false, at = 1_000L,
        )
        val same = kotlinx.coroutines.withContext(inMs(-1)) { PushNotes.idLoaded(client, m, budgetMs = 1_000) }
        assertEquals("left as it came", "generic", same.text)
        assertEquals("the side-by-side asks never went out", 0, reached.get())
    }

    // ---- where it is set -------------------------------------------------------------------

    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val next = text.indexOf(p, at + 1)
            assertTrue("'$p' after what comes before it", next > at)
            at = next
        }
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    @Test
    fun bothReceiversCarryADeadlineFromTheirArrival() {
        val notifier = File(main, "service/Notifier.kt").readText()
        assertTrue("class NotificationActionReceiver" in notifier)
        val actions = notifier.substringAfter("class NotificationActionReceiver")
        inOrder(body(actions, "override fun onReceive("), "val deadline = RequestDeadline.after(REQUESTS_MS)", "goAsync()",
            "c.appScope.launch(deadline)", "withTimeoutOrNull(WORK_MS) { act(c, head, text, shownAt) }", "if (done == null) c.notifier.couldNotSend(head)")
        assertTrue("requests over by 7 s", "const val REQUESTS_MS = 7_000L" in actions)
        assertTrue("the sending by 8 s", "const val WORK_MS = 8_000L" in actions)

        val receiver = File(main, "push/PushReceiver.kt").readText()
        inOrder(body(receiver, "override fun onReceive("), "val deadline = RequestDeadline.after(REQUESTS_MS)", "goAsync()",
            "scope.launch(deadline)", "withTimeoutOrNull(WORK_MS)")
        assertTrue("requests over by 8.5 s", "const val REQUESTS_MS = 8_500L" in receiver)
        assertTrue("the box stays at 9 s", "const val WORK_MS = 9_000L" in receiver)
    }
}
