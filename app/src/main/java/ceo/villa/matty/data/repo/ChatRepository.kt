package ceo.villa.matty.data.repo

import android.util.Log
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.ChannelMember
import ceo.villa.matty.data.api.CustomEmoji
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.api.MattermostSocket
import ceo.villa.matty.data.api.OpenDialogRequest
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.PostList
import ceo.villa.matty.data.api.Preference
import ceo.villa.matty.data.api.Reaction
import ceo.villa.matty.data.api.SidebarCategory
import ceo.villa.matty.data.api.Team
import ceo.villa.matty.data.api.TeamUnread
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.api.UserThread
import ceo.villa.matty.data.api.WebappPlugin
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.runCatchingCancellable
import ceo.villa.matty.data.api.str
import ceo.villa.matty.data.session.AppSettings
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.data.session.AppNeed
import ceo.villa.matty.data.session.AppNeedKeeper
import ceo.villa.matty.data.session.PolicyKeeper
import ceo.villa.matty.data.session.ServerPolicy
import ceo.villa.matty.data.session.TwoFactor
import ceo.villa.matty.ui.chat.Mentions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Timeline(
    /** Post ids, newest first. */
    val order: List<String> = emptyList(),
    val hasMoreOlder: Boolean = true,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** Member's last_viewed_at when the channel was opened, for the "new messages" divider. */
    val lastViewedAt: Long = 0,
)

enum class SendState { Sending, Failed }

data class ChannelUnread(val unread: Boolean, val mentions: Long, val muted: Boolean)

/**
 * The live model of one signed-in server. Everything the UI shows is a StateFlow here, kept
 * current by REST fetches plus the WebSocket stream.
 */
