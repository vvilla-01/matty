package ceo.villa.matty

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.net.Downloader
import ceo.villa.matty.data.net.TransportPolicy
import ceo.villa.matty.data.api.MattermostSocket
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.data.giphy.GiphyClient
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.AppSettings
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.data.session.SessionStore
import ceo.villa.matty.plugins.PluginRegistry
import ceo.villa.matty.service.ConnectionService
import ceo.villa.matty.service.Notifier
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import ceo.villa.matty.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MattyApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * Only what needs no network: a push or a notification action starts this process too, and
     * must not connect it. The app itself starts with [SessionManager.ensureActive], called by
     * [MainActivity] and [ConnectionService].
     */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.migrate()
        // A plugin bundle left by an app that was stopped in the middle of an install: no install runs yet.
        container.appScope.launch(Dispatchers.IO) { runCatching { ceo.villa.matty.plugins.villanet.removeLeftUpdates(cacheDir) } }
        // Push: what a distributor hands the app is carried out here; a server that goes takes
        // its registrations with it.
        ceo.villa.matty.push.PushReceiver.sink = container.push
        container.push.watch()
        // How Android says this copy is signed, read once (Settings shows it).
        ceo.villa.matty.verify.SelfCheck.start(this, container.appScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.sessions.onForeground(true)
            override fun onStop(owner: LifecycleOwner) = container.sessions.onForeground(false)
        })
        container.appScope.launch { EmojiStore.load(this@MattyApp) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

val Context.container: AppContainer get() = (applicationContext as MattyApp).container

/**
 * The last line of defence for background work: an exception nothing else caught is logged, never
 * allowed to take the whole app down (Android's default for an uncaught coroutine exception).
 */
val NeverCrash = CoroutineExceptionHandler { _, e -> android.util.Log.e("Matty", "uncaught in background work", e) }

/** Process-wide singletons. Deliberately tiny: no DI framework. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + NeverCrash)
    val settings = AppSettings(app)
    val sessionStore = SessionStore(app)

    private val userAgent = "Matty/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; Mattermost client)"

    /**
     * The one network client everything derives from: TLS 1.2+ only (no cleartext in release),
     * no http<->https redirects, and a guard that refuses to send credentials anywhere the
     * transport policy doesn't allow. See data/net/Transport.kt.
     */
    val baseHttp: OkHttpClient = TransportPolicy.harden(OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        })
        .build()

    val sessions = SessionManager(this)
    val giphy = GiphyClient(baseHttp)
    val plugins = PluginRegistry(this)
    val notifier = Notifier(app, this)
    /** Push notifications through UnifiedPush (push/): registering, the servers' side, what arrives. */
    val push by lazy { ceo.villa.matty.push.PushCenter(this) }
    val downloader = Downloader(app)

    private val imageHttpCache = Cache(File(app.cacheDir, "http_images"), 64L * 1024 * 1024)

    /**
     * Images: Mattermost avatars and files need the session token, but only requests to the
     * active server's exact origin (scheme, host and port) get it — never Giphy, link-preview
     * hosts, look-alike hosts, another port, or plain http.
     */
    val mediaHttp: OkHttpClient by lazy {
        baseHttp.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val client = sessions.current.value?.client
                // The token only, and only to the signed-in server: see TransportPolicy.mediaHeaders.
                val headers = if (client == null || req.header("Authorization") != null) emptyMap()
                else TransportPolicy.mediaHeaders(req.url, client.origin, client.token)
                if (headers.isEmpty()) chain.proceed(req)
                else chain.proceed(req.newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }.build())
            })
            // A plugin picture refused for the app check is heard like the plugin's other requests.
            .addInterceptor(ceo.villa.matty.plugins.villanet.PluginMediaRefusals.interceptor)
            .build()
    }

    /** Stills for video attachments (the server makes previews for pictures only). */
    val videoStills by lazy { ceo.villa.matty.data.net.VideoStills({ mediaHttp }, devicePrefs) }

    val imageLoader: ImageLoader by lazy {
        val imageHttp = mediaHttp.newBuilder().cache(imageHttpCache).build()
        ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
                add(videoStills.FetcherFactory())
                add(ceo.villa.matty.data.net.VideoStills.StillKeyer())
                add(AnimatedImageDecoder.Factory())
                // Plugin icons and brand art are often SVG.
                add(coil3.svg.SvgDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "coil")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(coil3.util.DebugLogger(coil3.util.Logger.Level.Warn)) }
            .build()
    }

    /**
     * Everything private a signed-in session leaves on the device: cached pictures and files,
     * web sign-in cookies, drafts, pending shares, message notifications and name colours. Run
     * once the server is removed: the message notifications of the servers still signed in stay.
     * It settles a wipe owed by [oweSharedWipe].
     */
    fun wipeSessionData() {
        // Disk work goes off the main thread; a flag, set in the same edit that settles the owed
        // wipe, makes sure it finishes even if the process dies half-way (it is re-run on the next launch).
        devicePrefs.edit().remove(KEY_WIPE_OWED).putBoolean("wipe_pending", true).apply()
        runCatching { imageLoader.memoryCache?.clear() }
        videoStills.forget()
        appScope.launch(Dispatchers.IO) { wipeDiskCaches() }
        runCatching {
            android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        ceo.villa.matty.ui.chat.Drafts.map.clear()
        ceo.villa.matty.ui.chat.Drafts.forgetUploads()
        ceo.villa.matty.ui.ShareInbox.clear()
        wipeServersGone()
    }

    /**
     * The wipe of what every server shares is owed to the next start of the process ([wipeOwed]),
     * written to disk at once: called before servers are taken out of the store, so that a process
     * that ends before their data goes still wipes it. [wipeSessionData] settles it.
     */
    fun oweSharedWipe() {
        devicePrefs.edit().putBoolean(KEY_WIPE_OWED, true).commit()
    }

    /**
     * Only what is kept by server, of the servers no longer saved: their message notifications,
     * name colours and kept messages. What is shared by every server (drafts, uploads, the pending
     * share, caches, cookies) stays owed to the next start of the process ([oweSharedWipe]):
     * another server is live and using it.
     */
    fun wipeServerData() {
        wipeServersGone()
    }

    private fun wipeServersGone() {
        forgetNameColors()
        notifier.clearDropped(sessionStore.servers.value.mapTo(HashSet()) { it.id })
        // Off the main thread. The folders are listed before the saved servers are read: a folder
        // is made only for a server already saved, so one signed in meanwhile keeps its own.
        appScope.launch(Dispatchers.IO) {
            val folders = chatsDir.list()?.toList().orEmpty()
            val saved = sessionStore.servers.value.map { it.id }
            for (name in chatFoldersGone(folders, saved)) runCatching { File(chatsDir, name).deleteRecursively() }
        }
    }

    /** At the start of the process, before anything is live: the wipe [oweSharedWipe] left owed. */
    fun wipeOwed() {
        if (devicePrefs.getBoolean(KEY_WIPE_OWED, false)) wipeSessionData()
    }

    private val devicePrefs get() = app.getSharedPreferences("matty_device", Context.MODE_PRIVATE)

    companion object {
        /** A wipe of what every server shares, owed to the next start of the process. */
        private const val KEY_WIPE_OWED = "wipe_owed"

        /** The folder under chats/ that keeps [serverId]'s recent messages; null for an id that names none. */
        fun chatFolder(serverId: String): String? = serverId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { null }

        /** Of the [folders] under chats/, those of no server in [saved] (ids). */
        fun chatFoldersGone(folders: List<String>, saved: Collection<String>): List<String> {
            val kept = saved.mapNotNullTo(HashSet(), ::chatFolder)
            return folders.filter { it !in kept }
        }
    }

    /** Who has which name colour, one entry per signed-in server. */
    private val nameColorPrefs get() = app.getSharedPreferences("matty_name_colors", Context.MODE_PRIVATE)

    fun nameColorStore(serverId: String) = ceo.villa.matty.data.repo.NameColorStore(nameColorPrefs, serverId)

    /** The name colours of every server no longer signed in to go (the others' stay as they were). */
    private fun forgetNameColors() {
        val signedIn = sessionStore.servers.value.mapTo(HashSet()) { it.id }
        val prefs = nameColorPrefs
        prefs.edit().apply { prefs.all.keys.filter { it !in signedIn }.forEach { remove(it) } }.apply()
    }

    /** Where recent messages are kept on the device, one folder per signed-in server. */
    private val chatsDir get() = File(app.cacheDir, "chats")

    /** The store of recent messages for a server (it keeps nothing while that's switched off in Settings). */
    fun chatCache(serverId: String): ceo.villa.matty.data.repo.ChatCache? {
        val safe = chatFolder(serverId) ?: return null
        return ceo.villa.matty.data.repo.ChatCache(File(chatsDir, safe), { settings.value.keepMessages }) { sessionStore.cacheKey() }
            .also { cache -> appScope.launch(Dispatchers.IO) { cache.prune() } }
    }

    /** The saved messages go (switched off in Settings, or a sign-out), and the key they were under. */
    fun wipeSavedMessages() {
        runCatching { chatsDir.deleteRecursively() }
        runCatching { sessionStore.dropCacheKey() }
    }

    private fun wipeDiskCaches() {
        wipeSavedMessages()
        runCatching { imageLoader.diskCache?.clear() }
        runCatching { imageHttpCache.evictAll() }
        runCatching { File(app.cacheDir, "shared").deleteRecursively() }
        runCatching { ceo.villa.matty.plugins.villanet.removeLeftUpdates(app.cacheDir) }
        devicePrefs.edit().remove("wipe_pending").apply()
    }

    /** Clean-ups for data older builds may have left behind; each runs until it succeeds once. */
    fun migrate() {
        if (devicePrefs.getBoolean("wipe_pending", false)) appScope.launch(Dispatchers.IO) { wipeDiskCaches() }
        // Before 0.1.1, GitLab sign-in left the session cookie in the WebView's cookie store.
        val p = app.getSharedPreferences("matty_migrations", Context.MODE_PRIVATE)
        if (!p.getBoolean("webview_wiped_v1", false)) runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            android.webkit.WebStorage.getInstance().deleteAllData()
            cm.removeAllCookies { cm.flush(); p.edit().putBoolean("webview_wiped_v1", true).apply() }
        }
    }

    val deviceId: String by lazy {
        val p = app.getSharedPreferences("matty_device", Context.MODE_PRIVATE)
        p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }

    /**
     * What Matty's own intents carry ([IntentKey]), made once and kept with the app's private
     * data (never backed up), so a notification still opens its channel after the process was
     * killed. Not [deviceId], which goes to the server.
     */
    val intentKey: String by lazy {
        val p = app.getSharedPreferences("matty_intent_key", Context.MODE_PRIVATE)
        p.getString("key", null) ?: IntentKey.create().also { p.edit().putString("key", it).commit() }
    }
}

