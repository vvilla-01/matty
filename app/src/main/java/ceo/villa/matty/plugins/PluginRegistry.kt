package ceo.villa.matty.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ceo.villa.matty.AppContainer
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.plugins.villanet.VillaNetPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a plugin's UI gets to work with. */
interface PluginHost {
    val repo: ChatRepository
    fun openPanel(pluginId: String, args: Map<String, String> = emptyMap())
    fun openUrl(url: String)
    fun openChannel(channelId: String)
}

/**
 * A full-screen surface a plugin contributes (the Matty plugin's panel, for one). [title] is what
 * it is called when the module has nothing better: see [MattyPlugin.panelTitle].
 */
class PluginPanel(
    val title: String,
    val icon: ImageVector,
    val content: @Composable (host: PluginHost, args: Map<String, String>, onClose: () -> Unit) -> Unit,
)

/** One line of [MattyPlugin.checks]; [warning] when it says something is not as it should be. */
data class PluginCheck(val text: String, val warning: Boolean)

/**
 * A native Matty integration for a Mattermost server plugin.
 *
 * Mattermost's own mobile app cannot run web plugins, so plugin features simply disappear on
 * phones. Matty's answer is native modules: each one declares the server plugin id it speaks
 * for, and is switched on automatically when that plugin is active on the server. Everything
 * here is optional — implement only what the plugin needs. See docs/PLUGINS.md.
 */
interface MattyPlugin {
    /** The Mattermost plugin id, e.g. "ceo.villa.matty". */
    val id: String
    /** The module's name before it knows better: see [displayName]. */
    val name: String
    val description: String

    /**
     * Ids the server plugin had before, that links posted then name (`/plug/<id>/...`): they
     * open in this module as its own do.
     */
    val formerIds: Set<String> get() = emptySet()

    /**
     * What a person sees the module called (the Plugins screen), which may change while it runs:
     * the Matty plugin says what the server calls it. [name] when it can't change.
     */
    @Composable
    fun displayName(): String = name

    /** What the panel's button in the channel header is called; [PluginPanel.title] when it can't change. */
    @Composable
    fun panelTitle(): String = panel?.title.orEmpty()

    /** Custom post types (post.type) this module draws natively. */
    val postTypes: Set<String> get() = emptySet()

    /** Native rendering for one of [postTypes]. */
    @Composable
    fun PostContent(post: Post, host: PluginHost) {}

    /** A panel shown from the channel header, the plugin hub and deep links. */
    val panel: PluginPanel? get() = null

    /** A count shown in red on the panel's button in the channel header (0 shows none). */
    @Composable
    fun panelBadge(): Int = 0

    /**
     * A standing line about the module itself, for the Plugins screen: something the reader
     * should know before expecting it to work (VillaNET: the server's plugin is older than this
     * app works with). Null when all is well.
     */
    @Composable
    fun notice(): String? = null

    /**
     * What the module found when it checked the server's plugin, or the app against it, one line
     * each: where the plugin is listed, and in Settings (the Matty plugin: whether its release is
     * signed, and whether the server verified this app). Empty when there is nothing to say.
     */
    @Composable
    fun checks(): List<PluginCheck> = emptyList()

    /** Called when a server with this plugin becomes active. */
    fun onAttach(repo: ChatRepository) {}
    fun onDetach() {}

    /** WebSocket events the plugin published, already filtered to `custom_<id>_*`. */
    fun onEvent(name: String, event: WsEvent) {}

    fun onForeground(foreground: Boolean) {}

    /**
     * What this module knows about a person right now, for everywhere a person is shown: on the
     * air, in voice, what they're doing, whether their custom status is the plugin's own. Null
     * when it has nothing to say. See [PersonFacts]; read it through [personOf].
     *
     * It is asked for every avatar and every member row, so a module should make it one lookup
     * that only changes when this person's facts do (VillaNET keeps a map by user id and
     * subscribes each caller to its one entry).
     */
    @Composable
    fun person(userId: String): PersonFacts? = null

