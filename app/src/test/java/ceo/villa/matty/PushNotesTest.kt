package ceo.villa.matty

import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.User
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.service.PushNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A notification made from a push alone, with no live session. */
class PushNotesTest {
    private fun message(
        channelType: String = "O", channelName: String = "Town Square", senderName: String = "alice",
        text: String = "alice: hello", sender: String = "alice-id", root: String = "", crt: Boolean = false,
        idLoaded: Boolean = false,
    ) = PushPayload.Message(
        user = "victor-id", post = "post1", channel = "chan1", root = root, team = "team1", sender = sender,
        senderName = senderName, channelName = channelName, channelType = channelType, text = text, crt = crt,
        idLoaded = idLoaded, fromWebhook = false, at = 1_000L,
    )

    private fun note(m: PushPayload.Message, silent: Boolean = false, headers: Map<String, String> = emptyMap()) =
        PushNotes.note("server-a", "https://chat.example.test/", "victor-id", m, silent, headers)

    @Test
    fun aChannelPostIsTitledByItsChannelAndLosesMattermostsSenderLead() {
        val n = note(message())
        assertEquals("#Town Square", n.title)
        assertTrue(n.group)
        assertEquals("alice", n.senderName)
        assertEquals("hello", n.text)
        assertEquals("chan1", n.conversation)
        assertEquals("server-a", n.serverId)
        assertEquals("victor-id", n.me)
        assertEquals(1_000L, n.at)
    }

    @Test
    fun aDirectMessageHasNoTitle() {
        val n = note(message(channelType = "D", channelName = "alice", text = "hi there"))
        assertNull(n.title)
        assertFalse(n.group)
        assertEquals("hi there", n.text)
    }

    @Test
    fun aGroupMessageKeepsItsName() {
        val n = note(message(channelType = "G", channelName = "alice, bob, victor", text = "lunch?"))
        assertEquals("alice, bob, victor", n.title)
        assertTrue(n.group)
    }

    @Test
    fun aCollapsedThreadReplyIsItsOwnConversationUnderMattermostsTitle() {
        val n = note(message(root = "root1", crt = true, channelName = "Reply in Town Square"))
        assertEquals("root1", n.conversation)
        assertEquals("root1", n.threadRoot)
        assertEquals("Reply in Town Square", n.title)
        // Without collapsed threads a reply is the channel's.
        assertEquals("chan1", note(message(root = "root1", crt = false)).conversation)
    }

    @Test
    fun withoutTheChannelsNameThereIsNoTitle() {
        assertNull(note(message(channelName = "")).title)
    }

    @Test
    fun theLeadStaysWhenItIsNotTheSenders() {
        assertEquals("bob: hello", note(message(text = "bob: hello")).text)
        assertEquals("New message", note(message(text = "  ")).text)
    }

    @Test
    fun idLoadedNamesNobodyAndLoadsNoPicture() {
        val n = note(message(sender = "", senderName = "", channelName = "", text = "You have a new message.", idLoaded = true))
        assertEquals(PushNotes.SOMEONE, n.senderName)
        assertNull(n.avatar)
        assertEquals("You have a new message.", n.text)
    }

    @Test
    fun theSendersPictureIsOnTheSavedServerWithItsOwnHeaders() {
        val n = note(message(), headers = mapOf("Authorization" to "Bearer t"))
        assertEquals("https://chat.example.test/api/v4/users/alice-id/image?_=0", n.avatar?.url)
        assertEquals(mapOf("Authorization" to "Bearer t"), n.avatar?.headers)
        assertFalse("never printed", n.avatar.toString().contains("Bearer"))
    }

    @Test
    fun anOldPushIsSilent() {
        assertTrue(note(message(), silent = true).silent)
        assertFalse(note(message()).silent)
    }

    @Test
    fun idLoadedIsFilledInFromTheServer() {
        val m = message(sender = "", senderName = "", channelName = "", channelType = "", text = "generic", idLoaded = true)
        val post = Post(id = "post1", userId = "alice-id", channelId = "chan1", message = "the real text")
        val alice = User(id = "alice-id", username = "alice", firstName = "Alice", lastName = "Example")
        val filled = PushNotes.filled(m, post, alice, Channel(id = "chan1", type = "O", displayName = "Town Square"))
        val n = note(filled)
        assertEquals("Alice Example", n.senderName)
        assertEquals("#Town Square", n.title)
        assertEquals("the real text", n.text)
        assertEquals("alice-id", n.senderId)
        // A DM's title is the sender's name, as Mattermost's own push has it.
        val dm = PushNotes.filled(m, post, alice, Channel(id = "chan1", type = "D", displayName = ""))
        assertNull(note(dm).title)
        // What could not be read leaves Mattermost's words.
        val bare = PushNotes.filled(m, post.copy(message = ""), null, null)
        assertEquals("generic", bare.text)
        assertEquals("alice-id", bare.sender)
    }

    @Test
    fun theNotificationKnowsItsTeam() {
        assertEquals("team1", note(message()).team)
        assertEquals("team1", note(message()).head.team)
    }

    @Test
    fun idLoadedAsksThePostAndTheChannelSideBySideWithinItsBudget() = runBlocking {
        // The post is answered only once the channel has been asked (side by side, or not at all
        // until a 5 s give-up), and the sender only once the test lets it, after idLoaded is back:
        // nothing here is timed but the budget itself.
        val channelAsked = CountDownLatch(1)
        val senderGate = CountDownLatch(1)
        val postWaitedForChannel = AtomicBoolean(false)
        val gated = AtomicBoolean(true)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val body = when {
                path == "/api/v4/posts/post1" -> {
                    if (gated.get()) postWaitedForChannel.set(channelAsked.await(5, TimeUnit.SECONDS))
                    """{"id":"post1","user_id":"alice-id","channel_id":"chan1","message":"the real text"}"""
                }
                path == "/api/v4/channels/chan1" -> {
                    channelAsked.countDown()
                    """{"id":"chan1","type":"O","display_name":"Town Square"}"""
                }
                else -> {
                    if (gated.get()) senderGate.await(5, TimeUnit.SECONDS)
                    """{"id":"alice-id","username":"alice","first_name":"Alice","last_name":"Example"}"""
                }
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = MattermostClient("https://chat.example.test", "token", http)
        val m = message(sender = "", senderName = "", channelName = "", channelType = "", text = "generic", idLoaded = true)

        val started = System.nanoTime()
        val partly = PushNotes.idLoaded(client, m, budgetMs = 1_500)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        senderGate.countDown()
        assertTrue("the channel was asked while the post was still unanswered", postWaitedForChannel.get())
        assertEquals("the post came", "the real text", partly.text)
        assertEquals("the channel came beside the post", "Town Square", partly.channelName)
        assertEquals("the sender did not come in time", "", partly.senderName)
        assertTrue("held to its budget, not to the sender's answer (5 s away): $tookMs ms", tookMs < 4_000)

        gated.set(false)
        val whole = PushNotes.idLoaded(client, m, budgetMs = 2_000)
        assertEquals("Alice Example", whole.senderName)
        assertEquals("Town Square", whole.channelName)
    }
}
