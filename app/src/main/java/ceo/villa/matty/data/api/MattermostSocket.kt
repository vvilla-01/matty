package ceo.villa.matty.data.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ceo.villa.matty.data.net.TransportPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** A server-pushed event: `event`, its `data`, and the `broadcast` scope. */
data class WsEvent(
    val event: String,
    val data: JsonObject,
    val broadcast: JsonObject,
    val seq: Long,
) {
    val channelId: String get() = broadcast.str("channel_id").orEmpty().ifEmpty { data.str("channel_id").orEmpty() }
    val teamId: String get() = broadcast.str("team_id").orEmpty().ifEmpty { data.str("team_id").orEmpty() }
    val userId: String get() = broadcast.str("user_id").orEmpty().ifEmpty { data.str("user_id").orEmpty() }

    /** Mattermost double-encodes many payloads (e.g. `data.post` is a JSON string). */
    fun <T> decodeString(key: String, ser: kotlinx.serialization.KSerializer<T>): T? =
        data.str(key)?.let { runCatching { MattermostJson.decodeFromString(ser, it) }.getOrNull() }
}

enum class SocketState { Disconnected, Connecting, Connected }

/**
 * Mattermost WebSocket with automatic reconnect (exponential backoff, capped at 30s) and
 * resumable sessions via connection_id + sequence_number, so events missed during a short
 * drop are replayed by the server.
 */
class MattermostSocket(
    private val client: MattermostClient,
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    /** A monotonic clock (ms) that counts the phone's sleep: an attempt begun before a sleep is old after it. */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val http = baseHttp.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        // A redirected upgrade would carry the auth challenge (and its token) to the new host.
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WsEvent> = _events

    private val _state = MutableStateFlow(SocketState.Disconnected)
    val state: StateFlow<SocketState> = _state

    /** Emits each time a fresh (non-resumed) connection is made, so callers can resync. */
    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reconnected: SharedFlow<Unit> = _reconnected

    private var ws: WebSocket? = null
    private var connectionId: String = ""
    private val lastSeq = AtomicLong(0)
    private val sendSeq = AtomicLong(1)
    private var retryJob: Job? = null
    private var attempts = 0
    private var authSeq = -1L
    @Volatile private var gotHello = false
    @Volatile private var opened = false
    @Volatile private var wanted = false
    private var hadConnection = false
    /** When the latest connection attempt began ([clock]). */
    @Volatile private var openedAt = 0L

    fun connect() {
        wanted = true
        if (ws != null) return
        open()
    }

    fun disconnect() {
        wanted = false
        retryJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _state.value = SocketState.Disconnected
    }

    /**
     * Force a reconnect, e.g. when the network comes back or the app comes to the foreground. An
     * attempt that has only just begun is left to finish ([kickReopens]); either way the backoff
     * starts afresh, so a try after a failure follows within a second.
     */
    fun kick() {
        if (!wanted) return
        attempts = 0
        if (!kickReopens(_state.value, clock() - openedAt)) return
        retryJob?.cancel()
        ws?.cancel()
        ws = null
        open()
    }

    private fun open() {
        // Built from the parsed server origin (never by string surgery), and checked to be that
        // exact origin over an allowed scheme. OkHttp upgrades https -> wss itself.
        val wsUrl = "${client.apiUrl}/websocket".toHttpUrlOrNull()?.newBuilder()?.apply {
            if (connectionId.isNotEmpty()) {
                addQueryParameter("connection_id", connectionId)
                addQueryParameter("sequence_number", lastSeq.get().toString())
            }
        }?.build()
        if (wsUrl == null || !TransportPolicy.isAllowed(wsUrl) || !TransportPolicy.sameOrigin(wsUrl, client.origin)) {
            wanted = false
            _state.value = SocketState.Disconnected
            return
        }
        _state.value = SocketState.Connecting
        openedAt = clock()
        gotHello = false
        opened = false
        val req = Request.Builder().url(wsUrl).apply {
            client.token?.let { header("Authorization", "Bearer $it") }
        }.build()
        ws = http.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            // Belt and braces: some proxies strip the Authorization header on upgrade.
            client.token?.let { t ->
                val seq = sendSeq.getAndIncrement()
                authSeq = seq
                val msg = buildJsonObject {
                    put("seq", seq); put("action", "authentication_challenge")
                    put("data", buildJsonObject { put("token", t) })
                }
                webSocket.send(msg.toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { MattermostJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            // A refused authentication challenge means the token is dead; let the client confirm.
            if (obj.str("status") == "FAIL" && obj["seq_reply"]?.jsonPrimitive?.longOrNull == authSeq) {
                client.onUnauthorized?.invoke()
                return
            }
            val event = obj.str("event") ?: return // replies to our actions carry no event
            val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
            val broadcast = obj["broadcast"] as? JsonObject ?: JsonObject(emptyMap())
            val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0
            if (event == "hello") {
                attempts = 0
                gotHello = true
                val newId = data.str("connection_id").orEmpty()
                val resumed = newId.isNotEmpty() && newId == connectionId
                connectionId = newId
                if (!resumed) lastSeq.set(0)
                _state.value = SocketState.Connected
                if (hadConnection && !resumed) _reconnected.tryEmit(Unit)
                hadConnection = true
            }
            lastSeq.set(seq + 1)
            _events.tryEmit(WsEvent(event, data, broadcast, seq))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("MattySocket", "socket failure: ${t.message}")
            if (response?.code == 401) {
                // The token is gone or revoked; a reconnect loop would be pointless.
                wanted = false
                client.onUnauthorized?.invoke()
            }
            dropped(webSocket)
        }
    }

    private fun dropped(socket: WebSocket) {
        if (ws !== socket) return
        ws = null
        // Upgraded but closed before "hello": often a rejected token. Ask the client to check
        // (one cheap /users/me); a network blip just falls through to the backoff below.
        if (opened && !gotHello) client.onUnauthorized?.invoke()
        _state.value = SocketState.Disconnected
        if (!wanted) return
        val delayMs = min(30_000L, 1_000L shl min(attempts, 5))
        attempts++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (wanted && ws == null) open()
        }
    }

    companion object {
        /** How long a connection attempt is given before a kick starts another. */
        const val FRESH_ATTEMPT_MS = 3_000L

        /**
         * Whether a kick starts a new attempt: never over a live connection, nor over an attempt
         * begun under [FRESH_ATTEMPT_MS] ago ([attemptAgeMs]), which would only be torn down and
         * begun again (the app coming to the foreground just after it started connecting).
         */
        fun kickReopens(state: SocketState, attemptAgeMs: Long): Boolean = when (state) {
            SocketState.Connected -> false
            SocketState.Connecting -> attemptAgeMs >= FRESH_ATTEMPT_MS
            SocketState.Disconnected -> true
        }
    }

    fun sendTyping(channelId: String, parentId: String) {
        val msg = buildJsonObject {
            put("action", "user_typing"); put("seq", sendSeq.getAndIncrement())
            put("data", buildJsonObject { put("channel_id", channelId); put("parent_id", parentId) })
        }
        ws?.send(msg.toString())
    }
}