class ChatRepository(
    val server: SavedServer,
    val client: MattermostClient,
    val socket: MattermostSocket,
    val settings: AppSettings,
    val scope: CoroutineScope,
    /** Recent messages kept on the device (see [ChatCache]); null when that's switched off. */
    private val chatCache: ChatCache? = null,
    /** The name colours handed out on this server, kept on the device (see [handOutNameColors]). */
    private val nameColorStore: NameColorStore? = null,
    /** Keeps what the server now requires (see [policy]) with its saved session. */
    rememberPolicy: (ServerPolicy) -> Unit = {},
    /** Keeps what newer Matty the server now requires (see [appNeed]) with its saved session. */
    rememberAppNeed: (AppNeed?) -> Unit = {},
    /**
     * Whether this server would be locked if it asked for the unlock (ui.lock.Unlocks): read when
     * it does ask, as the lock may come up after the moment it matters (see [readingChannel]).
     */
    private val unlockDue: () -> Boolean = { false },
) {
    val me = MutableStateFlow<User?>(null)
    val config = MutableStateFlow<Map<String, String>>(emptyMap())

    private val policyKeeper = PolicyKeeper(server.policy, rememberPolicy)

    /**
     * What this server requires of the app (no screen capture, an unlock): from the last session
     * until its config has been read again, then from the server itself. Decided before [ready].
     */
    val policy: StateFlow<ServerPolicy> = policyKeeper.policy

    /**
     * A newer Matty that this server requires, heard from its Matty Plugin and kept with the saved
     * session; the module that hears it tells [appNeeds]. While [appNeed] is not null the server is
     * closed behind the update page (ui.lock.UpdateGate).
     */
    val appNeeds = AppNeedKeeper(server.appNeed, ceo.villa.matty.BuildConfig.VERSION_NAME, rememberAppNeed)
    val appNeed: StateFlow<AppNeed?> = appNeeds.need
    val teams = MutableStateFlow<List<Team>>(emptyList())
    val teamUnreads = MutableStateFlow<Map<String, TeamUnread>>(emptyMap())
    /** null means the Home (direct messages) space is selected. */
    val currentTeamId = MutableStateFlow<String?>(null)
    val channels = MutableStateFlow<Map<String, Channel>>(emptyMap())
    val members = MutableStateFlow<Map<String, ChannelMember>>(emptyMap())
    val categories = MutableStateFlow<Map<String, List<SidebarCategory>>>(emptyMap())
    val users = MutableStateFlow<Map<String, User>>(emptyMap())
    val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val posts = MutableStateFlow<Map<String, Post>>(emptyMap())
    val timelines = MutableStateFlow<Map<String, Timeline>>(emptyMap())
    val threadOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val sendStates = MutableStateFlow<Map<String, SendState>>(emptyMap())
    /** "channelId|parentId" -> (userId -> expiry ms). */
    val typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val customEmojis = MutableStateFlow<Map<String, CustomEmoji>>(emptyMap())
    val preferences = MutableStateFlow<List<Preference>>(emptyList())
    val webappPlugins = MutableStateFlow<List<WebappPlugin>>(emptyList())
    val threads = MutableStateFlow<List<UserThread>>(emptyList())
    val ready = MutableStateFlow(false)
    val fatalError = MutableStateFlow<String?>(null)

    /** The channel the user is looking at right now (null when backgrounded or on a list). */
    val activeChannelId = MutableStateFlow<String?>(null)
    @Volatile var appInForeground: Boolean = true

    /**
     * The channel whose new messages count as read as they come: the open one, with the app in
     * front and no lock over it. The chat behind a lock keeps [activeChannelId] so that it opens
     * where it was after the unlock; asked of the book directly, because coming back to the front
     * reaches here before the lock has been drawn.
     */
    val readingChannel: String?
        get() = activeChannelId.value?.takeIf { appInForeground && !(policy.value.asksUnlock && unlockDue()) }

    private val _dialogs = MutableSharedFlow<OpenDialogRequest>(extraBufferCapacity = 4)
    val dialogs: SharedFlow<OpenDialogRequest> = _dialogs

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

    /** A slash command's goto_location. Kept apart from notices so no text can turn into a link. */
    private val _gotoLocations = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val gotoLocations: SharedFlow<String> = _gotoLocations

    /**
     * The id of each plugin Mattermost says is running again (switched on, or installed again over
     * itself), for the module that speaks for it: the same version put back is not a change of the
     * plugin list, but what the plugin answers may be.
     */
    private val _pluginsEnabled = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pluginsEnabled: SharedFlow<String> = _pluginsEnabled

    /** Every socket event, for plugins and the notifier. */
    val events: SharedFlow<WsEvent> get() = socket.events

    /** Posted events that should alert the user (mentions, DMs) while not looking at them. */
    private val _alerts = MutableSharedFlow<Pair<Post, WsEvent>>(extraBufferCapacity = 32)
    val alerts: SharedFlow<Pair<Post, WsEvent>> = _alerts

    val myId: String get() = me.value?.id ?: server.userId

    // ---- derived helpers -------------------------------------------------------------------

    val collapsedThreads: Boolean
        get() {
            val pref = preferences.value.firstOrNull { it.category == "display_settings" && it.name == "collapsed_reply_threads" }?.value
            return when (config.value["CollapsedThreads"]) {
                "always_on" -> true
                "default_on" -> pref != "off"
                "default_off" -> pref == "on"
                else -> false
            }
        }

    val gifEnabled: Boolean get() = config.value["EnableGifPicker"] == "true"
    val giphyKey: String get() = settings.value.giphyKeyOverride.ifBlank { config.value["GiphySdkKey"].orEmpty() }
    val imageProxyEnabled: Boolean get() = config.value["HasImageProxy"] == "true"
    /** Whether the server lets a webhook or a bot post under another name, and with another picture (both off by default). */
    val postUsernameOverride: Boolean get() = config.value["EnablePostUsernameOverride"] == "true"
    val postIconOverride: Boolean get() = config.value["EnablePostIconOverride"] == "true"
    val siteName: String get() = config.value["SiteName"]?.ifBlank { null } ?: server.name

    fun nameOf(user: User?): String {
        if (user == null || user.username.isEmpty()) return "…"
        return when (config.value["TeammateNameDisplay"]) {
            "username" -> user.username
            "full_name" -> user.fullName.ifBlank { user.username }
            else -> user.displayName
        }
    }

    fun nameOf(userId: String): String = users.value[userId]?.let { nameOf(it) } ?: "…"

    /**
     * Each known username, in lower case, to the name drawn for a mention of them. Worked out once
     * when people or the name setting change, so a message only looks up its own mentions.
     */
    val mentionLabels: StateFlow<Map<String, String>> = combine(users, config) { all, _ ->
        all.values.filter { it.username.isNotEmpty() }.associate { it.username.lowercase() to nameOf(it) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun channelTitle(ch: Channel): String = when {
        ch.isDirect -> {
            val other = ch.otherUserId(myId)
            users.value[other]?.let { nameOf(it) } ?: ch.displayName.ifBlank { "Direct message" }
        }
        // Group names list every member; like Discord, leave yourself out.
        ch.isGroup -> me.value?.username?.let { mine ->
            ch.displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != mine }.joinToString(", ")
        }?.ifBlank { null } ?: ch.displayName
        else -> ch.displayName.ifBlank { ch.name }
    }

    fun unreadOf(ch: Channel, m: ChannelMember? = members.value[ch.id]): ChannelUnread {
        if (m == null) return ChannelUnread(false, 0, false)
        val crt = collapsedThreads
        val total = if (crt) ch.totalMsgCountRoot else ch.totalMsgCount
        val read = if (crt) m.msgCountRoot else m.msgCount
        val mentions = if (crt) m.mentionCountRoot else m.mentionCount
        return ChannelUnread(total > read, mentions, m.isMuted)
    }

    fun isFlagged(postId: String) = preferences.value.any { it.category == "flagged_post" && it.name == postId && it.value == "true" }

    fun avatarUrl(userId: String): String = client.avatarUrl(userId, users.value[userId]?.lastPictureUpdate ?: 0)

    // ---- startup ---------------------------------------------------------------------------

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { socketLoop() }
        scope.launch { socket.reconnected.collect { resync() } }
        scope.launch { typingSweeper() }
        scope.launch { keepNameColors() }
        scope.launch { bootstrap() }
        socket.connect()
    }

    fun stop() {
        socket.disconnect()
        scope.cancel()
    }

    private suspend fun bootstrap() {
        try {
            val jobs = listOf(
                scope.async { me.value = client.me().also { u -> users.update { it + (u.id to u) } } },
                scope.async { config.value = readConfig() },
                scope.async { teams.value = client.myTeams().filter { it.deleteAt == 0L }.sortedBy { it.displayName.lowercase() } },
                scope.async { preferences.value = client.preferences() },
                scope.async { refreshTeamUnreads() },
                // Not part of what "ready" waits for or fails on: it looks after itself.
                scope.async { refreshPlugins() },
            )
            jobs.awaitAll()
            val start = server.lastTeamId.takeIf { id -> id.isNotEmpty() && teams.value.any { it.id == id } }
                ?: teams.value.firstOrNull()?.id
            // Load every team's channels so DMs and unread badges are complete everywhere.
            teams.value.map { t -> scope.async { loadTeam(t.id) } }.awaitAll()
            currentTeamId.value = start
            ready.value = true
            launchSafe { loadCustomEmojis() }
            launchSafe { refreshStatuses(users.value.keys) }
        } catch (t: Throwable) {
            Log.e("Matty", "bootstrap failed", t)
            // A 401 here is handled by the session manager (sign in again), not shown as an outage.
            // This server was reached over TLS at sign-in, so "no HTTPS here" never applies:
            // only a rejected certificate is worth calling out.
            fatalError.value = when {
                // The server wants two-factor authentication on this account first: only the web app sets it up.
                TwoFactor.isRequired(t) -> TwoFactor.SET_UP_ON_WEB
                t is ceo.villa.matty.data.api.ApiException -> t.message ?: "The server refused the request"
                ceo.villa.matty.data.net.NetErrors.isUntrustedCert(t) -> ceo.villa.matty.data.net.NetErrors.UNTRUSTED_CERT
                else -> "Couldn't reach $siteName. Check your connection."
            }
        }
    }

    /**
     * The server's client config, read from its own origin with this session's token. What it
     * requires of the app is taken from the answer; a read that fails throws and changes nothing.
     */
    private suspend fun readConfig(): Map<String, String> {
        val ticket = policyKeeper.asking()
        return client.clientConfig().also { policyKeeper.answered(ticket, it) }
    }

    suspend fun loadTeam(teamId: String) {
        val chans = scope.async { client.myChannels(teamId) }
        val mems = scope.async { client.myChannelMembers(teamId) }
        val cats = scope.async { runCatchingCancellable { client.categories(myId, teamId).let { c -> c.order.mapNotNull { id -> c.categories.firstOrNull { it.id == id } }.ifEmpty { c.categories } } }.getOrDefault(emptyList()) }
        val list = chans.await().filter { it.deleteAt == 0L }
        val fresh = list.associateBy { c -> c.id }
        // This team's channels as the server has them now: ones left, removed from or archived go
        // (and their unread counts with them). DMs and groups have no team and are left alone.
        var dropped: Set<String> = emptySet()
        channels.update { m ->
            dropped = m.values.filter { it.teamId == teamId && it.id !in fresh }.mapTo(HashSet()) { it.id }
            m - dropped + fresh
        }
        members.update { it - dropped + mems.await().associateBy { m -> m.channelId } }
        categories.update { it + (teamId to cats.await()) }
        // DM partners and GM members need user records for names and avatars.
        val dmUsers = list.filter { it.isDirect }.mapNotNull { it.otherUserId(myId) }
        ensureUsers(dmUsers)
    }

    suspend fun refreshTeamUnreads() {
        teamUnreads.value = runCatchingCancellable { client.myTeamUnreads() }.getOrDefault(emptyList()).associateBy { it.teamId }
    }

    private suspend fun loadCustomEmojis() {
        if (config.value["EnableCustomEmoji"] != "true") return
        val all = ArrayList<CustomEmoji>()
        var page = 0
        while (page < 20) {
            val batch = client.customEmojis(page)
            all += batch
            if (batch.size < 200) break
            page++
        }
        customEmojis.value = all.associateBy { it.name }
    }

    internal suspend fun resync() {
        // Where the open channel stood before any post arrived on the new connection: catching
        // up from a newer post would skip whatever was missed while disconnected.
        val active = activeChannelId.value
        val since = active?.let { newestPostAt(it) }
        // A plugin may have been updated or switched off while the connection was away.
        refreshPlugins()
        // So may the config (and what it requires of the app): its config_changed is not replayed.
        launchSafe { readConfig() }
        runCatchingCancellable {
            teams.value.forEach { loadTeam(it.id) }
            refreshTeamUnreads()
            active?.let { refreshChannel(it, since) }
            timelines.value.keys.filter { it != activeChannelId.value }.forEach { id ->
                dropTimeline(id) // stale; reload lazily on next open
            }
        }
    }

    fun selectTeam(teamId: String?) {
        currentTeamId.value = teamId
        if (teamId != null && categories.value[teamId] == null) launchSafe { loadTeam(teamId) }
    }

    private var pluginReads = 0

    /**
     * Reads which plugins the server runs: at start, on a fresh connection, and when the server
     * says one was switched on or off (an update is both).
     *
     * A read that fails says nothing about the plugins, so the list stays as it was and the read
     * is tried again a few times: a hiccup must not switch every native module off (and stop a
     * stream that is playing). Only the latest read asked for counts: two events a moment apart
     * mustn't leave the older answer standing.
     */
    fun refreshPlugins() {
        val mine = ++pluginReads
        scope.launch {
            for (wait in listOf(0L, 2_000L, 10_000L, 60_000L)) {
                delay(wait)
                if (mine != pluginReads) return@launch
                val list = runCatchingCancellable { client.webappPlugins() }.getOrNull() ?: continue
                if (mine == pluginReads) {
                    webappPlugins.value = list
                    // A plugin no longer running requires nothing of the app.
                    appNeeds.listed(list.mapTo(HashSet()) { it.id })
                }
                return@launch
            }
        }
    }

    // ---- users & presence ------------------------------------------------------------------

    private val userLock = Any()
    private val pendingUsers = HashSet<String>()
    private var userFetchScheduled = false

    /** Fetch any users we don't know yet, batched; every id either joins a scheduled batch or schedules one. */
    fun ensureUsers(ids: Collection<String>) {
        val known = users.value
        val missing = ids.filter { it.isNotEmpty() && known[it]?.username.isNullOrEmpty() }
        if (missing.isEmpty()) return
        synchronized(userLock) {
            pendingUsers += missing
            if (userFetchScheduled) return
            userFetchScheduled = true
        }
        scope.launch {
            delay(40)
            val batch = synchronized(userLock) {
                userFetchScheduled = false
                pendingUsers.toList().also { pendingUsers.clear() }
            }.filter { users.value[it]?.username.isNullOrEmpty() }
            batch.chunked(100).forEach { chunk ->
                runCatching { client.usersByIds(chunk) }.onSuccess { list ->
                    users.update { it + list.associateBy { u -> u.id } }
                    refreshStatuses(list.map { it.id })
                }
            }
        }
    }

    /** Usernames already asked for, found or not: the server isn't asked about one twice. */
    private val askedUsernames = HashSet<String>()

    /**
     * Learns the people mentioned in [list] that the app doesn't know yet, in one request, so a
     * mention is drawn with their name without anybody opening them first.
     */
    fun ensureMentioned(list: Collection<Post>) {
        val known = users.value.values.mapTo(HashSet()) { it.username.lowercase() }
        val wanted = Mentions.unknown(list.map { it.message }) { it in known }
        if (wanted.isEmpty()) return
        val ask = synchronized(userLock) { wanted.filter { askedUsernames.add(it) } }
        if (ask.isEmpty()) return
        launchSafe {
            ask.chunked(100).forEach { chunk ->
                runCatchingCancellable { client.usersByUsernames(chunk) }
                    .onSuccess { found -> users.update { m -> m + found.filter { it.username.isNotEmpty() }.associateBy { u -> u.id } } }
                    // Not an answer about these names: they may be asked for again.
                    .onFailure { synchronized(userLock) { askedUsernames.removeAll(chunk.toSet()) } }
            }
        }
    }

    /**
     * Hands out name colours on this server and keeps them. What was handed out before is read back
     * first; then the server's people are read once (oldest account first decides, so the order is
     * the same from the first screen) before anybody is given a colour; then whoever the app comes
     * to know later is given the next one. Should the server have more people than it reads, the
     * rest get theirs as they become known; should the first read of all fail, nobody is given
     * one until it has succeeded.
     */
    private suspend fun keepNameColors() {
        val store = nameColorStore
        // Not another server's colours, nor anyone's before this server's saved ones are back.
        NameColors.assigned = emptyMap()
        var mine = store?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.read() } }.orEmpty()
        NameColors.assigned = mine
        // Nobody is handed a colour before the server's people have been read once, now or in a
        // session before (the saved colours are that read's): handed out from whoever the device
        // happens to hold, the order would be an accident, and a colour once given is kept. Until
        // then names take the fallback, and the read is tried again, less and less often.
        var wait = 15_000L
        while (true) {
            val read = runCatchingCancellable {
                for (page in 0 until 3) {
                    val list = client.users(page, 200)
                    users.update { it + list.associateBy { u -> u.id } }
                    if (list.size < 200) break
                }
            }
            if (read.isSuccess || mine.isNotEmpty()) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(10 * 60_000L)
        }
        users.collect { all ->
            val next = handOutNameColors(mine, all.values)
            if (next !== mine) {
                mine = next
                NameColors.assigned = next
                store?.write(next)
            }
        }
    }

    suspend fun refreshStatuses(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(200).forEach { chunk ->
            runCatchingCancellable { client.statusesByIds(chunk) }.onSuccess { list ->
                statuses.update { it + list.associate { s -> s.userId to s.status } }
            }
        }
    }

    /**
     * One person's status, asked afresh for their profile: their dot is brought up to date, and
     * the answer (with when they were last active) is the caller's to keep. Null when it couldn't
     * be asked.
     */
    suspend fun statusOf(userId: String): ceo.villa.matty.data.api.UserStatus? =
        runCatchingCancellable { client.status(userId) }.onSuccess { s -> statuses.update { it + (s.userId to s.status) } }.getOrNull()

    suspend fun setMyStatus(status: String) {
        client.setStatus(myId, status)
        statuses.update { it + (myId to status) }
    }

    suspend fun setCustomStatus(emoji: String, text: String, duration: String, expiresAt: String?) {
        client.setCustomStatus(emoji, text, duration, expiresAt)
        me.value = client.me().also { u -> users.update { it + (u.id to u) } }
    }

    suspend fun clearCustomStatus() {
        client.clearCustomStatus()
        me.value = client.me().also { u -> users.update { it + (u.id to u) } }
    }

    /**
     * Who's in each channel (user ids, the people themselves are in [users]), as last read. The
     * member panel shows this at once and [refreshChannelMembers] brings it up to date behind
     * it, so opening the panel a second time is never a blank wait.
     */
    val channelMemberIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val memberReads = HashMap<String, Long>()
    private val memberJobs = HashMap<String, Job>()

    /**
     * Reads [channelId]'s members again, unless that was done within [maxAgeMs] or is under way.
     * Cheap to call whenever the list might be looked at.
     */
    fun refreshChannelMembers(channelId: String, maxAgeMs: Long = 0) {
        synchronized(memberReads) {
            if (memberJobs[channelId]?.isActive == true) return
            val last = memberReads[channelId]
            if (last != null && android.os.SystemClock.elapsedRealtime() - last < maxAgeMs) return
            memberJobs[channelId] = scope.launch {
                runCatchingCancellable { loadChannelMembers(channelId) }.onFailure {
                    // Nothing to show yet and nothing came: an empty list beats a spinner that never ends.
                    channelMemberIds.update { m -> if (channelId in m) m else m + (channelId to emptyList()) }
                }
            }
        }
    }

    private suspend fun loadChannelMembers(channelId: String) {
        val fresh = channelId !in channelMemberIds.value
        var ids = emptyList<String>()
        // The server pages through people ordered by presence. Somebody coming online between
        // two pages moves up the order: everyone between shifts down one, a person shows up twice
        // and the one who moved is on neither page. So a read in which anybody appears twice is
        // a torn one, and is made again (three times at most).
        for (attempt in 0 until 3) {
            val all = ArrayList<User>()
            // 200 a page; ten pages is more people than a panel is for.
            for (page in 0 until 10) {
                val list = client.channelMembersUsers(channelId, page, 200)
                all += list
                users.update { it + list.associateBy { u -> u.id } }
                // Their presence before they're shown: the panel files anybody it knows nothing
                // about under Offline, and would regroup them all a moment later.
                refreshStatuses(list.map { it.id })
                // A first read shows as it arrives; a re-read replaces the list once, when it's whole.
                if (fresh && attempt == 0) channelMemberIds.update { it + (channelId to all.map { u -> u.id }.distinct()) }
                if (list.size < 200) break
            }
            ids = all.map { it.id }.distinct()
            if (ids.size == all.size) break
        }
        channelMemberIds.update { it + (channelId to ids) }
        synchronized(memberReads) { memberReads[channelId] = android.os.SystemClock.elapsedRealtime() }
    }

    suspend fun openDirect(userId: String): Channel {
        val ch = client.createDirect(myId, userId)
        channels.update { it + (ch.id to ch) }
        runCatchingCancellable { client.myChannelMember(ch.id) }.onSuccess { m -> members.update { it + (m.channelId to m) } }
        ensureUsers(listOf(userId))
        return ch
    }

    /** A new group message with these people (and me), ready to open. */
    suspend fun openGroup(userIds: List<String>): Channel {
        val ch = client.createGroup(listOf(myId) + userIds)
        channels.update { it + (ch.id to ch) }
        runCatchingCancellable { client.myChannelMember(ch.id) }.onSuccess { m -> members.update { it + (m.channelId to m) } }
        ensureUsers(userIds)
        return ch
    }

    suspend fun joinChannel(channelId: String, teamId: String?) {
        client.joinChannel(channelId, myId)
        teamId?.let { loadTeam(it) }
    }

    suspend fun leaveChannel(channelId: String) {
        client.leaveChannel(channelId, myId)
        channels.update { it - channelId }
        members.update { it - channelId }
    }

    suspend fun setChannelMuted(channelId: String, muted: Boolean) {
        val markUnread = if (muted) "mention" else "all"
        client.updateChannelNotifyProps(channelId, myId, mapOf("mark_unread" to markUnread))
        members.update { m -> m[channelId]?.let { mem -> m + (channelId to mem.copy(notifyProps = mem.notifyProps + ("mark_unread" to markUnread))) } ?: m }
    }

    suspend fun setCollapsedThreads(on: Boolean) {
        val pref = Preference(myId, "display_settings", "collapsed_reply_threads", if (on) "on" else "off")
        client.savePreferences(myId, listOf(pref))
        preferences.update { l -> l.filterNot { it.category == pref.category && it.name == pref.name } + pref }
        timelines.value = emptyMap()
    }

    suspend fun updateProfileImage(bytes: ByteArray, mime: String) {
        client.uploadProfileImage(myId, bytes, mime)
        me.value = client.me().also { u -> users.update { it + (u.id to u) } }
        avatarVersion.value = System.currentTimeMillis()
    }

    /** Bumped when my picture changes, so cached copies of the old one are skipped. */
    val avatarVersion = MutableStateFlow(0L)

    /**
     * An interactive message button or menu. The server applies the integration's update and
     * ephemeral text over the socket; anything it also returns here is applied straight away.
     */
    suspend fun doPostAction(postId: String, actionId: String, option: String?) {
        val r = client.doPostAction(postId, actionId, option)
        r.update?.let { u -> posts.update { it + (u.id to u) } }
        if (r.ephemeralText.isNotBlank()) _notices.tryEmit(r.ephemeralText)
    }

    // ---- posts -----------------------------------------------------------------------------

    private fun mergePosts(list: PostList) {
        if (list.posts.isEmpty()) return
        posts.update { it + list.posts }
        val authors = HashSet<String>()
        list.posts.values.forEach { p ->
            authors += p.userId
            p.metadata.reactions?.forEach { r -> authors += r.userId }
            // Thread participants often arrive as bare ids; only keep full records.
            p.participants?.forEach { u ->
                if (u.username.isNotEmpty()) users.update { m -> if (m[u.id]?.username.isNullOrEmpty()) m + (u.id to u) else m }
                else authors += u.id
            }
        }
        ensureUsers(authors)
        ensureMentioned(list.posts.values)
    }

    suspend fun openChannel(channelId: String) {
        activeChannelId.value = channelId
        val existing = timelines.value[channelId]
        if (existing == null && restore(channelId)) {
            // What was kept on the device is on screen already: ask only what changed since.
            catchUp(channelId)
        } else if (existing == null || !existing.loaded) {
            loadLatest(channelId)
        } else {
            timelines.update { it + (channelId to existing.copy(lastViewedAt = members.value[channelId]?.lastViewedAt ?: existing.lastViewedAt)) }
            // Saved messages that were never brought up to date (left before the answer came, or offline then).
            if (channelId in behind) catchUp(channelId)
        }
        markViewed(channelId)
        channels.value[channelId]?.teamId?.takeIf { it.isNotEmpty() }?.let { /* stays on the current team */ }
    }

    /** The channel's newest page from the server, as its timeline (replacing whatever was there). */
    private suspend fun loadLatest(channelId: String) {
        run {
            starts.merge(channelId, 1, Int::plus)
            val m = members.value[channelId]
            // Whatever is still on its way out (or failed to send) stays through the reload.
            timelines.update { it + (channelId to Timeline(order = it[channelId]?.order.orEmpty().filter { id -> id.contains(':') }, loading = true, lastViewedAt = m?.lastViewedAt ?: 0)) }
            try {
                val list = client.channelPosts(channelId, collapsed = collapsedThreads)
                mergePosts(list)
                noteHeard(channelId, list)
                timelines.update {
                    val cur = it[channelId]
                    val fetched = list.order.filter { id -> list.posts[id]?.let { p -> !collapsedThreads || p.rootId.isEmpty() } ?: false }
                    // Anything posted or sent while this was loading stays, newest first.
                    val arrived = cur?.order.orEmpty().filterNot { id -> id in fetched }
                    it + (channelId to Timeline(
                        order = arrived + fetched,
                        hasMoreOlder = list.order.size >= 60, loading = false, loaded = true,
                        lastViewedAt = m?.lastViewedAt ?: 0,
                    ))
                }
                keepSoon(channelId)
            } catch (e: CancellationException) {
                // Left before it loaded (switched channel, closed the app): try again next time.
                timelines.update { it + (channelId to (it[channelId] ?: Timeline()).copy(loading = false)) }
                throw e
            } catch (e: Exception) {
                timelines.update { it + (channelId to (it[channelId] ?: Timeline()).copy(loading = false)) }
                _notices.tryEmit(failureText("Couldn't load messages", e))
            }
        }
    }

    // ---- messages kept on the device -------------------------------------------------------

    /**
     * Per channel, the latest change the server itself has reported (a post's update time):
     * where "what changed since?" is asked from. Only answers to that question and fresh page
     * loads move it. A post arriving over the socket doesn't: it says nothing about what was
     * missed before it, and asking from its time would skip exactly that.
     */
    private val watermarks = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val saving = HashMap<String, Job>()
    /** Channels showing saved messages the server hasn't yet been asked about. */
    private val behind: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun noteHeard(channelId: String, list: PostList) {
        val latest = list.posts.values.maxOfOrNull { maxOf(it.updateAt, it.createAt, it.deleteAt) } ?: return
        watermarks.merge(channelId, latest) { a, b -> maxOf(a, b) }
    }

    /** Saves [channelId]'s newest messages to the device in a moment (once, however often it's asked). */
    private fun keepSoon(channelId: String) {
        val cache = chatCache ?: return
        if (channelId.isEmpty()) return
        synchronized(saving) {
            if (saving[channelId]?.isActive == true) return
            saving[channelId] = scope.launch {
                delay(1500)
                synchronized(saving) { saving.remove(channelId) }
                val t = timelines.value[channelId]?.takeIf { it.loaded } ?: return@launch
                val mark = watermarks[channelId] ?: return@launch
                val all = posts.value
                // Not the unsent, not what only this session was shown (a command's "only visible
                // to you" answer, which the server never stored and so never reports), and
                // nothing deleted.
                val real = t.order.filter { id -> !id.contains(':') && all[id]?.let { p -> p.type != "system_ephemeral" && p.deleteAt == 0L } != false }
                // Up to the first one that's missing, should one ever be: what's kept has no holes.
                val kept = real.asSequence().take(ChatCache.MAX_POSTS).map { all[it] }.takeWhile { it != null }.filterNotNull().toList()
                val people = HashSet<String>()
                kept.forEach { p -> people += p.userId; p.metadata.reactions?.forEach { r -> people += r.userId } }
                val known = users.value
                val snapshot = ChatCache.Snapshot(
                    collapsed = collapsedThreads, posts = kept, users = people.mapNotNull { known[it] },
                    hasMoreOlder = t.hasMoreOlder || real.size > kept.size, watermark = mark, savedAt = System.currentTimeMillis(),
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (kept.isEmpty()) cache.remove(channelId) else cache.write(channelId, snapshot)
                }
            }
        }
    }

    /** Brings [channelId]'s saved messages back as its timeline; false when nothing usable is saved. */
    private suspend fun restore(channelId: String): Boolean {
        val cache = chatCache ?: return false
        val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.read(channelId) } ?: return false
        if (snap.posts.isEmpty() || snap.collapsed != collapsedThreads || snap.watermark <= 0) return false
        // Anything that arrived over the socket meanwhile is newer than what was saved: it stays.
        posts.update { m -> m + snap.posts.filter { p -> m[p.id]?.let { it.updateAt < p.updateAt } ?: true }.associateBy { it.id } }
        users.update { m -> snap.users.associateBy { it.id } + m }
        watermarks[channelId] = snap.watermark
        behind += channelId
        starts.merge(channelId, 1, Int::plus)
        val member = members.value[channelId]
        timelines.update {
            val saved = snap.posts.map { p -> p.id }
            val arrived = it[channelId]?.order.orEmpty().filterNot { id -> id in saved }
            it + (channelId to Timeline(
                order = arrived + saved, hasMoreOlder = snap.hasMoreOlder, loading = false, loaded = true,
                lastViewedAt = member?.lastViewedAt ?: 0,
            ))
        }
        // The people in it, afresh: their names and pictures may have changed since it was saved,
        // somebody whose record wasn't known then is asked for now, and everyone's presence.
        val people = HashSet<String>()
        snap.posts.forEach { p -> people += p.userId; p.metadata.reactions?.forEach { r -> people += r.userId } }
        ensureMentioned(snap.posts)
        launchSafe {
            people.filter { it.isNotEmpty() }.chunked(100).forEach { chunk ->
                runCatchingCancellable { client.usersByIds(chunk) }.onSuccess { list -> users.update { it + list.associateBy { u -> u.id } } }
            }
            refreshStatuses(people)
        }
        return true
    }

    /**
     * After [restore]: what changed on the server since the saved messages were last brought up
     * to date. When that's more than one answer holds, there may be a stretch missing in between,
     * so the saved messages give way to the newest page instead.
     */
    private suspend fun catchUp(channelId: String, quiet: Boolean = false) {
        val since = watermarks[channelId] ?: return
        try {
            val list = client.postsSince(channelId, since, collapsedThreads)
            behind -= channelId
            if (list.posts.size >= SINCE_LIMIT) {
                watermarks.remove(channelId)
                loadLatest(channelId)
            } else {
                mergeSince(channelId, list)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) _notices.tryEmit(failureText("Showing saved messages. Couldn't check for new ones", e))
            retryCatchUp(channelId)
        }
    }

    private val catchUpRetries = HashMap<String, Job>()

    /**
     * A conversation showing saved messages that couldn't be checked against the server is asked
     * about again for as long as it stays open: sooner at first, then once a minute. Otherwise
     * new messages would land over the socket on top of a stretch that was never filled in, and
     * the conversation would look live with a hole in it.
     */
    private fun retryCatchUp(channelId: String) {
        synchronized(catchUpRetries) {
            if (catchUpRetries[channelId]?.isActive == true) return
            catchUpRetries[channelId] = scope.launch {
                for (wait in sequenceOf(3_000L, 10_000L, 30_000L) + generateSequence { 60_000L }) {
                    delay(wait)
                    if (channelId !in behind || activeChannelId.value != channelId) break
                    // Not through catchUp's own failure path: this loop is the retrying.
                    val since = watermarks[channelId] ?: break
                    val list = runCatchingCancellable { client.postsSince(channelId, since, collapsedThreads) }.getOrNull() ?: continue
                    behind -= channelId
                    if (list.posts.size >= SINCE_LIMIT) { watermarks.remove(channelId); loadLatest(channelId) } else mergeSince(channelId, list)
                    break
                }
            }
        }
    }

    /** A timeline that no longer holds, with where "what changed since?" was asked from: both go together. */
    private fun dropTimeline(channelId: String) {
        starts.merge(channelId, 1, Int::plus)
        timelines.update { it - channelId }
        watermarks.remove(channelId)
    }

    /** New, edited and deleted posts from a "since" answer, into [channelId]'s timeline as it is now. */
    private fun mergeSince(channelId: String, list: PostList) {
        mergePosts(list)
        noteHeard(channelId, list)
        // Deleted since: gone from memory and from the timeline, so nothing writes them back to
        // the device (the server only marks a deleted post; its words are still in the answer).
        list.posts.values.filter { it.deleteAt != 0L }.forEach { removeLocal(it.id) }
        val all = posts.value
        timelines.update { m ->
            // Only into a timeline that is loaded: what changed since says nothing about the rest.
            val cur = m[channelId]?.takeIf { it.loaded } ?: return@update m
            val fresh = sinceAdditions(cur.order, cur.hasMoreOlder, all, list.ordered(), collapsedThreads)
            if (fresh.isEmpty()) m else m + (channelId to cur.copy(order = newestFirst(fresh + cur.order)))
        }
        keepSoon(channelId)
    }

    /** The newest real (sent, not pending) post in a channel's timeline. */
    private fun newestPostAt(channelId: String): Long? =
        timelines.value[channelId]?.order?.firstOrNull { !it.contains(':') }?.let { posts.value[it]?.createAt }

    suspend fun refreshChannel(channelId: String, since: Long? = null) {
        // Never loaded (opened offline, say): there's nothing to bring up to date, only to load
        // (unless that is already under way).
        val held = timelines.value[channelId]
        if (held?.loaded != true) { if (held?.loading != true) loadLatest(channelId); return }
        // From the last thing the server itself reported, which also catches edits and deletions
        // of older messages; failing that, from the newest message there was.
        val from = watermarks[channelId] ?: since ?: newestPostAt(channelId) ?: return
        runCatchingCancellable { client.postsSince(channelId, from, collapsedThreads) }.onSuccess { list ->
            behind -= channelId
            // More than one answer holds: there may be a stretch missing, so start from the newest page.
            if (list.posts.size >= SINCE_LIMIT) { watermarks.remove(channelId); loadLatest(channelId) }
            // Into the timeline as it is now (posts that arrived meanwhile stay), newest first:
            // a missed post can be older than one the socket delivered while this was loading.
            else mergeSince(channelId, list)
        }
    }

    /** Pending sends stay on top as they are; everything else by time, newest first. */
    private fun newestFirst(ids: List<String>): List<String> {
        val all = posts.value
        val (pending, real) = ids.distinct().partition { it.contains(':') }
        return pending + real.sortedByDescending { all[it]?.createAt ?: Long.MIN_VALUE }
    }

    /** Counts each fresh start of a conversation's timeline: an answer asked of an earlier one is not for this one. */
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun loadOlder(channelId: String) {
        val t = timelines.value[channelId] ?: return
        if (t.loading || !t.hasMoreOlder) return
        val oldest = t.order.lastOrNull { !it.contains(':') } ?: return
        val start = starts[channelId]
        timelines.update { it + (channelId to (it[channelId] ?: t).copy(loading = true)) }
        try {
            val list = client.channelPosts(channelId, before = oldest, collapsed = collapsedThreads)
            mergePosts(list)
            // Started afresh meanwhile (restored, reloaded from the newest page, dropped): this
            // page continues the old stretch and would leave a hole in the new one, and the
            // "loading" there now is the reload's own.
            if (starts[channelId] != start) return
            // Into the timeline as it is now: a post or send that landed meanwhile must stay.
            timelines.update { m ->
                // Gone meanwhile means dropped on purpose (stale after a reconnect): don't rebuild it.
                val cur = m[channelId] ?: return@update m
                val older = list.order.filter { id -> id !in cur.order && list.posts[id]?.let { p -> !collapsedThreads || p.rootId.isEmpty() } == true }
                m + (channelId to cur.copy(order = cur.order + older, hasMoreOlder = list.order.size >= 60, loading = false))
            }
            keepSoon(channelId)
        } catch (e: CancellationException) {
            if (starts[channelId] == start) timelines.update { m -> m[channelId]?.let { m + (channelId to it.copy(loading = false)) } ?: m }
            throw e
        } catch (e: Exception) {
            Log.w("Matty", "older messages", e)
            if (starts[channelId] == start) timelines.update { m -> m[channelId]?.let { m + (channelId to it.copy(loading = false)) } ?: m }
        }
    }

    suspend fun loadThread(rootId: String) {
        val list = client.thread(rootId)
        mergePosts(list)
        threadOrders.update { it + (rootId to list.posts.values.sortedBy { p -> p.createAt }.map { p -> p.id }) }
        val root = list.posts[rootId]
        val teamId = root?.let { channels.value[it.channelId]?.teamId }?.ifEmpty { null } ?: currentTeamId.value
        if (teamId != null && collapsedThreads) launchSafe { client.markThreadRead(myId, teamId, rootId) }
    }

    /** One pending "viewed" per channel: marking B read must never cancel marking A read. */
    private val viewJobs = HashMap<String, Job>()
    fun markViewed(channelId: String) {
        viewJobs.remove(channelId)?.cancel()
        viewJobs[channelId] = scope.launch {
            try {
                delay(300)
                // Never throws: offline, the server is told the next time the channel is viewed.
                client.viewChannel(channelId)
            } finally {
                if (viewJobs[channelId] === coroutineContext[Job]) viewJobs.remove(channelId)
            }
            val ch = channels.value[channelId] ?: return@launch
            members.update { m ->
                val mem = m[channelId] ?: return@update m
                m + (channelId to mem.copy(
                    msgCount = ch.totalMsgCount, msgCountRoot = ch.totalMsgCountRoot,
                    mentionCount = 0, mentionCountRoot = 0, lastViewedAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    fun leaveActiveChannel() {
        activeChannelId.value = null
    }

    // ---- sending ---------------------------------------------------------------------------

    fun sendMessage(channelId: String, rootId: String, text: String, fileIds: List<String> = emptyList(), props: JsonObject? = null) {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && fileIds.isEmpty()) return
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && fileIds.isEmpty()) {
            runCommand(channelId, rootId, trimmed)
            return
        }
        val now = System.currentTimeMillis()
        val pendingId = "$myId:$now"
        // Never behind what the phone holds of the channel, whatever its clock says (see pendingCreateAt).
        val at = pendingCreateAt(now, posts.value.values.filter { it.channelId == channelId }.maxOfOrNull { it.createAt })
        val pending = Post(
            id = pendingId, createAt = at, updateAt = at, userId = myId, channelId = channelId,
            rootId = rootId, message = trimmed, pendingPostId = pendingId, fileIds = fileIds,
            props = props ?: JsonObject(emptyMap()),
        )
        posts.update { it + (pendingId to pending) }
        sendStates.update { it + (pendingId to SendState.Sending) }
        if (rootId.isEmpty() || !collapsedThreads) prependToTimeline(channelId, pendingId)
        if (rootId.isNotEmpty()) threadOrders.update { m -> m[rootId]?.let { m + (rootId to it + pendingId) } ?: m }
        scope.launch {
            runCatching { client.createPost(channelId, trimmed, rootId, fileIds, pendingId, props) }
                .onSuccess { real -> resolvePending(pendingId, real) }
                .onFailure { e ->
                    sendStates.update { it + (pendingId to SendState.Failed) }
                    _notices.tryEmit(e.message ?: "Message not sent")
                }
        }
    }

    fun retry(pendingId: String) {
        val p = posts.value[pendingId] ?: return
        removeLocal(pendingId)
        sendMessage(p.channelId, p.rootId, p.message, p.fileIds)
    }

    /** A message deleted on the server, in a conversation that may only exist as saved messages. */
    private fun forgetSaved(channelId: String, postId: String) {
        val cache = chatCache ?: return
        if (channelId.isEmpty() || timelines.value[channelId]?.loaded == true) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { cache.forgetPost(channelId, postId) }
    }

    fun removeLocal(id: String) {
        posts.value[id]?.let { keepSoon(it.channelId) }
        // With its replies: the server deletes a thread with the message that started it, and says
        // so only for that message. Shown inline (threads not folded away) they would otherwise
        // stay on screen, and be kept on the device.
        val gone = posts.value.values.filter { it.rootId == id }.mapTo(HashSet()) { it.id } + id
        posts.update { it - gone }
        sendStates.update { it - gone }
        timelines.update { m -> m.mapValues { (_, t) -> if (t.order.any { it in gone }) t.copy(order = t.order.filterNot { it in gone }) else t } }
        threadOrders.update { m -> m.mapValues { (_, l) -> if (l.any { it in gone }) l.filterNot { it in gone } else l } }
    }

    private fun resolvePending(pendingId: String, real: Post) {
        sendStates.update { it - pendingId }
        posts.update { it - pendingId + (real.id to real) }
        timelines.update { m ->
            m.mapValues { (_, t) ->
                if (pendingId in t.order) t.copy(order = t.order.map { if (it == pendingId) real.id else it }.distinct()) else t
            }
        }
        threadOrders.update { m -> m.mapValues { (_, l) -> l.map { if (it == pendingId) real.id else it }.distinct() } }
    }

    private fun prependToTimeline(channelId: String, postId: String) {
        timelines.update { m ->
            val t = m[channelId] ?: return@update m
            if (postId in t.order) m else m + (channelId to t.copy(order = listOf(postId) + t.order))
        }
        keepSoon(channelId)
    }

    private fun runCommand(channelId: String, rootId: String, command: String) {
        val teamId = channels.value[channelId]?.teamId?.ifEmpty { null } ?: currentTeamId.value ?: teams.value.firstOrNull()?.id ?: return
        scope.launch {
            runCatching { client.executeCommand(teamId, channelId, rootId, command) }
                .onSuccess { r ->
                    if (r.gotoLocation.isNotBlank()) _gotoLocations.tryEmit(r.gotoLocation)
                }
                .onFailure { e -> _notices.tryEmit(e.message ?: "Command failed") }
        }
    }

    suspend fun editPost(postId: String, message: String) {
        val updated = try {
            client.editPost(postId, message)
        } catch (e: ceo.villa.matty.data.api.ApiException) {
            // The server's own sentence counts in seconds ("only allowed for 1800 seconds").
            if (e.errorId == "api.post.update_post.permissions_time_limit.app_error") throw EditWindowClosed(postEditLimitSeconds) else throw e
        }
        posts.update { it + (updated.id to updated) }
    }

    /** How long after posting a message can still be edited on this server, in seconds; null when there's no limit. */
    val postEditLimitSeconds: Long? get() = config.value["PostEditTimeLimit"]?.toLongOrNull()?.takeIf { it >= 0 }

    /**
     * Whether [post] is past this server's edit window (the server refuses the edit then). Judged
     * by the server's clock, as the server will: a device a few minutes fast would otherwise grey
     * out an edit the server still takes, with no way to try.
     */
    fun editWindowClosed(post: Post, now: Long = System.currentTimeMillis() + client.clockOffsetMs): Boolean =
        postEditLimitSeconds?.let { now > post.createAt + it * 1000 } ?: false

    /** Refused by the server: the message is older than its edit window. */
    class EditWindowClosed(limitSeconds: Long?) : java.io.IOException("edit window closed"), ceo.villa.matty.data.api.ServerError {
        override val serverMessage: String = limitSeconds?.let { "this server only allows edits for ${spanWords(it)} after posting" }
            ?: "this server no longer allows editing a message this old"
    }

    suspend fun deletePost(postId: String) {
        client.deletePost(postId)
        removeLocal(postId)
    }

    suspend fun togglePin(post: Post) {
        if (post.isPinned) client.unpinPost(post.id) else client.pinPost(post.id)
        posts.update { it + (post.id to post.copy(isPinned = !post.isPinned)) }
    }

    suspend fun toggleFlag(postId: String) {
        val pref = Preference(myId, "flagged_post", postId, "true")
        if (isFlagged(postId)) {
            client.deletePreferences(myId, listOf(pref))
            preferences.update { l -> l.filterNot { it.category == "flagged_post" && it.name == postId } }
        } else {
            client.savePreferences(myId, listOf(pref))
            preferences.update { it + pref }
        }
    }

    suspend fun markUnread(post: Post) {
        client.markPostUnread(myId, post.id)
        runCatching { client.myChannelMember(post.channelId) }.onSuccess { m -> members.update { it + (m.channelId to m) } }
    }

    // ---- reactions -------------------------------------------------------------------------

    fun toggleReaction(postId: String, emoji: String) {
        val post = posts.value[postId] ?: return
        val mine = post.metadata.reactions.orEmpty().any { it.userId == myId && it.emojiName == emoji }
        applyReaction(Reaction(myId, postId, emoji, System.currentTimeMillis()), add = !mine)
        settings.noteEmojiUsed(emoji)
        scope.launch {
            runCatching {
                if (mine) client.removeReaction(myId, postId, emoji) else client.addReaction(myId, postId, emoji)
            }.onFailure { e ->
                applyReaction(Reaction(myId, postId, emoji), add = mine)
                _notices.tryEmit(e.message ?: "Could not react")
            }
        }
    }

    private fun applyReaction(r: Reaction, add: Boolean) {
        posts.update { m ->
            val p = m[r.postId] ?: return@update m
            val current = p.metadata.reactions.orEmpty().filterNot { it.userId == r.userId && it.emojiName == r.emojiName }
            val next = if (add) current + r else current
            m + (p.id to p.copy(metadata = p.metadata.copy(reactions = next)))
        }
        posts.value[r.postId]?.let { keepSoon(it.channelId) }
    }

    // ---- threads ---------------------------------------------------------------------------

    suspend fun refreshThreads() {
        val teamId = currentTeamId.value ?: teams.value.firstOrNull()?.id ?: return
        val t = client.userThreads(myId, teamId)
        t.threads.forEach { th -> mergeThreadRoot(th) }
        t.threads.flatMap { it.participants }.filter { it.username.isNotEmpty() }
            .forEach { u -> users.update { m -> if (m[u.id]?.username.isNullOrEmpty()) m + (u.id to u) else m } }
        ensureUsers(t.threads.map { it.post.userId })
        // A root post's mentions are drawn by name here too.
        ensureMentioned(t.threads.map { it.post })
        threads.value = t.threads
    }

    /** Thread payloads carry a slimmed root post; only take the thread fields from it. */
    private fun mergeThreadRoot(th: UserThread) {
        posts.update { m ->
            val cur = m[th.post.id]
            val merged = cur?.copy(
                replyCount = th.replyCount, lastReplyAt = th.lastReplyAt,
                participants = th.participants.ifEmpty { cur.participants.orEmpty() },
                isFollowing = th.isFollowing,
            )
                // Nothing held yet: the slim copy stands in, dated "never updated" (as Mattermost's
                // own web app keeps it), so that any full copy of the post, from the server or
                // from the messages kept on the device, replaces it instead of losing to it.
                ?: th.post.copy(updateAt = 0)
            m + (th.post.id to merged)
        }
    }

    suspend fun recentMentions(): List<Post> {
        val teamId = currentTeamId.value ?: teams.value.firstOrNull()?.id ?: return emptyList()
        val username = me.value?.username ?: return emptyList()
        val list = client.searchPosts(teamId, "@$username")
        mergePosts(list)
        return list.ordered().sortedByDescending { it.createAt }
    }

    suspend fun savedPosts(): List<Post> {
        val list = client.flaggedPosts(myId)
        mergePosts(list)
        return list.ordered().sortedByDescending { it.createAt }
    }

    suspend fun pinnedPosts(channelId: String): List<Post> {
        val list = client.pinnedPosts(channelId)
        mergePosts(list)
        return list.ordered().sortedByDescending { it.createAt }
    }

    suspend fun search(terms: String): List<Post> {
        val teamId = currentTeamId.value ?: teams.value.firstOrNull()?.id ?: return emptyList()
        val list = client.searchPosts(teamId, terms)
        mergePosts(list)
        // Channels from search results may be ones we have not loaded (e.g. archived).
        return list.ordered().sortedByDescending { it.createAt }
    }

    // ---- typing ----------------------------------------------------------------------------

    private var lastTypingSent = 0L
    fun userTyping(channelId: String, parentId: String) {
        if (!settings.value.sendTyping) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3_000) return
        lastTypingSent = now
        socket.sendTyping(channelId, parentId)
    }

    private suspend fun typingSweeper() {
        while (scope.isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            if (typing.value.values.any { m -> m.values.any { it < now } }) {
                typing.update { m -> m.mapValues { (_, u) -> u.filterValues { it >= now } }.filterValues { it.isNotEmpty() } }
            }
        }
    }

    // ---- socket events ---------------------------------------------------------------------

    private suspend fun socketLoop() {
        socket.events.collect { e ->
            runCatching { handle(e) }.onFailure { Log.w("Matty", "event ${e.event} failed", it) }
        }
    }

    internal fun handle(e: WsEvent) {
        when (e.event) {
            "posted" -> onPosted(e)
            "post_edited" -> e.decodeString("post", Post.serializer())?.let { p ->
                posts.update { m -> m + (p.id to p) }
                ensureMentioned(listOf(p))
                keepSoon(p.channelId)
            }
            "post_deleted" -> e.decodeString("post", Post.serializer())?.let { p -> forgetSaved(p.channelId, p.id); removeLocal(p.id) }
            "reaction_added" -> e.decodeString("reaction", Reaction.serializer())?.let {
                ensureUsers(listOf(it.userId)); applyReaction(it, true)
            }
            "reaction_removed" -> e.decodeString("reaction", Reaction.serializer())?.let { applyReaction(it, false) }
            "typing" -> {
                val uid = e.data.str("user_id") ?: return
                if (uid == myId) return
                val key = e.channelId + "|" + (e.data.str("parent_id") ?: "")
                ensureUsers(listOf(uid))
                typing.update { m -> m + (key to ((m[key] ?: emptyMap()) + (uid to System.currentTimeMillis() + 6_000))) }
            }
            "status_change" -> {
                val uid = e.data.str("user_id") ?: return
                val st = e.data.str("status") ?: return
                statuses.update { it + (uid to st) }
            }
            "user_updated" -> (e.data["user"] as? JsonObject)?.let { obj ->
                runCatching { ceo.villa.matty.data.api.MattermostJson.decodeFromJsonElement(User.serializer(), obj) }.getOrNull()?.let { u ->
                    users.update { it + (u.id to u) }
                    if (u.id == myId) me.value = u
                }
            }
            "channel_viewed" -> {
                val cid = e.data.str("channel_id") ?: return
                val ch = channels.value[cid] ?: return
                members.update { m ->
                    val mem = m[cid] ?: return@update m
                    m + (cid to mem.copy(msgCount = ch.totalMsgCount, msgCountRoot = ch.totalMsgCountRoot, mentionCount = 0, mentionCountRoot = 0))
                }
            }
            "multiple_channels_viewed" -> launchSafe { teams.value.forEach { loadTeam(it.id) } }
            "channel_created", "channel_updated", "channel_converted", "direct_added", "group_added",
            "channel_deleted", "channel_restored", "channel_member_updated" -> launchSafe {
                val tid = e.teamId.ifEmpty { e.data.str("team_id").orEmpty() }
                if (tid.isNotEmpty()) loadTeam(tid) else teams.value.forEach { loadTeam(it.id) }
            }
            "user_added", "user_removed" -> {
                // Somebody joined or left a channel whose members are cached: keep the cached list true.
                val who = e.data.str("user_id").orEmpty().ifEmpty { e.userId }
                val where = e.channelId
                if (who.isNotEmpty() && where.isNotEmpty() && where in channelMemberIds.value) {
                    if (e.event == "user_added") {
                        ensureUsers(listOf(who))
                        channelMemberIds.update { m -> m[where]?.let { ids -> if (who in ids) m else m + (where to ids + who) } ?: m }
                    } else {
                        channelMemberIds.update { m -> m[where]?.let { ids -> m + (where to ids - who) } ?: m }
                    }
                }
                if (e.userId == myId || e.data.str("user_id") == myId) launchSafe { teams.value.forEach { loadTeam(it.id) } }
            }
            "added_to_team", "leave_team", "update_team" -> launchSafe {
                teams.value = client.myTeams().filter { it.deleteAt == 0L }.sortedBy { it.displayName.lowercase() }
                // A team left: its channels go too.
                val ids = teams.value.mapTo(HashSet()) { it.id }
                val gone = channels.value.values.filter { it.teamId.isNotEmpty() && it.teamId !in ids }.mapTo(HashSet()) { it.id }
                if (gone.isNotEmpty()) { channels.update { it - gone }; members.update { it - gone } }
                teams.value.forEach { loadTeam(it.id) }
            }
            "sidebar_category_created", "sidebar_category_updated", "sidebar_category_deleted", "sidebar_category_order_updated" ->
                launchSafe {
                    val tid = e.teamId.ifEmpty { currentTeamId.value.orEmpty() }
                    if (tid.isNotEmpty()) {
                        val c = client.categories(myId, tid)
                        categories.update { it + (tid to c.order.mapNotNull { id -> c.categories.firstOrNull { it.id == id } }) }
                    }
                }
            "preferences_changed" -> e.decodeString("preferences", ListSerializer(Preference.serializer()))?.let { list ->
                threadModeMayChange { preferences.update { cur -> cur.filterNot { p -> list.any { it.category == p.category && it.name == p.name } } + list } }
            }
            "preferences_deleted" -> e.decodeString("preferences", ListSerializer(Preference.serializer()))?.let { list ->
                threadModeMayChange { preferences.update { cur -> cur.filterNot { p -> list.any { it.category == p.category && it.name == p.name } } } }
            }
            "emoji_added" -> e.decodeString("emoji", CustomEmoji.serializer())?.let { em -> customEmojis.update { it + (em.name to em) } }
            "open_dialog" -> e.decodeString("dialog", OpenDialogRequest.serializer())?.let { _dialogs.tryEmit(it) }
            "ephemeral_message" -> e.decodeString("post", Post.serializer())?.let { p ->
                val eph = p.copy(type = "system_ephemeral")
                posts.update { it + (eph.id to eph) }
                if (eph.rootId.isEmpty() || !collapsedThreads) prependToTimeline(eph.channelId, eph.id)
                if (eph.rootId.isNotEmpty()) threadOrders.update { m -> m[eph.rootId]?.let { m + (eph.rootId to it + eph.id) } ?: m }
            }
            "thread_updated" -> e.decodeString("thread", UserThread.serializer())?.let { th ->
                mergeThreadRoot(th)
                threads.update { l -> listOf(th) + l.filterNot { it.id == th.id } }
            }
            "thread_read_changed" -> {
                val tid = e.data.str("thread_id") ?: return
                threads.update { l -> l.map { if (it.id == tid) it.copy(unreadReplies = 0, unreadMentions = 0) else it } }
            }
            // A plugin switched on, off or replaced by another version: which native modules apply
            // (and what they may expect of their plugin) follows the server's list.
            "plugin_enabled" -> {
                refreshPlugins()
                enabledPluginId(e.data)?.let { _pluginsEnabled.tryEmit(it) }
            }
            "plugin_disabled" -> refreshPlugins()
            "config_changed" -> (e.data["config"] as? JsonObject)?.let { obj ->
                threadModeMayChange { config.value = obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } }
                // What it now requires: more at once; less only once a read of its own says so.
                policyKeeper.pushed(config.value)
                launchSafe { readConfig() }
            }
            // Requirements arrive with a licence (Mattermost's own app reads the config again too).
            "license_changed" -> launchSafe { readConfig() }
        }
    }

    /**
     * Replies folded into threads, or shown inline: the two are different timelines. When a
     * change made elsewhere (in the web app, by an admin) switches between them, every timeline
     * held is of the wrong kind, and so is anything about to be saved from one: they are dropped,
     * and the conversation on screen is loaded again as the new kind.
     */
    private fun threadModeMayChange(change: () -> Unit) {
        val before = collapsedThreads
        change()
        if (collapsedThreads == before) return
        synchronized(saving) { saving.values.forEach { it.cancel() }; saving.clear() }
        timelines.value.keys.forEach { dropTimeline(it) }
        activeChannelId.value?.let { open -> launchSafe { loadLatest(open) } }
    }

    private fun onPosted(e: WsEvent) {
        val post = e.decodeString("post", Post.serializer()) ?: return
        ensureUsers(listOf(post.userId))
        ensureMentioned(listOf(post))
        // Our own pending message coming back: swap it in place.
        if (post.pendingPostId.isNotEmpty() && posts.value.containsKey(post.pendingPostId) && post.pendingPostId != post.id) {
            resolvePending(post.pendingPostId, post)
        } else {
            posts.update { it + (post.id to post) }
            if (post.rootId.isEmpty() || !collapsedThreads) prependToTimeline(post.channelId, post.id)
            if (post.rootId.isNotEmpty()) {
                threadOrders.update { m -> m[post.rootId]?.let { if (post.id in it) m else m + (post.rootId to it + post.id) } ?: m }
                posts.update { m ->
                    val root = m[post.rootId] ?: return@update m
                    val parts = (root.participants.orEmpty().filterNot { it.id == post.userId } +
                        (users.value[post.userId] ?: User(post.userId)))
                    m + (root.id to root.copy(replyCount = root.replyCount + 1, lastReplyAt = post.createAt, participants = parts))
                }
            }
        }
        // Unread bookkeeping.
        val isRoot = post.rootId.isEmpty()
        channels.update { m ->
            val ch = m[post.channelId] ?: return@update m
            m + (ch.id to ch.copy(
                lastPostAt = post.createAt,
                totalMsgCount = ch.totalMsgCount + 1,
                totalMsgCountRoot = ch.totalMsgCountRoot + if (isRoot) 1 else 0,
                lastRootPostAt = if (isRoot) post.createAt else ch.lastRootPostAt,
            ))
        }
        if (!channels.value.containsKey(post.channelId)) {
            // A brand-new DM/GM: fetch it so it shows up in the list.
            launchSafe {
                val ch = client.channel(post.channelId)
                channels.update { it + (ch.id to ch) }
                runCatching { client.myChannelMember(ch.id) }.onSuccess { m -> members.update { it + (m.channelId to m) } }
            }
        }
        val mentions = e.decodeString("mentions", ListSerializer(String.serializer())).orEmpty()
        val mentioned = myId in mentions
        val viewing = readingChannel == post.channelId
        if (post.userId == myId) {
            markViewed(post.channelId)
            return
        }
        if (viewing) {
            markViewed(post.channelId)
            // Read as it arrives: don't draw a "NEW" divider above it unless one is already showing.
            timelines.update { m ->
                val t = m[post.channelId] ?: return@update m
                val alreadyUnread = t.order.any { id -> id != post.id && posts.value[id]?.let { it.createAt > t.lastViewedAt && it.userId != myId } == true }
                if (alreadyUnread) m else m + (post.channelId to t.copy(lastViewedAt = maxOf(t.lastViewedAt, post.createAt)))
            }
        } else {
            if (mentioned) members.update { m ->
                val mem = m[post.channelId] ?: return@update m
                m + (mem.channelId to mem.copy(
                    mentionCount = mem.mentionCount + 1,
                    mentionCountRoot = mem.mentionCountRoot + if (isRoot) 1 else 0,
                ))
            }
            val teamId = e.data.str("team_id").orEmpty()
            if (teamId.isNotEmpty() && teamId != currentTeamId.value) teamUnreads.update { m ->
                val u = m[teamId] ?: TeamUnread(teamId)
                m + (teamId to u.copy(msgCount = u.msgCount + 1, mentionCount = u.mentionCount + if (mentioned) 1 else 0))
            }
        }
        val channelType = e.data.str("channel_type").orEmpty()
        val muted = members.value[post.channelId]?.isMuted == true
        val dm = channelType == "D" || channelType == "G"
        if (!viewing && !post.isSystem && (mentioned || (dm && settings.value.notifyAllDms && !muted))) {
            _alerts.tryEmit(post to e)
        }
    }

    // ---- misc ------------------------------------------------------------------------------

    fun launchSafe(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Matty", "background op failed", e)
        }
    }

    /**
     * Something the user asked for (delete, pin, share…), run on the session's scope so it
     * finishes even when the sheet, dialog or screen that started it closes straight away. A
     * real failure becomes a notice; being cancelled (signing out) is never reported as one.
     */
    fun launchAction(failure: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Matty", failure, e)
            _notices.tryEmit(failureText(failure, e))
        }
    }

    /**
     * Like [launchAction], for a screen that wants the result (to navigate, close a dialog…):
     * the work runs on the session's scope and reports its own failure, so it finishes and is
     * reported even if the screen closes meanwhile; only the screen's follow-up is skipped then.
     * Null when it failed.
     */
    suspend fun <T> runAction(failure: String, block: suspend () -> T): T? = scope.async {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Matty", failure, e)
            _notices.tryEmit(failureText(failure, e))
            null
        }
    }.await()

    /** [failure], with the server's own reason when it gave one; never an exception's internals. */
    fun failureText(failure: String, e: Throwable): String = when {
        e is ceo.villa.matty.data.api.ServerError -> e.serverMessage?.let { "$failure: $it" } ?: failure
        ceo.villa.matty.data.net.NetErrors.isUntrustedCert(e) -> ceo.villa.matty.data.net.NetErrors.UNTRUSTED_CERT
        ceo.villa.matty.data.net.NetErrors.isUnreachable(e) -> "$failure. Couldn't reach $siteName."
        else -> failure
    }

    fun emitNotice(text: String) { _notices.tryEmit(text) }

    /** Whether a post is still up (not deleted). When the server can't be asked, assume it is. */
    suspend fun postStillThere(postId: String): Boolean = try {
        client.post(postId).deleteAt == 0L
    } catch (e: ceo.villa.matty.data.api.ApiException) {
        e.status != 404 && e.status != 403
    } catch (e: java.io.IOException) {
        true
    }

    suspend fun updateProfile(firstName: String, lastName: String, nickname: String, position: String) {
        val u = client.patchMe(buildJsonObject {
            put("first_name", firstName); put("last_name", lastName); put("nickname", nickname); put("position", position)
        })
        me.value = u
        users.update { it + (u.id to u) }
    }

    @Suppress("unused")
    private val stringMap = MapSerializer(String.serializer(), String.serializer())
}