/** Owns the live [ChatRepository] for whichever saved server is active. */
class SessionManager(private val c: AppContainer) {
    private val _current = MutableStateFlow<ChatRepository?>(null)
    val current: StateFlow<ChatRepository?> = _current

    /** One-off messages about the session (why you were signed out, a revoke that's pending…). */
    val notice = MutableStateFlow<String?>(null)

    private fun addNotice(msg: String) {
        notice.value = listOfNotNull(notice.value?.takeIf { msg !in it }, msg).joinToString("\n\n")
    }

    private fun insecureNotice(names: List<String>) = addNotice(
        "Signed out of ${names.joinToString()}: it doesn't use HTTPS, which Matty now requires. Your old session there " +
            "may still be active — end it from the web app (Profile › Security › View and log out of active sessions), " +
            "then sign in again once the server has HTTPS."
    )

    /** The server whose session just ended on the server side; sign-in pre-fills it. */
    val ended = MutableStateFlow<SavedServer?>(null)

    /** Whether the app is on screen; set by the process's lifecycle (a process a push started never is). */
    private var foreground = false

    private val _started = MutableStateFlow(false)

    /**
     * True once the app has started in this process: the first [ensureActive] ran. A push or a
     * notification action never starts it; what is done "at every app start" waits for this.
     */
    val started: StateFlow<Boolean> = _started

