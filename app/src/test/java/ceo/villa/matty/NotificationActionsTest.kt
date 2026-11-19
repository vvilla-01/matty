package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.service.NotificationActions
import ceo.villa.matty.service.NotificationActions.Outcome
import ceo.villa.matty.service.OneOff
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Reply and Mark as read from a notification, through a one-off client for the saved server. */
class NotificationActionsTest {
    private class Call(val method: String, val path: String, val body: String, val auth: String?)

    private val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())
    /** Status by "METHOD path?query"; anything else answers 200. */
    private val answers = HashMap<String, Int>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val where = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        calls += Call(req.method, where, body, req.header("Authorization"))
        val code = answers["${req.method} $where"] ?: 200
        val text = when {
            code != 200 -> """{"id":"x","message":"no"}"""
            where.startsWith("/api/v4/posts") -> """{"id":"new-post","channel_id":"chan1"}"""
            where == "/api/v4/users/me" -> """{"id":"victor-id","username":"victor"}"""
            where == "/api/v4/users/me/teams" -> """[{"id":"team-first"},{"id":"team-second"}]"""
            else -> """{"status":"OK"}"""
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val client = MattermostClient("https://chat.example.test", "token", http)

    @Test
    fun replyPostsWithoutMakingThePersonOnline() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.reply(client, "victor-id", "chan1", "root1", "on my way  ", "victor-id:42"))
        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v4/posts?set_online=false", call.path)
        assertTrue(call.body, call.body.contains("\"channel_id\":\"chan1\""))
        assertTrue(call.body, call.body.contains("\"root_id\":\"root1\""))
        assertTrue(call.body, call.body.contains("\"message\":\"on my way\""))
        assertTrue(call.body, call.body.contains("\"pending_post_id\":\"victor-id:42\""))
        assertEquals("Bearer token", call.auth)
    }

    @Test
    fun aReplySentAgainFromTheSameNotificationIsTheSamePost() = runBlocking {
        val conversation = "chan1"
        fun id(text: String, serverId: String = "s1", conv: String = conversation, shownAt: Long = 1_000, userId: String = "victor-id") =
            NotificationActions.replyId(serverId, userId, conv, shownAt, text)
        // The first went out but read "Couldn't send"; the person sends the same again, later.
        assertEquals(Outcome.Done, NotificationActions.reply(client, "victor-id", "chan1", "", "on my way", id("on my way")))
        Thread.sleep(5)
        assertEquals(Outcome.Done, NotificationActions.reply(client, "victor-id", "chan1", "", "on my way ", id("on my way ")))
        val sent = calls.map { Regex("\"pending_post_id\":\"([^\"]*)\"").find(it.body)!!.groupValues[1] }
        assertEquals("Mattermost takes the second as the first", 1, sent.toSet().size)
        assertTrue("the person's own", sent[0].startsWith("victor-id:"))
        // Anything else is another post.
        val others = listOf(id("on my way!"), id("on my way", serverId = "s2"), id("on my way", conv = "chan2"),
            id("on my way", shownAt = 2_000), id("on my way", userId = "alice-id"))
        assertEquals(others.size + 1, (others + sent[0]).toSet().size)
    }

    @Test
    fun anEmptyReplySendsNothing() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.reply(client, "victor-id", "chan1", "", "   ", "victor-id:1"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun markAsReadUsesTheRouteThatDoesNotMakeThePersonActive() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1"))
        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v4/channels/members/victor-id/mark_read", call.path)
        assertEquals("[\"chan1\"]", call.body)
    }

    @Test
    fun markAsReadOnACollapsedThreadMarksTheThreadInItsTeam() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1", threadRoot = "root1", team = "team1"))
        val call = calls.single()
        assertEquals("PUT", call.method)
        assertTrue(call.path, Regex("/api/v4/users/victor-id/teams/team1/threads/root1/read/[0-9]+").matches(call.path))
    }

    @Test
    fun aThreadIsReadUpToTheNewestLineTheNotificationShowed() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1", threadRoot = "root1", team = "team1", shownAt = 1_234, now = 9_999))
        assertEquals("the server's time of the line shown, not the phone's", "/api/v4/users/victor-id/teams/team1/threads/root1/read/1234", calls.single().path)
        calls.clear()
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1", threadRoot = "root1", team = "team1", shownAt = 0, now = 9_999))
        assertEquals("none known: the phone's", "/api/v4/users/victor-id/teams/team1/threads/root1/read/9999", calls.single().path)
    }

    @Test
    fun aDirectMessagesThreadIsMarkedInATeamOfThePersons() = runBlocking {
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1", threadRoot = "root1", team = ""))
        assertEquals("asked for once", listOf("/api/v4/users/me/teams"), calls.filter { it.method == "GET" }.map { it.path })
        assertTrue(calls.last().path, calls.last().path.startsWith("/api/v4/users/victor-id/teams/team-first/threads/root1/read/"))
    }

    @Test
    fun aThreadThatCannotBeMarkedIsAFailureNotTheChannel() = runBlocking {
        answers.clear()
        val failing = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            calls += Call(req.method, req.url.encodedPath, "", null)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (req.method == "PUT") 403 else 200).message("x")
                .body("""{"id":"x","message":"no"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val c = MattermostClient("https://chat.example.test", "token", failing)
        assertEquals(Outcome.Failed, NotificationActions.markRead(c, "victor-id", "chan1", threadRoot = "root1", team = "team1"))
        assertTrue("the channel is never marked instead", calls.none { it.path.contains("mark_read") || it.path.contains("/view") })
    }

    @Test
    fun aServerWithoutThatRouteIsToldTheOlderWay() = runBlocking {
        answers["POST /api/v4/channels/members/victor-id/mark_read"] = 404
        assertEquals(Outcome.Done, NotificationActions.markRead(client, "victor-id", "chan1"))
        assertEquals(listOf("/api/v4/channels/members/victor-id/mark_read", "/api/v4/channels/members/me/view"), calls.map { it.path })
        assertTrue(calls[1].body.contains("\"channel_id\":\"chan1\""))
    }

    @Test
    fun aFailureIsAFailure() = runBlocking {
        answers["POST /api/v4/posts?set_online=false"] = 500
        assertEquals(Outcome.Failed, NotificationActions.reply(client, "victor-id", "chan1", "", "hi", "victor-id:1"))
        answers["POST /api/v4/channels/members/victor-id/mark_read"] = 403
        assertEquals(Outcome.Failed, NotificationActions.markRead(client, "victor-id", "chan1"))
        assertTrue("no 401, so nobody asks whether the session is over", calls.none { it.path == "/api/v4/users/me" })
    }

    @Test
    fun a401EndsTheSessionOnlyWhenTheServerConfirmsIt() = runBlocking {
        answers["POST /api/v4/posts?set_online=false"] = 401
        assertEquals("a stray 401", Outcome.Failed, NotificationActions.reply(client, "victor-id", "chan1", "", "hi", "victor-id:1"))
        assertEquals(1, calls.count { it.path == "/api/v4/users/me" })
        answers["GET /api/v4/users/me"] = 401
        assertEquals(Outcome.Ended, NotificationActions.reply(client, "victor-id", "chan1", "", "hi", "victor-id:2"))
    }

    @Test
    fun theOneOffClientSignsNobodyOutAndIsHeldToItsOwnTimeout() {
        val server = SavedServer(id = "s1", url = "https://chat.example.test", name = "Chat", userId = "victor-id", username = "victor", sealedToken = "sealed")
        val one = assertNotNull(OneOff.of(server, "token", OneOff.http(http, OneOff.ACTION_SECONDS)))
        assertNull(one.client.onUnauthorized)
        assertEquals(10_000, one.client.http.callTimeoutMillis)
        assertEquals(3_000, OneOff.http(http, OneOff.RECEIVER_SECONDS).callTimeoutMillis)
        assertEquals("https://chat.example.test/plugins/ceo.villa.matty", one.plugin.base)
        assertNull("never over plain http", OneOff.of(server.copy(url = "http://chat.example.test"), "token", http))
        assertTrue("its token is not printed", !one.toString().contains("token"))
    }
}