    /**
     * Drawn over the coloured band across the top of a user's profile sheet, filling [modifier]'s
     * bounds, which are three wide by one high on every screen (VillaNET: the person's banner, the
     * part of it they chose, and on your own a way to change it). The band shows wherever this
     * draws nothing.
     */
    @Composable
    fun ProfileBanner(userId: String, host: PluginHost, modifier: Modifier) {}

    /**
     * What a module keeps for as long as a user's profile sheet is open, composed outside the
     * sheet, after it (VillaNET: the crop screen for one's banner, a window of its own over the
     * sheet). Not inside it: Material builds the sheet's window, and all composed in it, afresh
     * when the screen's density changes, and what was held there went.
     */
    @Composable
    fun ProfileOver(userId: String, host: PluginHost) {}

    /** Extra rows on a user's profile sheet (e.g. "Live now — Watch"). */
    @Composable
    fun ProfileExtras(userId: String, host: PluginHost) {}

    /**
     * Floating UI the module shows over whatever is on screen, without leaving it (VillaNET pops
     * a clip out over the chat it was shared in). Drawn above the app while a server with the
     * plugin is signed in.
     */
    @Composable
    fun Overlay(host: PluginHost) {}

    /**
     * Rows the module adds to a team's channel list, after its categories and before "Browse or
     * create channels" (VillaNET: the voice channels and who is in them). Called in the list's
     * composition, so the module collects what it needs here; the returned rows are added to the
     * list as they are, their keys starting with the module's [id]. Null adds nothing.
     */
    @Composable
    fun sidebarSection(host: PluginHost, teamId: String): (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null

    /**
     * Whether the module has something going on you'd want to control from anywhere (VillaNET: a
     * stream playing with its panel closed). While it does, Matty floats [MiniBar] just above the
     * bottom pills of the home screen and of threads.
     */
    @Composable
    fun hasMiniBar(): Boolean = false

    /** That control: one pill about 56dp tall, as wide as [modifier] allows; it handles its own taps. */
    @Composable
    fun MiniBar(host: PluginHost, modifier: Modifier) {}

    /** Return a reason to stop a message from being sent (e.g. a secret pasted by mistake). */
    fun checkOutgoing(text: String): String? = null

    /** Handle an in-app link such as `/plug/<id>/...`; return panel args to open, or null. */
    fun panelArgsForLink(path: String): Map<String, String>? = null
}

/** Someone in voice chat: talking freely, with their mic muted, or deafened (hearing nothing, and muted too). */
enum class VoiceState { Open, Muted, Deafened }

/**
 * What someone is doing right now, shown Discord-style under their name: "Playing ELDEN RING", or
 * "Streaming ELDEN RING" when their live stream is of that very game. [title] is empty when the
 * source knows they're in a game but not which; [source] is where it comes from ("Steam").
 */
data class UserActivity(val kind: Kind, val title: String, val source: String) {
    enum class Kind { Playing, Streaming }
}

/**
 * What plugins know about one person, for everywhere that person is shown.
 *
 * @property live On the air right now: a LIVE ring on their avatar everywhere.
 * @property voice In voice chat: a mic on their avatar (crossed headphones when deafened). Null when they aren't.
 * @property activity What they're doing, Discord-style under their name ("Playing ELDEN RING").
 * @property holdsStatus The custom status beside their name is the plugin's doing rather than
 *   their own words (VillaNET borrows it for its live badge while they broadcast). Lines that say
 *   what somebody is up to leave such a status out: it only repeats the LIVE ring.
 */
data class PersonFacts(
    val live: Boolean = false,
    val voice: VoiceState? = null,
    val activity: UserActivity? = null,
    val holdsStatus: Boolean = false,
) {
    /** Two modules' facts about one person, as one. */
    operator fun plus(o: PersonFacts) = PersonFacts(live || o.live, voice ?: o.voice, activity ?: o.activity, holdsStatus || o.holdsStatus)

    companion object {
        /** Nothing to say. */
        val None = PersonFacts()
    }
}

/** What the active plugins say about [userId] right now ([PersonFacts.None] when nothing, or nobody). */
@Composable
fun personOf(userId: String?): PersonFacts {
    if (userId.isNullOrEmpty()) return PersonFacts.None
    val plugins by androidx.compose.ui.platform.LocalContext.current.container.plugins.active.collectAsState()
    var facts = PersonFacts.None
    // Every plugin asked, each under its own key, so the calls line up the same way every time.
    plugins.forEach { p -> androidx.compose.runtime.key(p.id) { p.person(userId)?.let { facts += it } } }
    return facts
}

class PluginRegistry(private val c: AppContainer) {
    /** Built-in native modules. Add new ones here. */
    val builtIn: List<MattyPlugin> = listOf(
        VillaNetPlugin(c),
    )

