package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.RequestDeadline
import ceo.villa.matty.data.api.ServerError
import ceo.villa.matty.plugins.PluginInstall
import ceo.villa.matty.plugins.PluginUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

/** Where the plugin's releases are. */
internal const val PLUGIN_RELEASES = "https://github.com/vvilla-01/matty-plugin/"

/** The first version of the plugin whose `/access` can offer a system admin the install. */
const val INSTALL_SINCE = "4.4.0"

/** The most of a bundle the app takes, as the plugin's own limit for one. */
internal const val BUNDLE_MAX_BYTES = 64L * 1024 * 1024

/** How long the app waits for the new version to answer after Mattermost took the bundle, and how often it asks. */
internal const val INSTALL_WAIT_MS = 60_000L
internal const val INSTALL_ASK_MS = 2_000L

/** The header in which the plugin names the version of the bundle it answered with. */
internal const val BUNDLE_VERSION_HEADER = "X-Matty-Update-Version"

/** The app's own folder for a bundle on its way to Mattermost, in the app's [cacheDir]. */
internal fun pluginUpdateDir(cacheDir: File): File = File(cacheDir, "plugin-update")

/**
 * Removes every bundle in [pluginUpdateDir]: one left by an app that was stopped in the middle of
 * an install. Called when the app starts, when no install can be running, and by a sign-out's
 * wipe of the disk caches.
 */
fun removeLeftUpdates(cacheDir: File) {
    pluginUpdateDir(cacheDir).listFiles()?.forEach { it.deleteRecursively() }
}

/** The longest sentence from a server the app shows, in characters. */
internal const val SENTENCE_MAX = 300

/** A version as the plugin writes one: three numbers of up to nine digits, none with a leading zero. */
private val RELEASE_VERSION = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")

/** Whether [v] is a version as the plugin writes one. */
internal fun releaseVersion(v: String): Boolean = RELEASE_VERSION.matches(v)

/** The page of the plugin's release [version], built by the app: the only page the update line opens. */
internal fun pluginReleasePageOf(version: String): String = "${PLUGIN_RELEASES}releases/tag/v$version"

/** The name the app gives the bundle of [version] when it hands it to Mattermost. */
internal fun bundleNameOf(version: String): String = "$VILLANET_ID-$version.tar.gz"

/**
 * A newer release of the plugin, as `/access` tells a system admin of it: its [version], the page
 * a tap opens ([link]; null when the server named another one), and whether this admin may install
 * it from the app ([install]).
 */
internal data class UpdateOffer(val version: String, val link: String?, val install: Boolean)

/**
 * The Plugins screen's offer for a newer release ([word], from `/access`) of the plugin running
 * at [running]: for a system admin ([admin]) only, for a version newer than the running one. A
 * tap opens the release's page, which the app builds from the version, only when the server's
 * address is exactly that page. Install is offered only when the server says it can be, by a
 * plugin from [INSTALL_SINCE].
 */
internal fun pluginUpdateOf(word: VnUpdateWord?, running: String, admin: Boolean): UpdateOffer? {
    if (word == null || !admin) return null
    val version = word.version.trim()
    if (!releaseVersion(version) || !olderThan(running, version)) return null
    val page = pluginReleasePageOf(version)
    return UpdateOffer(version, page.takeIf { word.url == it }, word.install && !olderThan(running, INSTALL_SINCE))
}

/**
 * The marks that turn the direction of text (U+200E, U+200F, U+061C, U+202A to U+202E, U+2066 to
 * U+2069) and the byte order mark U+FEFF: a sentence from a server loses them without a space.
 */
private fun directionMark(ch: Char): Boolean =
    ch == '\u200E' || ch == '\u200F' || ch == '\u061C' || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\uFEFF'

/**
 * A sentence from a server, as the app shows it: one paragraph of at most [SENTENCE_MAX]
 * characters. The marks that turn the direction of text and U+FEFF are taken out; the joiners
 * U+200C and U+200D, which words and emoji need, stay; line breaks and every other control or
 * formatting character become one space. Null when nothing is left.
 */
internal fun oneParagraph(s: String?): String? {
    if (s == null) return null
    val flat = buildString {
        for (ch in s) {
            when {
                directionMark(ch) -> {}
                ch == '\u200C' || ch == '\u200D' -> append(ch)
                else -> when (ch.category) {
                    CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> append(' ')
                    else -> append(ch)
                }
            }
        }
    }.replace(Regex(" {2,}"), " ").trim()
    if (flat.isEmpty()) return null
    if (flat.length <= SENTENCE_MAX) return flat
    var end = SENTENCE_MAX - 1
    if (flat[end - 1].isHighSurrogate()) end--
    return flat.substring(0, end).trimEnd() + "…"
}

