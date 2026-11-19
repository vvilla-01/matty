package ceo.villa.matty

import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.push.PushPlan
import ceo.villa.matty.push.PushPlan.SessionCheck
import ceo.villa.matty.service.OneOff
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * What `GET /users/me` says about a session the server sent `ended` for, through a real
 * [MattermostClient]: only Mattermost's own 401, given twice, ends it.
 */
class SessionCheckTest {
    private enum class Answer { MATTERMOST_401, PAGE_401, UNAVAILABLE, OFFLINE, SOMEBODY_ELSE, THE_PERSON }

    private val queue = ConcurrentLinkedQueue<Answer>()
    private val asked = AtomicInteger()

    private fun answer(req: okhttp3.Request, code: Int, type: String, body: String) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody(type.toMediaType())).build()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        assertEquals("/api/v4/users/me", req.url.encodedPath)
        asked.incrementAndGet()
        when (queue.poll() ?: error("asked once more than expected")) {
            Answer.MATTERMOST_401 -> answer(
                req, 401, "application/json",
                """{"id":"api.context.session_expired.app_error","message":"Invalid or expired session, please login again.","detailed_error":"","request_id":"r1","status_code":401}""",
            )
            Answer.PAGE_401 -> answer(req, 401, "text/html", "<html><head><title>401 Authorization Required</title></head><body><h1>401</h1></body></html>")
            Answer.UNAVAILABLE -> answer(req, 503, "text/html", "<html><body>503 Service Unavailable</body></html>")
            Answer.OFFLINE -> throw IOException("no network")
            Answer.SOMEBODY_ELSE -> answer(req, 200, "application/json", """{"id":"bob-id","username":"bob"}""")
            Answer.THE_PERSON -> answer(req, 200, "application/json", """{"id":"victor-id","username":"victor"}""")
        }
    }.build()

    private val client = MattermostClient("https://chat.example.test", "token", http)

    private fun once(a: Answer): SessionCheck = runBlocking {
        queue.add(a)
        PushPlan.sessionCheck(runCatching { client.me() }, "victor-id")
    }

    private val pauses = AtomicInteger()

    private fun asked(vararg answers: Answer): SessionCheck = runBlocking {
        asked.set(0); pauses.set(0); queue.clear()
        queue.addAll(answers)
        PushPlan.sessionAsked("victor-id", pause = { pauses.incrementAndGet() }) { client.me() }
    }

    @Test fun onlyMattermostsOwn401SaysTheSessionIsOver() {
        assertEquals(SessionCheck.OVER, once(Answer.MATTERMOST_401))
        assertEquals("a 401 page from something in front of the server", SessionCheck.UNKNOWN, once(Answer.PAGE_401))
        assertEquals("a 503", SessionCheck.UNKNOWN, once(Answer.UNAVAILABLE))
        assertEquals("no network", SessionCheck.UNKNOWN, once(Answer.OFFLINE))
        assertEquals("another account", SessionCheck.UNKNOWN, once(Answer.SOMEBODY_ELSE))
        assertEquals(SessionCheck.ALIVE, once(Answer.THE_PERSON))
    }

    @Test fun a401IsAskedOnceMoreBeforeTheSessionIsOver() {
        assertEquals(SessionCheck.OVER, asked(Answer.MATTERMOST_401, Answer.MATTERMOST_401))
        assertEquals("asked twice", 2, asked.get())
        assertEquals("a pause between", 1, pauses.get())
        assertEquals("alive at the second look", SessionCheck.ALIVE, asked(Answer.MATTERMOST_401, Answer.THE_PERSON))
        assertEquals(SessionCheck.UNKNOWN, asked(Answer.MATTERMOST_401, Answer.UNAVAILABLE))
        assertEquals(SessionCheck.UNKNOWN, asked(Answer.MATTERMOST_401, Answer.PAGE_401))
        assertEquals(SessionCheck.UNKNOWN, asked(Answer.MATTERMOST_401, Answer.OFFLINE))
        assertEquals(1_000L, PushPlan.SESSION_CONFIRM_MS)
    }

    @Test fun anythingButA401IsTheAnswerAtOnce() {
        for (a in listOf(Answer.THE_PERSON, Answer.PAGE_401, Answer.UNAVAILABLE, Answer.OFFLINE, Answer.SOMEBODY_ELSE)) {
            val check = asked(a)
            assertEquals(a.name, if (a == Answer.THE_PERSON) SessionCheck.ALIVE else SessionCheck.UNKNOWN, check)
            assertEquals(a.name, 1, asked.get())
            assertEquals(a.name, 0, pauses.get())
        }
    }

    @Test fun theOneOffConfirmationCountsOnlyMattermostsOwn401() = runBlocking {
        val stray = ApiException(401, null, "HTTP 401")
        queue.add(Answer.PAGE_401)
        assertFalse("a proxy's 401 page confirms nothing", OneOff.sessionOver(client, stray))
        queue.add(Answer.UNAVAILABLE)
        assertFalse(OneOff.sessionOver(client, stray))
        queue.add(Answer.MATTERMOST_401)
        assertTrue(OneOff.sessionOver(client, stray))
        queue.add(Answer.MATTERMOST_401)
        assertTrue("a plugin route's 401 too", OneOff.sessionOver(client, VillaNetException("unauthorized", "no", 401)))
        assertFalse("no 401, nothing asked", OneOff.sessionOver(client, IOException("no network")))
        assertTrue("every answer was used", queue.isEmpty())
    }
}