    /**
     * Starts the app's live side, on the main thread: idempotent, and [current] is set when it
     * returns (unless no saved server can be used). The first call in a process also does the
     * start's own work: servers that can't be used any more are dropped, ended sessions wiped and
     * pending sign-outs retried. [serverId] is the server to start with when nothing is live yet
     * (the one a notification names); otherwise the active one.
     */
    fun ensureActive(serverId: String? = null) {
        val first = !_started.value
        if (first) {
            dropInsecure()
            dropEnded()
            c.wipeOwed()
            retryRevocations()
        }
        if (_current.value == null) chooseStart(c.sessionStore.servers.value, c.sessionStore.active, serverId, endedIds())?.let { activate(it) }
        _started.value = true
        // Once a process: every push registration is REGISTERed again and each server asked how it stands.
        if (first) c.push.onStart()
    }

    private fun dropInsecure() {
        // Servers saved before Matty required HTTPS can't be used. Their tokens are dropped
        // locally and NOT sent anywhere: revoking over http would leak them all over again.
        val insecure = c.sessionStore.servers.value.filterNot { TransportPolicy.isAllowed(it.url) }
        insecure.forEach { c.sessionStore.remove(it.id) }
        // Dropped as a sign-out drops a server: what it left on the phone goes too.
        if (insecure.isNotEmpty()) c.wipeSessionData()
        if (insecure.isNotEmpty()) insecureNotice(insecure.map { it.name })
    }