    private val _active = MutableStateFlow<List<MattyPlugin>>(emptyList())
    val active: StateFlow<List<MattyPlugin>> = _active

    private var jobs = listOf<Job>()

    fun attach(repo: ChatRepository) {
        jobs.forEach { it.cancel() }
        _active.value.forEach { it.onDetach() }
        _active.value = emptyList()
        jobs = listOf(
            repo.scope.launch {
                repo.webappPlugins.collect { server ->
                    val ids = server.map { it.id }.toSet()
                    val next = builtIn.filter { it.id in ids }
                    val prev = _active.value
                    (prev - next.toSet()).forEach { it.onDetach() }
                    (next - prev.toSet()).forEach { it.onAttach(repo) }
                    _active.value = next
                }
            },
            repo.scope.launch {
                repo.events.collect { e ->
                    if (!e.event.startsWith("custom_")) return@collect
                    for (p in _active.value) {
                        val prefix = "custom_${p.id}_"
                        if (e.event.startsWith(prefix)) p.onEvent(e.event.removePrefix(prefix), e)
                    }
                }
            },
        )
    }

    /** Signing out or switching servers: plugins drop their client, token and playback. */
    fun detachAll() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _active.value.forEach { it.onDetach() }
        _active.value = emptyList()
    }

    fun onForeground(fg: Boolean) = _active.value.forEach { it.onForeground(fg) }

    fun forPostType(type: String): MattyPlugin? = _active.value.firstOrNull { type in it.postTypes }

    /** The module for a plugin this server is running; null when it isn't (a module is nothing without its plugin). */
    fun byId(id: String): MattyPlugin? = _active.value.firstOrNull { it.id == id }

    fun checkOutgoing(text: String): String? = _active.value.firstNotNullOfOrNull { it.checkOutgoing(text) }

    /** Resolve `{site}/plug/<pluginId>/<rest>` links into a plugin panel. */
    fun resolveLink(url: String, siteUrl: String): Pair<MattyPlugin, Map<String, String>>? = linkTarget(_active.value, url, siteUrl)
}

/**
 * Which of [modules] a `{site}/plug/<pluginId>/<rest>` link opens, and with what: the module whose
 * plugin id the link names, now or before ([MattyPlugin.formerIds]). Null for any other link.
 */
internal fun linkTarget(modules: List<MattyPlugin>, url: String, siteUrl: String): Pair<MattyPlugin, Map<String, String>>? {
    val path = url.removePrefix(siteUrl.trimEnd('/')).takeIf { it != url || url.startsWith("/") } ?: return null
    if (!path.startsWith("/plug/")) return null
    val rest = path.removePrefix("/plug/")
    val pid = rest.substringBefore('/')
    val p = modules.firstOrNull { it.id == pid } ?: modules.firstOrNull { pid in it.formerIds } ?: return null
    val args = p.panelArgsForLink(rest.removePrefix(pid)) ?: return null
    return p to args
}
