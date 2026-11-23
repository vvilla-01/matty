package ceo.villa.matty.data.api

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ceo.villa.matty.data.net.NetErrors
import ceo.villa.matty.data.net.TransportPolicy
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

val MattermostJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
}

/** A refusal that came with the server's own, human-readable reason. */
interface ServerError {
    val serverMessage: String?
}

class ApiException(val status: Int, val error: ApiError?, message: String) : IOException(message), ServerError {
    val errorId: String get() = error?.id.orEmpty()
    override val serverMessage: String? get() = error?.message?.trim()?.ifBlank { null }
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** How long a plugin upload may wait for Mattermost's answer, in minutes. */
internal const val PLUGIN_UPLOAD_READ_MINUTES = 2L

/**
 * The moment by which every request a coroutine makes through [MattermostClient] must be over,
 * on [clock] (by default the one that counts the phone's sleep). Work that Android gives a few
 * seconds in all (a broadcast receiver) carries one: a coroutine timeout cannot stop a request
 * already on the wire, but the call's own timeout can.
 */
class RequestDeadline(
    val endsAt: Long,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AbstractCoroutineContextElement(RequestDeadline) {
    companion object Key : CoroutineContext.Key<RequestDeadline> {
        /** [ms] from now. */
        fun after(ms: Long) = RequestDeadline(android.os.SystemClock.elapsedRealtime() + ms)
    }

    /** What is left of the time, in ms (0 or less: none). */
    fun leftMs(): Long = endsAt - clock()
}

/**
 * Thin, typed wrapper over the Mattermost REST v4 API. One instance per signed-in server.
 * Every call is main-safe (runs on [Dispatchers.IO]).
 */
class MattermostClient(
    serverUrl: String,
    @Volatile var token: String?,
    val http: OkHttpClient,
) {
    val baseUrl: String = serverUrl.trimEnd('/')
    val apiUrl: String = "$baseUrl/api/v4"

    /** The server's exact origin. The session token is only ever sent here. */
    val origin: HttpUrl = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid server address")
    val host: String = origin.host

    init {
        // Defence in depth: the UI never builds a client for an unencrypted server, and nothing else may either.
        require(TransportPolicy.isAllowed(origin)) { NetErrors.HTTPS_REQUIRED }
    }

    /**
     * API calls never follow redirects: a 307/308 on /users/login would otherwise replay the
     * password to wherever the redirect points.
     */
    val apiHttp: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Called when the server says our token is no longer valid (HTTP 401 on its own API). */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    /** The server's clock minus this device's, in ms, by the Date of its latest answer (to the second). */
    @Volatile var clockOffsetMs: Long = 0L
        private set

    fun isSameOrigin(url: String): Boolean = TransportPolicy.sameOrigin(url, origin)

    // ---- plumbing -------------------------------------------------------------------------

    fun request(pathOrUrl: String): Request.Builder {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else apiUrl + pathOrUrl
        return Request.Builder().url(url).apply {
            header("X-Requested-With", "XMLHttpRequest")
            // Only this server, over an allowed connection, ever sees the token.
            if (isSameOrigin(url)) token?.let { header("Authorization", "Bearer $it") }
        }
    }

    /**
     * The call for [req] on [http] (by default [apiHttp]), held to the calling coroutine's
     * [RequestDeadline] when it has one: its timeout is then the smaller of the client's own and
     * the time left, and with no time left it fails without going out. Without one, the client's
     * own timeout alone.
     */
    suspend fun newCall(req: Request, http: OkHttpClient = apiHttp): Call {
        val left = currentCoroutineContext()[RequestDeadline]?.leftMs() ?: return http.newCall(req)
        if (left <= 0) throw IOException("Out of time")
        val call = http.newCall(req)
        val own = http.callTimeoutMillis.toLong()
        if (own == 0L || left < own) call.timeout().timeout(left, TimeUnit.MILLISECONDS)
        return call
    }

    /**
     * Sends [req] on [http] and gives back its answer, or throws Mattermost's refusal
     * ([ApiException]). The caller reads and closes the answer on [Dispatchers.IO]: closing an
     * answer that was not read whole reads the rest of it from the network, which Android refuses
     * on the main thread. [executeUnread] does that for an answer nobody reads.
     */
    suspend fun execute(req: Request, http: OkHttpClient = apiHttp): Response = withContext(Dispatchers.IO) {
        val resp = newCall(req, http).execute()
        // The server's clock against this device's: what "five minutes after posting" means is
        // the server's to say, and a tablet's clock can be minutes out.
        if (TransportPolicy.sameOrigin(req.url, origin)) resp.headers.getDate("Date")?.let { clockOffsetMs = it.time - resp.receivedResponseAtMillis }
        if (!resp.isSuccessful) {
            val body = resp.body?.string().orEmpty()
            resp.close()
            val err = runCatching { MattermostJson.decodeFromString(ApiError.serializer(), body) }.getOrNull()
            if (resp.code == 401 && token != null && req.header("Authorization") != null &&
                req.url.encodedPath.startsWith(origin.encodedPath.trimEnd('/') + "/api/v4/") &&
                !req.url.encodedPath.endsWith("/users/login")
            ) onUnauthorized?.invoke()
            throw ApiException(resp.code, err, err?.message?.ifBlank { null } ?: "HTTP ${resp.code}")
        }
        resp
    }

    suspend fun <T> call(req: Request, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        execute(req).use { r -> MattermostJson.decodeFromString(ser, r.body!!.string()) }
    }

    suspend fun <T> get(path: String, ser: KSerializer<T>): T = call(request(path).get().build(), ser)

    suspend fun <T> post(path: String, body: JsonElement, ser: KSerializer<T>): T =
        call(request(path).post(body.toString().toRequestBody(JSON_TYPE)).build(), ser)

    suspend fun <T> put(path: String, body: JsonElement, ser: KSerializer<T>): T =
        call(request(path).put(body.toString().toRequestBody(JSON_TYPE)).build(), ser)

    /** Sends [req] on [http] and closes its answer unread, on [Dispatchers.IO] (see [execute]). */
    private suspend fun executeUnread(req: Request, http: OkHttpClient = apiHttp) = withContext(Dispatchers.IO) {
        execute(req, http).close()
    }

    suspend fun send(method: String, path: String, body: JsonElement? = null) {
        val rb = body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON_TYPE)
        executeUnread(request(path).method(method, rb).build())
    }

    suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        execute(request(path).get().build()).use { it.body!!.string() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- system / auth --------------------------------------------------------------------

    suspend fun ping(): JsonObject = get("/system/ping", JsonObject.serializer())

    suspend fun clientConfig(): Map<String, String> {
        val obj = get("/config/client?format=old", JsonObject.serializer())
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
    }

    suspend fun clientLicense(): Map<String, String> = runCatching {
        get("/license/client?format=old", JsonObject.serializer()).mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
    }.getOrDefault(emptyMap())

    /** Returns the signed-in user and the session token from the `Token` header. */
    suspend fun login(loginId: String, password: String, mfaToken: String?): Pair<User, String> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("login_id", loginId); put("password", password)
            if (!mfaToken.isNullOrBlank()) put("token", mfaToken)
            put("device_id", "")
        }
        val resp = execute(request("/users/login").post(body.toString().toRequestBody(JSON_TYPE)).build())
        resp.use { r ->
            val t = r.header("Token") ?: throw IOException("Server returned no session token")
            MattermostJson.decodeFromString(User.serializer(), r.body!!.string()) to t
        }
    }