    fun clientFor(url: String, token: String? = null) = MattermostClient(url, token, c.baseHttp)

    private val oneOffHttp = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    /**
     * A client for saved server [serverId] that is not the live session ([ceo.villa.matty.service.OneOff]),
     * every call held to [seconds]: 3 inside a push's receiver, 10 for a notification's action.
     * Null when the server isn't saved, its session ended, or its sign-in doesn't open now. Any
     * thread (it opens the sealed token: better off the main one).
     */
    fun oneOff(serverId: String, seconds: Long): ceo.villa.matty.service.OneOff? {
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return null
        if (serverId in endedIds()) return null
        val token = c.sessionStore.unseal(server.sealedToken) ?: return null
        val http = oneOffHttp.getOrPut(seconds) { ceo.villa.matty.service.OneOff.http(c.baseHttp, seconds) }
        return ceo.villa.matty.service.OneOff.of(server, token, http)
    }

    private val endedPrefs get() = c.app.getSharedPreferences("matty_ended", Context.MODE_PRIVATE)

    private fun endedIds(): Set<String> = synchronized(this) { endedPrefs.getStringSet(KEY_ENDED, null).orEmpty() }

    /**
     * The server confirmed, outside the live session (a notification's action, a push), that the
     * session on [serverId] is over. Its notifications go at once and one says so; nothing more
     * is sent with its token; once the app has started in this process the server goes at once
     * (the live session included), otherwise at the next app start ([dropEnded]). Any thread.
     */
    fun endedAway(serverId: String) {
        val server = c.sessionStore.servers.value.firstOrNull { it.id == serverId } ?: return
        synchronized(this) { endedPrefs.edit().putStringSet(KEY_ENDED, endedIds() + serverId).commit() }
        // Its push registrations go now: UNREGISTER, keys deleted (nothing is sent to its server).
        c.push.forget(serverId)
        c.appScope.launch {
            c.notifier.clearMessages(serverId)
            c.notifier.showEnded(server)
            if (_started.value) dropEnded(setOf(serverId))
        }
    }

    /**
     * What [endedAway] marked ([only] those, or every one at the start) goes, and its marks; a mark
     * written meanwhile stays. At the start, or when the ended server was the live one, the wipe
     * is [confirmSessionEnded]'s; while another server is live only the ended server's own data
     * goes, and the rest of the wipe is owed to the next start ([AppContainer.oweSharedWipe]). On
     * the main thread.
     */
    private fun dropEnded(only: Set<String>? = null) {
        val ids = endedIds().let { marked -> if (only == null) marked else marked intersect only }.ifEmpty { return }
        val live = _current.value?.takeIf { it.server.id in ids }
        if (live != null) { teardown(live); _current.value = null }
        val gone = c.sessionStore.servers.value.filter { it.id in ids }
        if (gone.isNotEmpty()) {
            val all = wipesAll(atStart = only == null, otherLive = _current.value != null)
            // Owed before the servers go: a process that ends between the two still wipes at its next start.
            c.oweSharedWipe()
            gone.forEach { c.sessionStore.remove(it.id) }
            if (all) c.wipeSessionData() else c.wipeServerData()
        }
        gone.lastOrNull()?.let { ended.value = it }
        gone.forEach { addNotice("Your session on ${it.name} ended. Sign in again to keep chatting.") }
        synchronized(this) { endedPrefs.edit().putStringSet(KEY_ENDED, endedIds() - ids).commit() }
        if (live != null) activateOrStop(c.sessionStore.active)
    }

    /**
     * [server] becomes the live one; when there is none, or it cannot be started, the background
     * connection stops (nothing is live).
     */
    private fun activateOrStop(server: SavedServer?) {
        if (server == null || !activate(server)) ConnectionService.sync(c.app, false)
    }