/** Where the install of [version] stands. */
sealed interface InstallState {
    data object Idle : InstallState
    /** The bundle is being fetched from the plugin. */
    data class Getting(val version: String) : InstallState
    /** The bundle is with Mattermost, and the app waits for the new version to answer. */
    data class Installing(val version: String) : InstallState
    data class Installed(val version: String) : InstallState
    /**
     * The install of [version] did not happen, for the reason [sentence] says. [newer] is the
     * version the plugin answered with instead, when it had a newer one ready.
     */
    data class Failed(val version: String, val sentence: String, val newer: String? = null) : InstallState
}

/** What the install asks of the app and of the signed-in server. */
internal interface InstallPort {
    /** The app's own folder for the bundle, in its cache folder: nothing else is kept there. */
    val dir: File
    /** The server's name, as the app shows it. */
    val server: String
    /** Leaves the app's own voice session on this server. */
    fun leaveVoice()
    /**
     * Asks the plugin for the checked bundle (`POST /admin/update/bundle`) and gives [read] the
     * answer's body while it is open, with the length it says (-1 when it does not) and the
     * version the plugin says it is ([BUNDLE_VERSION_HEADER]; null when it says none). A refusal
     * is thrown with the plugin's sentence ([ServerError]).
     */
    suspend fun fetchBundle(read: (InputStream, Long, String?) -> Unit)
    /** Hands [file] to Mattermost under [name] (`POST /api/v4/plugins`, over the one there). */
    suspend fun upload(file: File, name: String)
    /**
     * The plugin's version as `/access` says it now, read as every read of `/access` is (so the
     * Plugins screen's line follows it), within the caller's [RequestDeadline]; null when it did
     * not answer.
     */
    suspend fun runningVersion(): String?
    suspend fun pause(ms: Long)
    fun now(): Long
}

/**
 * A system admin's install of a newer release of the plugin, from the Plugins screen. One at a
 * time: the app leaves its own voice session, fetches the checked bundle from the plugin into a
 * file of its own, hands exactly that file to Mattermost when it is the version the admin was
 * asked about and arrived whole (at least one byte, and as many as the answer said), and waits for the new version to answer, each read of `/access` held to what is
 * left of the wait. Mattermost answers the upload only once the new version runs, so an upload
 * that gave no answer waits too, and only a refusal ends the run at once: one in Mattermost's
 * words, or a 4xx without words (a proxy's).
 * A refusal of the bundle by the plugin reads `/access` again, so that the line follows what the
 * server says now. The file is removed at the end whatever happens; one left in
 * [InstallPort.dir] by an app that was stopped in the middle is removed when the app next starts
 * ([removeLeftUpdates]) or when the next run starts.
 */