    /**
     * Ends this session on the server. True when the server answered at all (200: revoked;
     * 400/401: already gone or not revocable), false when it couldn't be reached — the caller
     * should retry later rather than assume the token is dead.
     */
    suspend fun logout(): Boolean = try {
        send("POST", "/users/logout"); true
    } catch (e: ApiException) {
        // 400 (a token /logout can't revoke) and 401 (already invalid) are final; a 5xx, 429 or
        // redirect means the server didn't act, so keep the revocation queued and retry.
        e.status == 400 || e.status == 401
    } catch (e: IOException) {
        false
    }

    /** Attach the device to the session so the server knows this is a mobile client. */
    suspend fun attachDevice(deviceId: String) = runCatching {
        send("PUT", "/users/sessions/device", buildJsonObject { put("device_id", deviceId) })
    }

    // ---- users ----------------------------------------------------------------------------

    suspend fun me(): User = get("/users/me", User.serializer())
    suspend fun user(id: String): User = get("/users/$id", User.serializer())
    suspend fun userByUsername(username: String): User = get("/users/username/${enc(username)}", User.serializer())

    suspend fun usersByIds(ids: Collection<String>): List<User> =
        if (ids.isEmpty()) emptyList()
        else post("/users/ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }, ListSerializer(User.serializer()))

    suspend fun usersByUsernames(names: Collection<String>): List<User> =
        if (names.isEmpty()) emptyList()
        else post("/users/usernames", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }, ListSerializer(User.serializer()))

