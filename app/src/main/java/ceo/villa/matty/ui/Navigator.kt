package ceo.villa.matty.ui

import ceo.villa.matty.ui.chat.asOpened
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Stable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import ceo.villa.matty.AppContainer
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.plugins.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One photo or video in the media viewer. [fileId] marks a Mattermost attachment (it can be saved). */
data class MediaItem(
    val url: String,
    val video: Boolean,
    val title: String,
    val fileId: String? = null,
    val mime: String = "",
    /** A still for a video that isn't the one on screen: anything the image loader shows. */
    val poster: Any? = null,
)

sealed interface Route {
    data class Thread(val rootId: String) : Route
    data class Search(val initial: String = "") : Route
    data object Settings : Route
    data object Plugins : Route
    data object Licences : Route
    data class Plugin(val id: String, val args: Map<String, String> = emptyMap()) : Route
    /** Full-screen photos and videos: every one in the post, opened at [start], swiped through. */
    data class Media(val items: List<MediaItem>, val start: Int = 0) : Route
    data class ChannelInfo(val channelId: String) : Route
    data class Pinned(val channelId: String) : Route
    data object NewMessage : Route
    data object BrowseChannels : Route
    data object EditProfile : Route
    data object AddServer : Route
}

/**
 * The tab pill's screens. Messages has no screen of its own: it is Home with the Direct Messages
 * space picked in the rail (see [Navigator.showDirectMessages]).
 */
enum class HomeTab { Home, Activity, You }

/**
 * The rail's two buttons below the teams, whose screens open in the main area, in the place of
 * the chat and the member panel: the side bar beside it stays as it was, and in use.
 */
enum class RailPage { Discover, Plugins }

/**
 * Which rail pages are open in the main area, the one shown last. Back goes to the page shown
 * before, then to the chat, whose team (or direct messages) stays selected underneath all along.
 */
@Stable
class RailPages {
    private val shown = mutableStateListOf<RailPage>()
    val current: RailPage? get() = shown.lastOrNull()

    fun open(page: RailPage) {
        if (current == page) return
        shown.remove(page)
        shown.add(page)
    }

    /** Back: true when there was a page to close. */
    fun back(): Boolean = if (shown.isEmpty()) false else { shown.removeAt(shown.lastIndex); true }

    fun clear() = shown.clear()

    companion object {
        /** Kept with the activity's saved state, so a page comes back when the process does, as the chat does. */
        val Saver: Saver<RailPages, Any> = listSaver(
            save = { pages -> pages.shown.map { it.name } },
            restore = { names -> RailPages().apply { names.forEach { n -> RailPage.entries.firstOrNull { it.name == n }?.let(::open) } } },
        )
    }
}

/** The conversation the main area shows: none while Discover or Plugins has its place. */
fun channelInMain(channelId: String?, page: RailPage?): String? = if (page == null) channelId else null

/**
 * The rail button lit for [teamId] (null: direct messages) with [page] open in the main area:
 * the page's own button while one is open, else the team's.
 */
fun railSelection(teamId: String?, page: RailPage?): String = when (page) {
    RailPage.Discover -> RailDiscover
    RailPage.Plugins -> RailPlugins
    null -> teamId ?: RailDirect
}

const val RailDirect = "rail:dm"
const val RailDiscover = "rail:discover"
const val RailPlugins = "rail:plugins"