/**
 * The id of the plugin a `plugin_enabled` event is about. Its data carries the plugin's manifest,
 * as an object (as Mattermost sends it) or as a string of JSON; null when it names none.
 */
internal fun enabledPluginId(data: JsonObject): String? {
    val manifest = when (val m = data["manifest"]) {
        is JsonObject -> m
        is JsonPrimitive -> if (m.isString) runCatching { MattermostJson.parseToJsonElement(m.content) as? JsonObject }.getOrNull() else null
        else -> null
    }
    return manifest?.str("id")?.takeIf { it.isNotEmpty() }
}

/** A length of time in plain words: "45 seconds", "30 minutes", "1 hour", "2 days". */
fun spanWords(seconds: Long): String {
    fun n(count: Long, unit: String) = "$count $unit" + if (count == 1L) "" else "s"
    return when {
        seconds < 60 -> n(seconds, "second")
        seconds < 3600 -> n(seconds / 60, "minute")
        seconds < 86_400 -> n(seconds / 3600, "hour")
        else -> n(seconds / 86_400, "day")
    }
}

/** How many posts one "since" answer holds at most (Mattermost's own limit): at it, more may be missing. */
private const val SINCE_LIMIT = 1000

/**
 * Which posts of a "since" answer join a timeline ([order], newest first): the ones new to it and
 * not deleted, and only within the stretch that's loaded. An old message edited further back than
 * the timeline reaches would land past its end and leave a hole before it (and the next "load
 * older" would start from there, skipping the hole for good).
 */
internal fun sinceAdditions(order: List<String>, hasMoreOlder: Boolean, known: Map<String, Post>, answer: List<Post>, collapsed: Boolean): List<String> {
    val oldest = order.lastOrNull { !it.contains(':') }?.let { known[it]?.createAt }
    val floor = if (hasMoreOlder && oldest != null) oldest else Long.MIN_VALUE
    val have = order.toHashSet()
    return answer.filter { (!collapsed || it.rootId.isEmpty()) && it.deleteAt == 0L && it.createAt >= floor && it.id !in have }.map { it.id }
}

/**
 * The time a message being sent is stamped with: the phone's [clock], or one millisecond after
 * [newestHeld] (the newest post the phone holds of that channel) when that is not earlier, so the
 * message never sits behind what was there when it was written, even on a phone whose clock is
 * behind the server's.
 */
internal fun pendingCreateAt(clock: Long, newestHeld: Long?): Long =
    if (newestHeld == null || newestHeld < clock) clock else newestHeld + 1