internal class PluginInstaller(
    private val port: InstallPort,
    private val scope: CoroutineScope,
    private val limit: Long = BUNDLE_MAX_BYTES,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * Forgets an install that is over, so that its end ("is installed.", or why it failed) is no
     * longer said: an end answers the tap that got it, and is said until the Plugins screen is
     * opened again. One that runs goes on.
     */
    fun forgetEnd() {
        if (!running && (_state.value is InstallState.Installed || _state.value is InstallState.Failed)) _state.value = InstallState.Idle
    }

    /** Starts the install of [offer]: nothing when it is not offered for install, or while one runs. */
    fun start(offer: UpdateOffer): Boolean {
        if (!offer.install || running) return false
        val version = offer.version
        _state.value = InstallState.Getting(version)
        job = scope.launch { run(version) }
        return true
    }

    private suspend fun run(version: String) {
        var file: File? = null
        try {
            withContext(io) { clearDir() }
            port.leaveVoice()
            val f = withContext(io) { File.createTempFile("bundle-", ".tar.gz", port.dir) }
            file = f
            val wanted = currentCoroutineContext()[Job]
            port.fetchBundle { body, length, said ->
                // Only the version the admin was asked about is installed, and nothing of another is read.
                if (said != version) throw WrongVersion(said?.takeIf { releaseVersion(it) })
                if (length > limit) throw TooLarge()
                val got = copyAtMost(body, f, limit) { wanted?.isActive != false }
                // Only a whole bundle is handed on: at least one byte, and all it said it holds.
                if (got == 0L || (length >= 0 && got != length)) throw IOException("Not whole")
            }
            _state.value = InstallState.Installing(version)
            // Mattermost answers only once the new version runs, so an upload that gave no answer
            // may still have installed it: the wait decides. A refusal ends at once: one in
            // Mattermost's words, or a 4xx without words, as from a proxy that turned the upload away.
            val answered = try {
                port.upload(f, bundleNameOf(version))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ServerError && oneParagraph(e.serverMessage) != null) throw e
                if (e is ApiException && e.status in 400..499) throw e
                false
            }
            withContext(io) { f.delete() }
            file = null
            val until = port.now() + INSTALL_WAIT_MS
            while (true) {
                port.pause(INSTALL_ASK_MS)
                val now = withContext(RequestDeadline(until, port::now)) { port.runningVersion() }
                // The new version, or one newer still, which the end names; a version that cannot be read is not it.
                if (now != null && releaseVersion(now) && !olderThan(now, version)) {
                    _state.value = InstallState.Installed(now)
                    return
                }
                if (port.now() >= until) {
                    _state.value = InstallState.Failed(version, if (answered) notRunningYet(port.server, version) else COULD_NOT_INSTALL)
                    return
                }
            }
        } catch (e: CancellationException) {
            _state.value = InstallState.Idle
            throw e
        } catch (e: TooLarge) {
            _state.value = InstallState.Failed(version, TOO_LARGE)
        } catch (e: WrongVersion) {
            withContext(io) { file?.delete() }
            file = null
            // Read again, so that the line offers what the plugin has now.
            runCatching { port.runningVersion() }.onFailure { if (it is CancellationException) throw it }
            val newer = e.version
            _state.value = if (newer == null) InstallState.Failed(version, COULD_NOT_GET)
            else InstallState.Failed(version, readyNow(port.server, newer), newer)
        } catch (e: Exception) {
            val step = _state.value
            // The plugin refused the bundle: read again, so that an Install that cannot work stops being offered.
            if (step is InstallState.Getting && e is VillaNetException) {
                runCatching { port.runningVersion() }.onFailure { if (it is CancellationException) throw it }
            }
            val sentence = oneParagraph((e as? ServerError)?.serverMessage)
                ?: if (step is InstallState.Installing) COULD_NOT_INSTALL else COULD_NOT_GET
            _state.value = InstallState.Failed(version, sentence)
        } finally {
            file?.let { f -> withContext(kotlinx.coroutines.NonCancellable + io) { f.delete() } }
        }
    }

    /** Takes away what an earlier run left in the folder, and makes the folder when it is not there. */
    private fun clearDir() {
        val dir = port.dir
        dir.listFiles()?.forEach { it.deleteRecursively() }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No folder for the update")
    }

    private class TooLarge : IOException("Too large")

    /** The plugin answered with a bundle of another [version] than asked (null: none it says, or one that cannot be read). */
    private class WrongVersion(val version: String?) : IOException("Another version")

    companion object {
        const val GETTING = "Getting the update…"
        const val INSTALLING = "Installing…"
        const val TOO_LARGE = "The update is larger than 64 MB."
        const val COULD_NOT_GET = "Couldn't get the update. Try again in a moment."
        const val COULD_NOT_INSTALL = "Couldn't install the update. Try again in a moment."

        fun available(version: String) = "Matty Plugin $version is available"
        fun installed(version: String) = "Matty Plugin $version is installed."
        fun askTitle(version: String) = "Install Matty Plugin $version?"
        fun askText(server: String) = "$server restarts the plugin, which ends voice through Matty for a few seconds."
        fun readyNow(server: String, version: String) = "$server now has Matty Plugin $version ready. Tap Install again in a minute."
        fun notRunningYet(server: String, version: String) =
            "$server took Matty Plugin $version but is not running it yet. Look in System Console > Plugins."

        /**
         * Copies [input] into [to], at most [limit] bytes; more than that is refused ([TooLarge])
         * before it is written. [wanted] is asked between reads: the copy stops when nobody waits
         * for it any more.
         */
        internal fun copyAtMost(input: InputStream, to: File, limit: Long, wanted: () -> Boolean = { true }): Long {
            var total = 0L
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (!wanted()) throw CancellationException("Nobody waits for the update")
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw TooLarge()
                    out.write(buf, 0, n)
                }
            }
            return total
        }
    }
}

/**
 * The Plugins screen's line for [offer] and the install's [step], with the plugin running at
 * [running] as `/access` says: the step while one runs, the end when it is over, and otherwise
 * the offer, with Install (asking first, about [server]) when it is offered and [start] can start
 * it. A failure says the server's sentence under the offer. An end that [running] contradicts is
 * not said: "is installed." only while the running version is not older than the one installed,
 * and a failure only while the running version is still older than the one that failed.
 */
internal fun updateLineOf(offer: UpdateOffer?, step: InstallState, running: String, server: String, start: ((UpdateOffer) -> Unit)?): PluginUpdate? {
    when (step) {
        is InstallState.Getting -> return PluginUpdate(PluginInstaller.GETTING, null)
        is InstallState.Installing -> return PluginUpdate(PluginInstaller.INSTALLING, null)
        is InstallState.Installed -> if ((offer == null || offer.version == step.version) && !olderThan(running, step.version)) {
            return PluginUpdate(PluginInstaller.installed(step.version), null)
        }
        else -> {}
    }
    if (offer == null) return null
    val install = if (offer.install && start != null) {
        PluginInstall(PluginInstaller.askTitle(offer.version), PluginInstaller.askText(server)) { start(offer) }
    } else null
    val trouble = (step as? InstallState.Failed)
        ?.takeIf { (it.version == offer.version || it.newer == offer.version) && olderThan(running, it.version) }?.sentence
    return PluginUpdate(PluginInstaller.available(offer.version), offer.link, install, trouble)
}