/** App navigation + every action a message can trigger. One per signed-in session. */
@Stable
class Navigator(
    private val context: Context,
    private val c: AppContainer,
    override val repo: ChatRepository,
    private val scope: CoroutineScope,
    /** Discover or Plugins, open in the main area in place of the chat and the members. */
    val railPages: RailPages = RailPages(),
) : ChatActions, PluginHost {
    val stack = mutableStateListOf<Route>()
    var tab by mutableStateOf(HomeTab.Home)
    /** Selected channel per team id ("" = Home / DMs). */
    var channelId by mutableStateOf<String?>(repo.server.lastChannelId.ifEmpty { null })
    var profileUserId by mutableStateOf<String?>(null)
    /** Bumped to ask the panels to slide the chat into view. */
    var showChatSignal by mutableStateOf(0)
    /** The conversation in the main area, marked as open in the channel list: none under a rail page. */
    val openChannelId: String? get() = channelInMain(channelId, railPages.current)

    /** A team (null: direct messages) picked in the rail: its list, and the chat back in the main area. */
    fun selectSpace(teamId: String?) {
        railPages.clear()
        repo.selectTeam(teamId)
    }

    /**
     * Discover or Plugins from the rail: shown in the main area, which on a phone slides into view
     * as the chat does when a channel is picked (pressed again while it shows, it just slides there).
     */
    fun openRailPage(page: RailPage) {
        tab = HomeTab.Home
        railPages.open(page)
        showChatSignal++
    }

    /** The Messages tab: the Direct Messages space (the home screen puts its list in view). */
    fun showDirectMessages() {
        tab = HomeTab.Home
        selectSpace(null)
    }

    /** The Home tab: back to the first server in the rail (the home screen puts its list in view). */
    fun showHome() {
        tab = HomeTab.Home
        railPages.clear()
        repo.teams.value.firstOrNull()?.let { repo.selectTeam(it.id) }
    }

    val top: Route? get() = stack.lastOrNull()

    /** A second tap while a screen is opening doesn't stack another copy of it. */
    fun push(r: Route) { if (stack.lastOrNull() != r) stack.add(r) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false

    fun selectChannel(id: String, reveal: Boolean = true) {
        // Something shared from another app goes into the conversation picked next, even the open one.
        if (ShareInbox.pending) ShareInbox.target = id
        channelId = id
        val ch = repo.channels.value[id]
        if (ch != null && ch.teamId.isNotEmpty() && ch.teamId != repo.currentTeamId.value) repo.selectTeam(ch.teamId)
        c.sessions.rememberLocation(repo.currentTeamId.value, id)
        if (reveal) {
            tab = HomeTab.Home
            // Opened from Discover (a channel joined), a notification or a link: the main area
            // shows the conversation, not the page that had its place.
            railPages.clear()
            showChatSignal++
        }
    }

    // ---- ChatActions ---------------------------------------------------------------------

    override fun openUser(userId: String) {
        repo.ensureUsers(listOf(userId))
        profileUserId = userId
    }

    override fun openUsername(username: String) {
        val known = repo.users.value.values.firstOrNull { it.username.equals(username, true) }
        if (known != null) { openUser(known.id); return }
        scope.launch {
            ceo.villa.matty.data.runCatchingCancellable { repo.client.userByUsername(username) }.onSuccess { u ->
                repo.users.value = repo.users.value + (u.id to u)
                profileUserId = u.id
            }.onFailure { repo.emitNotice("No one called @$username here") }
        }
    }

    override fun openChannel(channelId: String) {
        stack.clear()
        selectChannel(channelId)
    }

    override fun openChannelByName(name: String) {
        val team = repo.currentTeamId.value
        val ch = repo.channels.value.values.firstOrNull { it.name == name && (team == null || it.teamId == team) }
            ?: repo.channels.value.values.firstOrNull { it.name == name }
        if (ch != null) openChannel(ch.id) else repo.emitNotice("You're not in ~$name")
    }

    override fun openThread(rootId: String) {
        if (ShareInbox.pending) ShareInbox.target = "thread:$rootId"
        push(Route.Thread(rootId))
    }

    /** A link waiting for the person to say it may open: where it goes, and the host it really reaches. */
    var pendingLink by mutableStateOf<Pair<String, String>?>(null)

    /** A link held down: shown in full, to copy or open. */
    var heldLink by mutableStateOf<String?>(null)

    override fun confirmUrl(url: String, host: String) { pendingLink = url to host }

    override fun showLink(url: String) { heldLink = url }

    override fun openUrl(url: String) {
        // Server-relative links ("/team/pl/…", "/plug/…") are links into this server.
        val target = url.trim().let { if (it.startsWith("/") && !it.startsWith("//")) repo.client.baseUrl + it else it }
        // Links into this server open natively where we can (exact origin only).
        val site = repo.client.baseUrl
        if (repo.client.isSameOrigin(target)) c.plugins.resolveLink(target, site)?.let { (p, args) -> push(Route.Plugin(p.id, args)); return }
        if (repo.client.isSameOrigin(target)) {
            val path = target.toHttpUrlOrNull()?.encodedPath?.removePrefix(repo.client.origin.encodedPath.trimEnd('/')).orEmpty()
            Regex("^/[^/]+/pl/([a-z0-9]{26})").find(path)?.let { m ->
                scope.launch {
                    ceo.villa.matty.data.runCatchingCancellable { repo.client.post(m.groupValues[1]) }.onSuccess { p ->
                        if (p.rootId.isNotEmpty()) openThread(p.rootId) else { openChannel(p.channelId); openThread(p.id) }
                    }
                }
                return
            }
            Regex("^/[^/]+/channels/([a-z0-9_\\-]+)").find(path)?.let { m -> openChannelByName(m.groupValues[1]); return }
            Regex("^/[^/]+/messages/@([a-zA-Z0-9._\\-]+)").find(path)?.let { m -> openUsername(m.groupValues[1]); return }
        }
        // One with no scheme is https: the same reading a link's words are checked against (LinkHosts.kt).
        val uri = runCatching { Uri.parse(asOpened(target)) }.getOrNull() ?: return
        // Message text, attachments and link previews are written by other people: only web
        // and mail links leave the app. intent:, file:, content:, javascript: and app schemes don't.
        when (uri.scheme?.lowercase()) {
            "https", "http" -> runCatching {
                CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF000000.toInt()).build())
                    .setShowTitle(true).build()
                    .apply { intent.addCategory(Intent.CATEGORY_BROWSABLE) }
                    .launchUrl(context, uri)
            }.onFailure { browse(uri) }
            "mailto", "tel" -> browse(uri)
            else -> repo.emitNotice("Matty doesn't open ${uri.scheme}: links")
        }
    }

    private fun browse(uri: Uri) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { repo.emitNotice("No app can open that link") }
    }

    override fun openMedia(url: String, isVideo: Boolean, title: String) = push(Route.Media(listOf(MediaItem(url, isVideo, title))))

    override fun openGallery(files: List<FileInfo>, index: Int) {
        val media = files.filter { it.isImage || it.isVideo }
        if (media.isEmpty()) return
        val at = media.indexOf(files.getOrNull(index)).coerceAtLeast(0)
        push(Route.Media(media.map { f ->
            MediaItem(
                repo.client.fileUrl(f.id), f.isVideo, f.name, fileId = f.id, mime = f.mimeType,
                poster = when {
                    !f.isVideo -> null
                    f.hasPreviewImage -> repo.client.filePreviewUrl(f.id)
                    else -> ceo.villa.matty.data.net.VideoStill(f.id, repo.client.fileUrl(f.id))
                },
            )
        }, at))
    }

    fun openAttachment(file: FileInfo) = openGallery(listOf(file), 0)

    /** A non-media file waiting for the user to confirm saving it to Downloads. */
    var pendingDownload by mutableStateOf<FileInfo?>(null)

    override fun openFile(file: FileInfo) {
        if (file.isImage || file.isVideo) openAttachment(file) else pendingDownload = file
    }

    /** Saves a Mattermost attachment to Downloads/Matty, on an explicit tap. */
    fun downloadAttachment(fileId: String, name: String, mime: String) {
        val safe = ceo.villa.matty.data.net.Downloader.safeName(name)
        repo.emitNotice("Downloading $safe…")
        scope.launch {
            ceo.villa.matty.data.runCatchingCancellable { c.downloader.saveAttachment(repo.client, fileId, name, mime) }
                .onSuccess { repo.emitNotice("Saved $safe to Downloads/Matty") }
                .onFailure { repo.emitNotice(repo.failureText("Couldn't download $safe", it)) }
        }
    }

    override fun search(terms: String) = push(Route.Search(terms))

    override fun openPlugin(pluginId: String, args: Map<String, String>) = push(Route.Plugin(pluginId, args))

    override fun messageUser(userId: String) {
        scope.launch {
            ceo.villa.matty.data.runCatchingCancellable { repo.openDirect(userId) }
                .onSuccess { ch -> profileUserId = null; repo.selectTeam(repo.currentTeamId.value); openChannel(ch.id) }
                .onFailure { repo.emitNotice(repo.failureText("Couldn't open the conversation", it)) }
        }
    }

    // ---- PluginHost ----------------------------------------------------------------------

    override fun openPanel(pluginId: String, args: Map<String, String>) = openPlugin(pluginId, args)
}