    /**
     * Ending a session on its server gets 10 seconds in all, enforced by OkHttp itself: a
     * coroutine timeout can't interrupt a request already on the wire, and would report a slow
     * but successful sign-out as a failure.
     */
    private val revokeHttp by lazy { c.baseHttp.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    private fun revokeClient(url: String, token: String) = MattermostClient(url, token, revokeHttp)

    /** Short timeouts for probing an address before sign-in. */
    private val probeHttp by lazy {
        c.baseHttp.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    fun probeClient(url: String) = MattermostClient(url, null, probeHttp)

    fun activate(server: SavedServer): Boolean {
        // A session the server said is over is never started again; it goes with dropEnded.
        if (server.id in endedIds()) return false
        if (!TransportPolicy.isAllowed(server.url)) {
            c.sessionStore.remove(server.id)
            c.wipeSessionData()
            insecureNotice(listOf(server.name))
            return false
        }
        val token = when (val u = c.sessionStore.unlock(server.sealedToken)) {
            is SessionStore.Unseal.Ok -> u.token
            SessionStore.Unseal.Gone -> {
                // The device key that sealed it is gone for good (e.g. a security reset). The
                // server is dropped, so the wipe a sign-out runs runs too: its pictures, name
                // colours and notifications don't stay behind. Removed first, so the name colours
                // of the servers still signed in are kept.
                _current.value?.takeIf { it.server.id == server.id }?.let { teardown(it); _current.value = null }
                c.sessionStore.remove(server.id)
                c.wipeSessionData()
                addNotice("Please sign in to ${server.name} again.")
                val next = c.sessionStore.active
                if (next != null && next.id != server.id) return activate(next)
                ConnectionService.sync(c.app, false)
                return false
            }
            SessionStore.Unseal.Retry -> {
                // A passing Keystore hiccup: keep everything, just ask to retry.
                addNotice("Couldn't unlock your saved sign-in for ${server.name}. Try again in a moment.")
                return false
            }
        }
        _current.value?.let { teardown(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + NeverCrash)
        val client = clientFor(server.url, token)
        val socket = MattermostSocket(client, c.baseHttp, scope)
        // What the server last required, as saved: [server] may be a copy from before that changed
        // (a need saved as gone stays gone).
        val saved = c.sessionStore.servers.value.firstOrNull { it.id == server.id }
        val remembered = saved?.policy ?: server.policy
        val repo = ChatRepository(
            server.copy(policy = remembered, appNeed = if (saved != null) saved.appNeed else server.appNeed), client, socket, c.settings, scope, c.chatCache(server.id), c.nameColorStore(server.id),
            rememberPolicy = { policy -> c.sessionStore.rememberPolicy(server.id, policy) },
            rememberAppNeed = { need -> c.sessionStore.rememberAppNeed(server.id, need) },
            unlockDue = { ceo.villa.matty.ui.lock.AppLock.book.isLocked(server.id, ceo.villa.matty.ui.lock.AppLock.now()) },
        )
        client.onUnauthorized = { c.appScope.launch { confirmSessionEnded(repo) } }
        repo.appInForeground = foreground
        c.sessionStore.setActive(server.id)
        ended.value = null
        _current.value = repo
        repo.start()
        c.plugins.attach(repo)
        c.notifier.attach(repo)
        ConnectionService.sync(c.app, c.settings.value.backgroundConnection)
        return true
    }

    private fun teardown(repo: ChatRepository) {
        c.plugins.detachAll()
        repo.stop()
    }

    /** Persist a fresh sign-in and make it the active server. */
    fun signedIn(url: String, siteName: String, user: User, token: String) {
        val clean = url.trimEnd('/')
        // Signing in again to the same account: end the old session on the server first, or
        // every re-login would leave another valid token behind.
        c.sessionStore.servers.value.filter { it.userId == user.id && it.url.equals(clean, ignoreCase = true) }.forEach { old ->
            revokeLater(old.url, old.sealedToken)
        }
        val server = SavedServer(
            id = UUID.randomUUID().toString(), url = clean, name = siteName,
            userId = user.id, username = user.username, sealedToken = c.sessionStore.seal(token),
        )
        c.sessionStore.upsert(server)
        // The old session's notifications go with it: their taps and actions would lead nowhere.
        c.notifier.clearDropped(c.sessionStore.servers.value.mapTo(HashSet()) { it.id })
        // So does any "Signed out" notice for this address.
        c.notifier.signedIn(clean)
        notice.value = null  // whatever sent the user to sign in has been dealt with
        activate(server)
        c.push.serverAdded(server.id)
        c.appScope.launch { runCatching { clientFor(clean, token).attachDevice("") } }
    }

    /** Sign out of the active server: wipe this phone immediately, then end the session on the server. */
    fun signOut() {
        val repo = _current.value ?: return
        val server = repo.server
        teardown(repo)
        _current.value = null
        c.sessionStore.remove(server.id)
        c.wipeSessionData()
        revokeLater(server.url, server.sealedToken, tell = true)
        activateOrStop(c.sessionStore.active)
    }

    /** Ends a session on its server; if the server can't be reached, keeps trying on later launches. */
    private fun revokeLater(url: String, sealedToken: String, tell: Boolean = false) {
        if (!TransportPolicy.isAllowed(url)) return
        c.sessionStore.enqueueRevoke(url, sealedToken)
        val token = c.sessionStore.unseal(sealedToken) ?: return  // stays queued; retried later
        c.appScope.launch {
            val client = revokeClient(url, token)
            // Its push registration on the server first, while the token still works (best effort).
            c.push.forgetAtServer(client)
            val done = runCatchingCancellable { client.logout() }.getOrDefault(false)
            if (done) c.sessionStore.dequeueRevoke(SessionStore.PendingRevoke(url, sealedToken))
            else if (tell) addNotice("Signed out on this phone. Matty couldn't reach the server to end the session there and will keep trying.")
        }
    }

    private fun retryRevocations() {
        c.sessionStore.pendingRevocations().forEach { item ->
            if (!TransportPolicy.isAllowed(item.url)) { c.sessionStore.dequeueRevoke(item); return@forEach }
            val token = when (val u = c.sessionStore.unlock(item.sealedToken)) {
                is SessionStore.Unseal.Ok -> u.token
                SessionStore.Unseal.Gone -> { c.sessionStore.dequeueRevoke(item); return@forEach }
                SessionStore.Unseal.Retry -> return@forEach
            }
            c.appScope.launch {
                val done = runCatchingCancellable { revokeClient(item.url, token).logout() }.getOrDefault(false)
                if (done) c.sessionStore.dequeueRevoke(item)
            }
        }
    }

    private var confirming = false

    /**
     * The server answered 401. Confirm with one /users/me (a stray 401 shouldn't sign anyone
     * out), then treat the session as over: wipe it here and ask the user to sign in again.
     */
    private suspend fun confirmSessionEnded(repo: ChatRepository) {
        if (confirming || _current.value !== repo) return
        confirming = true
        val dead = try {
            repo.client.me(); false
        } catch (e: ApiException) {
            e.status == 401
        } catch (e: Exception) {
            false
        }
        confirming = false
        if (!dead || _current.value !== repo) return
        val server = repo.server
        teardown(repo)
        _current.value = null
        c.sessionStore.remove(server.id)
        c.wipeSessionData()
        ended.value = server
        addNotice("Your session on ${server.name} ended. Sign in again to keep chatting.")
        activateOrStop(c.sessionStore.active)
    }

    fun switchTo(serverId: String) {
        if (_current.value?.server?.id == serverId || serverId in endedIds()) return
        c.sessionStore.servers.value.firstOrNull { it.id == serverId }?.let { activate(it) }
    }

    fun onForeground(fg: Boolean) {
        foreground = fg
        if (fg) retryRevocations()
        // What push owes a server is paid every time the app comes back, not only at its start.
        if (fg) c.push.cameToFront()
        val repo = _current.value ?: return
        repo.appInForeground = fg
        if (fg) {
            repo.socket.kick()
            // Not when the server's lock is coming up over it: nobody has read it yet.
            repo.readingChannel?.let { repo.markViewed(it) }
            repo.launchSafe { repo.refreshTeamUnreads() }
        }
        c.plugins.onForeground(fg)
    }

    fun rememberLocation(teamId: String?, channelId: String?) {
        val repo = _current.value ?: return
        c.sessionStore.rememberLocation(repo.server.id, teamId.orEmpty(), channelId.orEmpty())
    }

    companion object {
        private const val KEY_ENDED = "ids"

        /**
         * Whether a server's ending wipes what every server shares (drafts, uploads, the pending
         * share, caches, cookies) now: at the start of the process ([atStart]), or when no other
         * server is live ([otherLive]). Otherwise only what is the ended server's own goes now.
         */
        fun wipesAll(atStart: Boolean, otherLive: Boolean): Boolean = atStart || !otherLive

        /** The server the app starts with: [wanted] when it is saved, else the active one; never one whose session [ended]. */
        fun chooseStart(saved: List<SavedServer>, active: SavedServer?, wanted: String?, ended: Set<String> = emptySet()): SavedServer? =
            wanted?.takeIf { it !in ended }?.let { id -> saved.firstOrNull { it.id == id } } ?: active?.takeIf { it.id !in ended }
    }
}