    /** One page of everybody on the server (200 at most to a page), in the server's own order. */
    suspend fun users(page: Int, perPage: Int = 200): List<User> =
        get("/users?page=$page&per_page=$perPage", ListSerializer(User.serializer()))

    suspend fun statusesByIds(ids: Collection<String>): List<UserStatus> =
        if (ids.isEmpty()) emptyList()
        else post("/users/status/ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }, ListSerializer(UserStatus.serializer()))

    /** One person's status, with when they were last active (0 when they or the server don't share it). */
    suspend fun status(userId: String): UserStatus = get("/users/$userId/status", UserStatus.serializer())

    suspend fun setStatus(userId: String, status: String) {
        send("PUT", "/users/$userId/status", buildJsonObject { put("user_id", userId); put("status", status) })
    }

    suspend fun setCustomStatus(emoji: String, text: String, duration: String, expiresAt: String?) {
        send("PUT", "/users/me/status/custom", buildJsonObject {
            put("emoji", emoji); put("text", text); put("duration", duration)
            if (expiresAt != null) put("expires_at", expiresAt)
        })
    }

    suspend fun clearCustomStatus() = send("DELETE", "/users/me/status/custom")

    suspend fun patchMe(patch: JsonObject): User = put("/users/me/patch", patch, User.serializer())

    suspend fun uploadProfileImage(userId: String, bytes: ByteArray, mime: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "avatar.jpg", bytes.toRequestBody(mime.toMediaType()))
            .build()
        executeUnread(request("/users/$userId/image").post(body).build())
    }

    /** Autocomplete users; when [channelId] is given, results are split into in/out of channel. */
    suspend fun autocompleteUsers(term: String, teamId: String?, channelId: String?): List<User> {
        val q = buildString {
            append("/users/autocomplete?name=").append(enc(term)).append("&limit=25")
            if (teamId != null) append("&in_team=").append(teamId)
            if (channelId != null) append("&in_channel=").append(channelId)
        }
        val obj = get(q, JsonObject.serializer())
        val ser = ListSerializer(User.serializer())
        val users = obj["users"]?.let { MattermostJson.decodeFromJsonElement(ser, it) }.orEmpty()
        val out = obj["out_of_channel"]?.let { MattermostJson.decodeFromJsonElement(ser, it) }.orEmpty()
        return users + out
    }

    suspend fun searchUsers(term: String, teamId: String?): List<User> =
        post("/users/search", buildJsonObject {
            put("term", term); put("allow_inactive", false); put("limit", 50)
            if (teamId != null) put("team_id", teamId)
        }, ListSerializer(User.serializer()))

    suspend fun channelMembersUsers(channelId: String, page: Int = 0, perPage: Int = 200): List<User> =
        get("/users?in_channel=$channelId&page=$page&per_page=$perPage&sort=status", ListSerializer(User.serializer()))

    fun avatarUrl(userId: String, lastUpdate: Long = 0): String = "$apiUrl/users/$userId/image?_=$lastUpdate"

    // ---- teams ----------------------------------------------------------------------------

    suspend fun myTeams(): List<Team> = get("/users/me/teams", ListSerializer(Team.serializer()))
    suspend fun myTeamUnreads(): List<TeamUnread> = get("/users/me/teams/unread", ListSerializer(TeamUnread.serializer()))
    suspend fun joinableTeams(): List<Team> = get("/teams?page=0&per_page=100", ListSerializer(Team.serializer()))
    suspend fun joinTeam(teamId: String, userId: String) =
        send("POST", "/teams/$teamId/members", buildJsonObject { put("team_id", teamId); put("user_id", userId) })

    fun teamIconUrl(teamId: String, lastUpdate: Long): String = "$apiUrl/teams/$teamId/image?_=$lastUpdate"

    // ---- channels -------------------------------------------------------------------------

    suspend fun myChannels(teamId: String): List<Channel> =
        get("/users/me/teams/$teamId/channels?include_deleted=false", ListSerializer(Channel.serializer()))

    suspend fun myChannelMembers(teamId: String): List<ChannelMember> =
        get("/users/me/teams/$teamId/channels/members", ListSerializer(ChannelMember.serializer()))

    suspend fun channel(id: String): Channel = get("/channels/$id", Channel.serializer())
    suspend fun myChannelMember(channelId: String): ChannelMember = get("/channels/$channelId/members/me", ChannelMember.serializer())

