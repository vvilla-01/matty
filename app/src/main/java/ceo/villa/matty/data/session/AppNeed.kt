package ceo.villa.matty.data.session

import ceo.villa.matty.plugins.villanet.olderThan
import ceo.villa.matty.plugins.villanet.releaseVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A newer Matty than this one that a server requires (Matty Plugin's minimum app version). While
 * there is one, the server is closed behind the update page. It is kept with the saved server, so
 * the next start shows that page before the server has been asked again.
 */
@Serializable
data class AppNeed(
    /** The oldest version the server takes, `N.N.N`; "" when its refusal did not say which. */
    val minimum: String = "",
    /** This app's version when it was heard: an app updated since is not held to a need without a [minimum]. */
    val heardBy: String = "",
    /** The server plugin that said it: once that plugin is no longer on the server, nothing is required. */
    val plugin: String = "",
)

/**
 * A minimum as the server gives it, read by the plugin's own rule for a version ([releaseVersion]:
 * three numbers of up to nine digits, none with a leading zero); anything else (blank included)
 * is none.
 */
fun minimumOf(word: String?): String? = word?.trim()?.takeIf { releaseVersion(it) }

/**
 * The minimum a refusal `app_outdated` names in its sentence ("This server needs Matty 0.1.32 or
 * newer. You have 0.1.31."), or null when it names none.
 */
fun minimumInRefusal(sentence: String?): String? =
    sentence?.let { REFUSAL.find(it)?.groupValues?.get(1) }

private const val PART = "(?:0|[1-9][0-9]{0,8})"
private val REFUSAL = Regex("Matty ($PART\\.$PART\\.$PART) or newer")

/**
 * Whether [need] closes the server to this app at version [own] (`BuildConfig.VERSION_NAME`, a
 * debug build's "-debug" ending left out, as the server leaves it out): a minimum newer than
 * [own], or a refusal that named none, heard by this very version.
 */
fun closesTo(need: AppNeed?, own: String): Boolean {
    if (need == null) return false
    val minimum = minimumOf(need.minimum)
    return if (minimum != null) olderThan(own, minimum) else need.heardBy == own
}

/**
 * The need last heard from one server, and the rule for changing it. A refusal `app_outdated` is
 * believed at once. So is `GET /access` naming a minimum newer than this app, or one the app
 * cannot read, unless an answer asked after it has already taken this version. Only the answer to
 * an `/access` read that succeeded, and that was asked after the last refusal, may take a need
 * away; so does a plugin list without the plugin that said it. A read that fails changes nothing.
 * What is held is always a need that closes the server, or null.
 */
class AppNeedKeeper(remembered: AppNeed?, private val own: String, private val remember: (AppNeed?) -> Unit = {}) {
    private val _need = MutableStateFlow(remembered?.takeIf { closesTo(it, own) })
    /** What closes the server; null while nothing does. */
    val need: StateFlow<AppNeed?> = _need

    /** How many `/access` reads were started; each one's ticket is its number. */
    private var asked = 0L
    /** Reads up to this ticket were asked before something they cannot know of (a refusal). */
    private var settled = 0L
    /** The ticket of the last answer that took this version: an answer asked before it cannot close the server. */
    private var opened = 0L

    init {
        // An app updated since the need was kept: it goes now, not only once the server is asked.
        if (remembered != null && _need.value == null) remember(null)
    }

    /** An `/access` read is about to start: the ticket to hand back with its answer. */
    @Synchronized
    fun asking(): Long = ++asked

    /**
     * The plugin [plugin] answered the read with [ticket], naming [minimum] (null or blank when it
     * named none, as before Matty Plugin 4.4.0). A minimum the app cannot read still says that one
     * is set: it closes the server as a need that names none.
     */
    @Synchronized
    fun answered(ticket: Long, minimum: String?, plugin: String) {
        val said = minimum?.trim().orEmpty()
        val m = minimumOf(said)
        if (said.isNotEmpty() && (m == null || olderThan(own, m))) {
            if (ticket <= opened) return
            if (ticket > settled) settled = ticket
            set(AppNeed(m.orEmpty(), own, plugin))
        } else if (ticket > settled) {
            settled = ticket
            opened = ticket
            set(null)
        }
    }

    /**
     * A request to [plugin] was refused `app_outdated` with [sentence]. The minimum is the one it
     * names, else the one already held; without either (or with one this app is not older than,
     * which the server reads otherwise) it is a need that names none. Reads asked before it can no
     * longer take it away.
     */
    @Synchronized
    fun refused(sentence: String?, plugin: String) {
        val m = (minimumInRefusal(sentence) ?: _need.value?.minimum)?.let { minimumOf(it) }?.takeIf { olderThan(own, it) }
        settled = asked
        set(AppNeed(m.orEmpty(), own, plugin))
    }

    /** The server's plugin list was read: a need said by a plugin that is not in [ids] goes. */
    @Synchronized
    fun listed(ids: Set<String>) {
        val held = _need.value ?: return
        if (held.plugin !in ids) set(null)
    }

    private fun set(n: AppNeed?) {
        if (n == _need.value) return
        _need.value = n
        remember(n)
    }
}

/** How far apart the update page's reads of `GET /access` are at least, its own or tapped. */
const val APP_CHECK_GAP_MS = 5_000L

/**
 * Reads kept at least [gapMs] apart by [clock] (ms, only compared with itself), so that a server
 * that contradicts itself (it refuses this version, then its `/access` takes it) cannot drive the
 * update page into a tight loop. Any thread.
 */
class ReadPace(private val gapMs: Long, private val clock: () -> Long) {
    private var next: Long? = null

    /** How long the read about to be made waits first (0 for at once); the read is booked for then. */
    @Synchronized
    fun claim(): Long {
        val now = clock()
        val at = maxOf(now, next ?: now)
        next = at + gapMs
        return at - now
    }
}
