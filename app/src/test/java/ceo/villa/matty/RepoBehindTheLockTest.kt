package ceo.villa.matty

import android.content.ContextWrapper
import android.content.SharedPreferences
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.MattermostSocket
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.AppSettings
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.data.session.ServerPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A session's repository against a server answered in memory (no network): what it requires is
 * read again whenever it may have changed unseen, and the conversation behind its lock is not
 * treated as being read.
 */
class RepoBehindTheLockTest {
    /** The server's client config as it stands now. */
    @Volatile private var config: Map<String, String> = emptyMap()
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val path = req.url.encodedPath
        asked += "${req.method} $path"
        val body = when {
            path.endsWith("/config/client") -> JsonObject(config.mapValues { JsonPrimitive(it.value) }).toString()
            path.endsWith("/plugins/webapp") || path.endsWith("/teams/unread") -> "[]"
            else -> "{}"
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    /** Enough of the phone's settings storage for [AppSettings] to read its defaults. */
    private object NoPrefs : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun repo(policy: ServerPolicy = ServerPolicy(), unlockDue: () -> Boolean = { false }): ChatRepository {
        val client = MattermostClient("https://chat.example.test", "token", http)
        val settings = AppSettings(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
        })
        val server = SavedServer(id = "s", url = "https://chat.example.test", name = "Chat", userId = "me", username = "me", sealedToken = "", policy = policy)
        return ChatRepository(server, client, MattermostSocket(client, http, scope), settings, scope, unlockDue = unlockDue)
    }

    @After fun stop() = scope.cancel()

    private fun waitFor(what: String, test: () -> Boolean) = runBlocking {
        assertNotNull(what, withTimeoutOrNull(5_000) { while (!test()) kotlinx.coroutines.delay(10); true })
    }

    @Test
    fun aNewConnectionReadsWhatTheServerRequiresAgain() = runBlocking {
        // Switched on while the socket was down: its config_changed is never replayed.
        val repo = repo()
        config = mapOf("MobileEnableBiometrics" to "true")
        repo.resync()
        waitFor("the config read again after a reconnect") { repo.policy.value.asksUnlock }
    }

    @Test
    fun aNewLicenceReadsWhatTheServerRequiresAgain() {
        val repo = repo()
        config = mapOf("MobilePreventScreenCapture" to "true")
        repo.handle(WsEvent("license_changed", JsonObject(emptyMap()), JsonObject(emptyMap()), 1))
        waitFor("the config read again after a licence change") { repo.policy.value.blocksCapture }
    }

    private fun dm(channel: String) = WsEvent(
        "posted",
        buildJsonObject {
            put("post", buildJsonObject { put("id", "p1"); put("user_id", "them"); put("channel_id", channel); put("message", "hi"); put("create_at", 1L) }.toString())
            put("channel_type", "D")
        },
        buildJsonObject { put("channel_id", channel) },
        2,
    )

    /** Whether a DM arriving in [channel] alerts the person (rather than counting as read). */
    private fun alerts(repo: ChatRepository, channel: String): Boolean = runBlocking {
        val alert = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { repo.alerts.first() } }
        repo.handle(dm(channel))
        alert.await() != null
    }

    @Test
    fun theChannelBehindTheLockIsNotBeingRead() {
        // Back after more than five minutes: the open channel is still the active one, but the
        // lock is (about to be) in front of it.
        val repo = repo(ServerPolicy(asksUnlock = true), unlockDue = { true })
        repo.activeChannelId.value = "x"
        assertNull(repo.readingChannel)
        assertTrue("a DM behind the lock alerts", alerts(repo, "x"))
        Thread.sleep(600)
        assertFalse("nothing behind the lock is marked read", asked.any { it.endsWith("/channels/members/me/view") })
    }

    @Test
    fun anUnlockedOrUnaskingServersOpenChannelIsBeingRead() {
        // Unlocked: as before.
        val open = repo(ServerPolicy(asksUnlock = true), unlockDue = { false })
        open.activeChannelId.value = "x"
        assertEquals("x", open.readingChannel)
        assertFalse(alerts(open, "x"))
        // A server that asks nothing is never behind a lock, whatever the book says.
        val asksNothing = repo(ServerPolicy(), unlockDue = { true })
        asksNothing.activeChannelId.value = "x"
        assertEquals("x", asksNothing.readingChannel)
        // In the background nothing is being read.
        asksNothing.appInForeground = false
        assertNull(asksNothing.readingChannel)
    }
}