    suspend fun categories(userId: String, teamId: String): SidebarCategories =
        get("/users/$userId/teams/$teamId/channels/categories", SidebarCategories.serializer())

    suspend fun updateCategory(userId: String, teamId: String, category: SidebarCategory) {
        put("/users/$userId/teams/$teamId/channels/categories/${category.id}",
            MattermostJson.encodeToJsonElement(SidebarCategory.serializer(), category), SidebarCategory.serializer())
    }

    suspend fun viewChannel(channelId: String) = ceo.villa.matty.data.runCatchingCancellable {
        send("POST", "/channels/members/me/view", buildJsonObject { put("channel_id", channelId); put("collapsed_threads_supported", true) })
    }

    /**
     * Marks [channelIds] read for [userId] without making the person active: the server does not
     * set their status, active channel or last activity, as viewing a channel does. Throws on a
     * refusal (a server without the route answers 404).
     */
    suspend fun markChannelsRead(userId: String, channelIds: List<String>) =
        send("POST", "/channels/members/$userId/mark_read", buildJsonArray { channelIds.forEach { add(JsonPrimitive(it)) } })

    suspend fun createDirect(myId: String, otherId: String): Channel =
        post("/channels/direct", buildJsonArray { add(JsonPrimitive(myId)); add(JsonPrimitive(otherId)) }, Channel.serializer())

    suspend fun createGroup(userIds: List<String>): Channel =
        post("/channels/group", buildJsonArray { userIds.forEach { add(JsonPrimitive(it)) } }, Channel.serializer())

    suspend fun createChannel(teamId: String, name: String, displayName: String, purpose: String, private: Boolean): Channel =
        post("/channels", buildJsonObject {
            put("team_id", teamId); put("name", name); put("display_name", displayName)
            put("purpose", purpose); put("type", if (private) "P" else "O")
        }, Channel.serializer())

    suspend fun browseChannels(teamId: String): List<Channel> =
        get("/teams/$teamId/channels?page=0&per_page=200", ListSerializer(Channel.serializer()))

    suspend fun joinChannel(channelId: String, userId: String) =
        send("POST", "/channels/$channelId/members", buildJsonObject { put("user_id", userId) })

    suspend fun leaveChannel(channelId: String, userId: String) = send("DELETE", "/channels/$channelId/members/$userId")

    suspend fun updateChannelNotifyProps(channelId: String, userId: String, props: Map<String, String>) =
        send("PUT", "/channels/$channelId/members/$userId/notify_props", buildJsonObject {
            put("channel_id", channelId); put("user_id", userId)
            props.forEach { (k, v) -> put(k, v) }
        })

    suspend fun channelStats(channelId: String): JsonObject = get("/channels/$channelId/stats", JsonObject.serializer())

    // ---- posts ----------------------------------------------------------------------------

    suspend fun channelPosts(channelId: String, before: String? = null, perPage: Int = 60, collapsed: Boolean): PostList {
        val q = buildString {
            append("/channels/$channelId/posts?per_page=$perPage&skipFetchThreads=false")
            append("&collapsedThreads=$collapsed&collapsedThreadsExtended=false")
            if (before != null) append("&before=").append(before)
        }
        return get(q, PostList.serializer())
    }

    suspend fun postsSince(channelId: String, since: Long, collapsed: Boolean): PostList =
        get("/channels/$channelId/posts?since=$since&collapsedThreads=$collapsed&skipFetchThreads=false", PostList.serializer())

    suspend fun thread(postId: String): PostList =
        get("/posts/$postId/thread?skipFetchThreads=false&collapsedThreads=true&collapsedThreadsExtended=false&direction=down&perPage=200", PostList.serializer())

    suspend fun post(id: String): Post = get("/posts/$id", Post.serializer())

