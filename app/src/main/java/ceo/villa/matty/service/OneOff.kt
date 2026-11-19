package ceo.villa.matty.service

import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.net.TransportPolicy
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A client for one saved server that is not the live session: what a push or a notification
 * action does with the server goes through one, without starting the app's connection. It never
 * signs anybody out by itself (no [MattermostClient.onUnauthorized]): a 401 is confirmed with
 * [sessionOver] and the caller decides. OkHttp's own call timeout bounds every request, because a
 * coroutine timeout cannot stop one already on the wire.
 */
class OneOff(val server: SavedServer, val client: MattermostClient) {
    /** Matty Plugin's routes on the same server, through the same client. */
    val plugin: VillaNetApi by lazy { VillaNetApi(client) }

    /**
     * Whether [e] means the session is over: a 401, confirmed by one `/users/me` that Mattermost
     * itself refuses with 401 too ([mattermosts401]; a stray 401 must not end anything).
     */
    suspend fun sessionOver(e: Throwable): Boolean = sessionOver(client, e)

    override fun toString() = "OneOff(${server.id})"

    companion object {
        /** Inside a receiver, which has about 10 seconds in all. */
        const val RECEIVER_SECONDS = 3L
        /** For Reply and Mark as read. */
        const val ACTION_SECONDS = 10L

        /** [base] with a call timeout of [seconds]. */
        fun http(base: OkHttpClient, seconds: Long): OkHttpClient = base.newBuilder().callTimeout(seconds, TimeUnit.SECONDS).build()

        /** The client for [server] with [token] over [http]; null when the server's address may not be used. */
        fun of(server: SavedServer, token: String, http: OkHttpClient): OneOff? {
            if (!TransportPolicy.isAllowed(server.url)) return null
            val client = runCatching { MattermostClient(server.url, token, http) }.getOrNull() ?: return null
            client.onUnauthorized = null
            return OneOff(server, client)
        }

        suspend fun sessionOver(client: MattermostClient, e: Throwable): Boolean {
            if (status(e) != 401) return false
            return try {
                client.me(); false
            } catch (c: CancellationException) {
                throw c
            } catch (again: Exception) {
                mattermosts401(again)
            }
        }

        /**
         * A 401 that Mattermost itself gave: its error, with an id, in the body. A 401 page from
         * something in front of the server (a proxy's sign-in, say) says nothing about the session.
         */
        fun mattermosts401(e: Throwable): Boolean = e is ApiException && e.status == 401 && e.errorId.isNotEmpty()

        private fun status(e: Throwable): Int = when (e) {
            is ApiException -> e.status
            is VillaNetException -> e.status
            else -> 0
        }
    }
}

/** Reply and Mark as read from a notification, for a saved server whether or not it is connected. */
object NotificationActions {
    sealed interface Outcome {
        data object Done : Outcome
        data object Failed : Outcome
        /** The server confirmed the session is over. */
        data object Ended : Outcome
    }

    /**
     * Posts [text] as [userId] without making them show as online, under [pendingId]
     * ([replyId]): Mattermost answers a post sent again with the same one within its 30 s with
     * the post it made already.
     */
    suspend fun reply(client: MattermostClient, userId: String, channelId: String, threadRoot: String, text: String, pendingId: String): Outcome {
        val message = text.trimEnd()
        if (message.isBlank()) return Outcome.Done
        return attempt(client) {
            client.createPost(channelId, message, threadRoot, emptyList(), pendingId, setOnline = false)
        }
    }

    /**
     * The pending post id of a reply: the same for the same text sent again from the same
     * notification (its server, its [conversation] and the newest line it showed, [shownAt]),
     * so that a reply that went out but read "Couldn't send" is not posted twice.
     */
    fun replyId(serverId: String, userId: String, conversation: String, shownAt: Long, text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(listOf(serverId, conversation, shownAt.toString(), text.trimEnd()).joinToString("\u0000").toByteArray())
        return "$userId:" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Marks the conversation read without making [userId] active. A collapsed thread ([threadRoot]
     * not empty) is marked as a thread, in [team] (for a direct or group message, which has none,
     * the first of the person's teams, asked for); the thread route sets no status and no active
     * channel either. The thread is read up to the newest line the notification showed
     * ([shownAt], the server's time), the phone's [now] only when that is not known (0). A
     * channel is marked with the route for that, which takes the server's own time, and a server
     * without it (404) is told the older way, by viewing the channel.
     */
    suspend fun markRead(
        client: MattermostClient, userId: String, channelId: String, threadRoot: String = "", team: String = "",
        shownAt: Long = 0L, now: Long = System.currentTimeMillis(),
    ): Outcome = attempt(client) {
        if (threadRoot.isNotEmpty()) {
            val teamId = team.ifEmpty { client.myTeams().firstOrNull()?.id ?: error("in no team") }
            client.markThreadRead(userId, teamId, threadRoot, NoteRules.readUpTo(shownAt, now)).getOrThrow()
            return@attempt
        }
        try {
            client.markChannelsRead(userId, listOf(channelId))
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            client.viewChannel(channelId).getOrThrow()
        }
    }

    private suspend fun attempt(client: MattermostClient, block: suspend () -> Unit): Outcome = try {
        block(); Outcome.Done
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (OneOff.sessionOver(client, e)) Outcome.Ended else Outcome.Failed
    }
}