    /** [setOnline] false: posting does not make the person show as online (a reply from a notification). */
    suspend fun createPost(
        channelId: String, message: String, rootId: String, fileIds: List<String>, pendingId: String,
        props: JsonObject? = null, setOnline: Boolean = true,
    ): Post =
        post(if (setOnline) "/posts" else "/posts?set_online=false", buildJsonObject {
            put("channel_id", channelId); put("message", message); put("root_id", rootId)
            put("pending_post_id", pendingId)
            put("file_ids", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
            if (props != null) put("props", props)
        }, Post.serializer())

    suspend fun editPost(id: String, message: String): Post =
        put("/posts/$id/patch", buildJsonObject { put("message", message) }, Post.serializer())

    suspend fun deletePost(id: String) = send("DELETE", "/posts/$id")
    suspend fun pinPost(id: String) = send("POST", "/posts/$id/pin")
    suspend fun unpinPost(id: String) = send("POST", "/posts/$id/unpin")

    suspend fun pinnedPosts(channelId: String): PostList = get("/channels/$channelId/pinned", PostList.serializer())

    suspend fun flaggedPosts(userId: String): PostList = get("/users/$userId/posts/flagged?per_page=60", PostList.serializer())

    suspend fun markPostUnread(userId: String, postId: String) =
        send("POST", "/users/$userId/posts/$postId/set_unread", buildJsonObject { put("collapsed_threads_supported", true) })

    suspend fun searchPosts(teamId: String, terms: String, page: Int = 0): PostList =
        post("/teams/$teamId/posts/search", buildJsonObject {
            put("terms", terms); put("is_or_search", false); put("include_deleted_channels", false)
            put("time_zone_offset", java.util.TimeZone.getDefault().rawOffset / 1000)
            put("page", page); put("per_page", 40)
        }, PostList.serializer())

    suspend fun doPostAction(postId: String, actionId: String, selectedOption: String?): PostActionResponse =
        post("/posts/$postId/actions/$actionId", buildJsonObject {
            if (selectedOption != null) put("selected_option", selectedOption)
        }, PostActionResponse.serializer())

    // ---- reactions ------------------------------------------------------------------------

    suspend fun addReaction(userId: String, postId: String, emoji: String) =
        send("POST", "/reactions", buildJsonObject { put("user_id", userId); put("post_id", postId); put("emoji_name", emoji) })

    suspend fun removeReaction(userId: String, postId: String, emoji: String) =
        send("DELETE", "/users/$userId/posts/$postId/reactions/${enc(emoji)}")

    // ---- threads (collapsed reply threads) ------------------------------------------------

    suspend fun userThreads(userId: String, teamId: String, unreadOnly: Boolean = false): UserThreads =
        get("/users/$userId/teams/$teamId/threads?extended=true&deleted=false&per_page=40&unread=$unreadOnly", UserThreads.serializer())

    /** Marks the thread read up to [at] (Unix ms): its replies made then or before count as read. */
    suspend fun markThreadRead(userId: String, teamId: String, threadId: String, at: Long = System.currentTimeMillis()) = runCatching {
        send("PUT", "/users/$userId/teams/$teamId/threads/$threadId/read/$at")
    }

    suspend fun followThread(userId: String, teamId: String, threadId: String, follow: Boolean) =
        send(if (follow) "PUT" else "DELETE", "/users/$userId/teams/$teamId/threads/$threadId/following")

    // ---- files ----------------------------------------------------------------------------

    fun fileUrl(id: String) = "$apiUrl/files/$id"
    fun fileThumbUrl(id: String) = "$apiUrl/files/$id/thumbnail"
    fun filePreviewUrl(id: String) = "$apiUrl/files/$id/preview"

    suspend fun uploadFile(
        channelId: String,
        fileName: String,
        mime: String,
        length: Long,
        open: () -> InputStream,
        onProgress: (Float) -> Unit,
    ): FileInfo {
        // Checked between chunks: an upload nobody is waiting for any more stops sending.
        val job = currentCoroutineContext()[Job]
        val stream = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                open().source().use { src ->
                    var total = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        if (job?.isActive == false) throw IOException("Upload cancelled")
                        val n = src.read(buf, 64 * 1024)
                        if (n < 0) break
                        sink.write(buf, n)
                        total += n
                        if (length > 0) onProgress((total.toFloat() / length).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("channel_id", channelId)
            .addFormDataPart("files", fileName, stream)
            .build()
        val res = try {
            call(request("/files").post(body).build(), FileUploadResponse.serializer())
        } catch (e: IOException) {
            // Stopped because nobody is waiting any more: that's a cancellation, not a failure.
            currentCoroutineContext().ensureActive()
            throw e
        }
        return res.fileInfos.firstOrNull() ?: throw IOException("Upload returned no file")
    }

    // ---- plugins --------------------------------------------------------------------------

    /**
     * The request that hands the plugin bundle in [file] to Mattermost under [name], over the
     * plugin of the same id if it is there, as the System Console's upload does (system admins).
     */
    fun uploadPluginRequest(file: File, name: String): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("plugin", name, file.asRequestBody("application/gzip".toMediaType()))
            .addFormDataPart("force", "true")
            .build()
        return request("/plugins").post(body).build()
    }

    /**
     * The client for [uploadPlugin]: Mattermost installs the plugin before it answers, so its
     * answer is waited for up to [PLUGIN_UPLOAD_READ_MINUTES].
     */
    internal val pluginUploadHttp: OkHttpClient by lazy { apiHttp.newBuilder().readTimeout(PLUGIN_UPLOAD_READ_MINUTES, TimeUnit.MINUTES).build() }

    /** Sends [uploadPluginRequest]. Refused with Mattermost's own sentence ([ApiException]). */
    suspend fun uploadPlugin(file: File, name: String) {
        executeUnread(uploadPluginRequest(file, name), pluginUploadHttp)
    }

    // ---- emoji ----------------------------------------------------------------------------

    suspend fun customEmojis(page: Int): List<CustomEmoji> =
        get("/emoji?page=$page&per_page=200&sort=name", ListSerializer(CustomEmoji.serializer()))

    fun customEmojiUrl(id: String) = "$apiUrl/emoji/$id/image"

    // ---- preferences ----------------------------------------------------------------------

    suspend fun preferences(): List<Preference> = get("/users/me/preferences", ListSerializer(Preference.serializer()))

    suspend fun savePreferences(userId: String, prefs: List<Preference>) {
        put("/users/$userId/preferences", MattermostJson.encodeToJsonElement(ListSerializer(Preference.serializer()), prefs), JsonElement.serializer())
    }

    suspend fun deletePreferences(userId: String, prefs: List<Preference>) {
        post("/users/$userId/preferences/delete", MattermostJson.encodeToJsonElement(ListSerializer(Preference.serializer()), prefs), JsonElement.serializer())
    }

    // ---- commands & integrations ----------------------------------------------------------

    suspend fun commandSuggestions(teamId: String, channelId: String, rootId: String, input: String): List<AutocompleteSuggestion> =
        get("/teams/$teamId/commands/autocomplete_suggestions?user_input=${enc(input)}&channel_id=$channelId&root_id=$rootId",
            ListSerializer(AutocompleteSuggestion.serializer()))

    suspend fun teamCommands(teamId: String): List<SlashCommand> =
        get("/teams/$teamId/commands/autocomplete", ListSerializer(SlashCommand.serializer()))

    suspend fun executeCommand(teamId: String, channelId: String, rootId: String, command: String): CommandResponse =
        post("/commands/execute", buildJsonObject {
            put("channel_id", channelId); put("team_id", teamId); put("root_id", rootId); put("command", command)
        }, CommandResponse.serializer())

    suspend fun submitDialog(
        url: String, callbackId: String, state: String, userId: String, channelId: String, teamId: String,
        submission: Map<String, JsonElement>, cancelled: Boolean,
    ): DialogSubmitResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url", url); put("callback_id", callbackId); put("state", state)
            put("user_id", userId); put("channel_id", channelId); put("team_id", teamId)
            put("submission", JsonObject(submission)); put("cancelled", cancelled)
        }
        execute(request("/actions/dialogs/submit").post(body.toString().toRequestBody(JSON_TYPE)).build()).use { r ->
            val s = r.body?.string().orEmpty()
            if (s.isBlank()) DialogSubmitResponse() else MattermostJson.decodeFromString(DialogSubmitResponse.serializer(), s)
        }
    }

    /** The plugins this server runs that have a web part; throws when the server couldn't be asked. */
    suspend fun webappPlugins(): List<WebappPlugin> = get("/plugins/webapp", ListSerializer(WebappPlugin.serializer()))

    /**
     * Where a picture somebody posted is loaded from: the server's own address as it is, another
     * site's through the server's image proxy when one is configured, or straight from that site
     * when the transport policy allows it (https). Null for anything else: a member's post never
     * makes the phone open a file:, content:, android.resource: or data: address of its own.
     */
    fun proxiedImage(url: String, proxyEnabled: Boolean): String? {
        val u = url.trim()
        if (!TransportPolicy.isWebAddress(u)) return null
        return when {
            isSameOrigin(u) -> u
            // The server fetches it, so plain http is the server's to judge.
            proxyEnabled -> "$apiUrl/image?url=${Uri.encode(u)}"
            TransportPolicy.isAllowed(u) -> u
            else -> null
        }
    }

    @Suppress("unused")
    private val stringMap = MapSerializer(String.serializer(), String.serializer())
}
