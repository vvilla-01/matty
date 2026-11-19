package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.verify.AttestationUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import java.security.MessageDigest

// The app check (the plugin's contract/README.md, "The app check"): Matty proves to the server,
// on Android's word rather than its own, that it is the official build. The server gives a
// challenge for this session; the app makes a key in the phone's Keystore attested for a
// challenge derived from it ([appCheckChallenge]) and sends the chain; the server reads it and
// keeps its verdict for the session. Android attests the app's RSA certificate (the first of its
// signing history), never the ML-DSA one, so this proves the classical signature only; and a
// program that hides what it is cannot be told from the web panel, so a server that requires
// the check stops honest copies that were altered or rebuilt, not a determined impostor.

/** The first line of what the attestation challenge is the digest of: the format and its version. */
internal const val APP_CHECK_HEADER = "matty-app-check 1"

internal const val APP_CHECK_OFF = "off"
internal const val APP_CHECK_REPORT = "report"
internal const val APP_CHECK_REQUIRE = "require"
internal const val APP_VERIFIED = "verified"
internal const val APP_UNVERIFIED = "unverified"
internal const val APP_NONE = "none"

/**
 * The address the app reaches the server at, as the challenge names it: `scheme://host[:port]`,
 * lower case, the port left out when it is the scheme's default, no path, an IPv6 host in
 * brackets. The plugin writes its own Site URL's the same way, and the two must be equal.
 */
internal fun appCheckOrigin(url: HttpUrl): String {
    val scheme = url.scheme.lowercase()
    val host = url.host.lowercase().let { if (':' in it) "[$it]" else it }
    return if (url.port == HttpUrl.defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:${url.port}"
}

/**
 * The attestation challenge for [challenge] (the base64 exactly as the server sent it), asked for
 * by [userId] at [origin]: the SHA-256 of the four lines joined by a single line feed, with none
 * after the last. So an attestation answers one challenge, for one person, at one server.
 */
internal fun appCheckChallenge(origin: String, userId: String, challenge: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest("$APP_CHECK_HEADER\n$origin\n$userId\n$challenge".toByteArray(Charsets.UTF_8))

/**
 * When to prove this app to a server: once per session and app version, again when the server
 * says the verdict has gone, and never in a loop. Nothing is asked while the check is off, nor
 * of a session whose verdict is for this version. A verdict the server no longer holds (it says
 * "none" where this plan was given one: an admin changed the rules for the check, or it expired)
 * is proved again soon, but not sooner than [soonMs] after the last try; and after [SOON_RUN]
 * such proofs in a row, each less than [againMs] after the one before (a server that drops every
 * verdict it gives), the next waits [againMs]. A verdict the server keeps no version for (it
 * failed before the attestation was read: the server's Site URL, or a boot key it does not list
 * yet, both of which an admin can change) and a try that came to nothing (no network, the server
 * busy, the phone's secure hardware busy) are tried again, but not sooner than [againMs] after
 * the last try; and a phone that cannot make an attested key is not asked again while the app
 * runs, unless the person asks ([forget]). An "unverified" verdict held for this version stands
 * (the server says "none" instead once its rules have changed); the person's own ask proves once
 * more past it, once per ask. Held in memory: a new start of the app may try once more.
 *
 * | the server's word, and what was tried               | due in                          |
 * |-----------------------------------------------------|---------------------------------|
 * | the check off, or no word                           | never                           |
 * | a verdict for this version, verified                | never                           |
 * | a verdict for this version, unverified              | never; at once on the ask       |
 * | anything else, never tried (or the person asked)    | at once                         |
 * | this phone could not make the proof                 | never                           |
 * | a verdict for this app here, and the server has one | never                           |
 * | none, where the server had given a verdict          | [soonMs] after the last try ([againMs] after a run of [SOON_RUN]) |
 * | a verdict with no version, or a try that came to nothing | [againMs] after the last try |
 */
internal class ProofPlan(private val clock: () -> Long, private val againMs: Long = 10 * 60_000L, private val soonMs: Long = 30_000L) {
    /** [run]: how many answered tries came before this one, each less than [againMs] after the one before it. */
    private class Tried(val at: Long, val verdict: Boolean, val cannot: Boolean, val run: Int)

    /** By session (the server, and a digest of its token) and app version. */
    private val tried = HashMap<String, Tried>()
    /** Sessions whose person asked to try again ([forget]) and whose try has not been made yet. */
    private val asked = HashSet<String>()

    /** Whether a proof is due now: see [dueIn]. */
    fun due(session: String, versionCode: Long, word: VnAppWord?): Boolean = dueIn(session, versionCode, word) == 0L

    /**
     * When a proof is due, as the table above has it: 0 now, the milliseconds until it is when it
     * is due later, or null when it is not due without the person's ask ([forget]).
     */
    fun dueIn(session: String, versionCode: Long, word: VnAppWord?): Long? {
        val check = word?.check.orEmpty()
        if (check != APP_CHECK_REPORT && check != APP_CHECK_REQUIRE) return null
        val held = word!!.versionCode == versionCode && word.state != APP_NONE
        // Held for this version: a verified one stands; an unverified one only on the person's ask.
        if (held) return if (word.state == APP_UNVERIFIED && session in asked) 0L else null
        val last = tried["$session|$versionCode"] ?: return 0L
        if (last.cannot) return null
        // The verdict this app was given here, and the server still holds one: it is this one's.
        // (One with no version came before the attestation was read, and may pass once an
        // admin has changed what it failed on.)
        if (last.verdict && word.state != APP_NONE && (word.state == APP_VERIFIED || word.versionCode != 0L)) return null
        // The server no longer holds the verdict it gave: again soon, never in a loop, and after
        // a run of them, in a while.
        val wait = if (last.verdict && word.state == APP_NONE && last.run < SOON_RUN) soonMs else againMs
        // Given before the attestation was read, or the try came to nothing: again, in a while.
        return (last.at + wait - clock()).coerceAtLeast(0L)
    }

    /**
     * The person asked to try again: what was tried in [session] is forgotten, a phone that could
     * not included, and the run of proofs made soon with it, and the next try is due even past
     * an "unverified" verdict held for this version, once (until [tried] records it).
     */
    fun forget(session: String) {
        tried.keys.removeAll { it.startsWith("$session|") }
        asked += session
    }

    /** A try was made: [verdict] when the server answered it, [cannot] when this phone could not make the proof. */
    fun tried(session: String, versionCode: Long, verdict: Boolean, cannot: Boolean = false) {
        val key = "$session|$versionCode"
        val now = clock()
        val before = tried[key]
        val run = if (before != null && before.verdict && now - before.at < againMs) before.run + 1 else 0
        tried[key] = Tried(now, verdict, cannot, run)
        asked -= session
    }

    companion object {
        /** How many proofs made soon after the first come before the wait is [againMs] again. */
        const val SOON_RUN = 4
    }
}

/**
 * What is done with [plan]'s answer, so that a proof due later is made with nobody there. When
 * the reader's access is read ([dueNow]): a proof due now is made, one due later has [later] set
 * for then (one at a time: a new wait replaces the one set), and one not due without the person's
 * ask has it cancelled. When a try ends ([ended]): one the server answered has everything read
 * again, and one that came to nothing has [later] set for the next (in a while: see [ProofPlan]).
 * A read [later] makes that fails sets nothing more: nothing loops on a server that does not answer.
 */
internal class ProofScheduler(private val plan: ProofPlan, private val later: Later) {
    /** Whether a proof is due now. Nothing is decided while one is on its way ([proving]). */
    fun dueNow(session: String, versionCode: Long, word: VnAppWord?, proving: Boolean): Boolean {
        if (proving) return false
        val wait = plan.dueIn(session, versionCode, word)
        if (wait != null && wait > 0L) later.after(wait) else later.cancel()
        return wait == 0L
    }

    /**
     * A try has ended, with the server's word as it stands now: whether everything is to be read
     * again (the server [answered] it). A try that came to nothing was just recorded, so the next
     * is never due now: [later] is set for it (or cancelled, for a phone that cannot).
     */
    fun ended(answered: Boolean, session: String, versionCode: Long, word: VnAppWord?): Boolean {
        if (answered) return true
        dueNow(session, versionCode, word, proving = false)
        return false
    }

    /** The person left this server: nothing more is read for it. */
    fun cancel() = later.cancel()
}

/**
 * What the person's Refresh does: proves this Matty again (as Try again does) while the server
 * refuses it ([refused]), or while its check is on and it holds no verdict for this Matty
 * ([word] says none); otherwise it only reads everything again. A verdict the server holds for
 * this version is not proved again by Refresh: under `report` nothing can have changed (the
 * server says none once its rules change), and under `require` it is refused, which Try again covers.
 */
internal fun refreshProves(refused: String?, word: VnAppWord?): Boolean {
    if (refused != null) return true
    val check = word?.check.orEmpty()
    return (check == APP_CHECK_REPORT || check == APP_CHECK_REQUIRE) && word!!.state == APP_NONE
}

/**
 * One try of [session]'s proof, run by [prove] and recorded in this plan when it ends: answered
 * when [prove] returns, could not ([cannot] is told why) when it throws [AttestationUnavailable],
 * and anything else is a try that came to nothing (offline, the server busy, the phone's hardware
 * busy), quietly. A try cancelled before it was answered (the person left this server) is not
 * recorded, however the cancel surfaced: no proof reached the server, so coming back may start
 * one at once. Returns whether the server answered; a cancel is passed on.
 */
internal suspend fun ProofPlan.attempt(session: String, versionCode: Long, cannot: (String?) -> Unit, prove: suspend () -> Unit): Boolean {
    var answered = false
    var could = true
    try {
        prove()
        answered = true
    } catch (e: CancellationException) {
        throw e
    } catch (e: AttestationUnavailable) {
        could = false
        cannot(e.message)
    } catch (e: Exception) {
        // Came to nothing: tried again in a while.
    } finally {
        if (answered || !could || currentCoroutineContext().isActive) tried(session, versionCode, verdict = answered, cannot = !could)
    }
    return answered
}

/**
 * The proof on its way, if one is, and for which session: one at a time. A proof for a server
 * just left is dropped ([drop]) so that the next server's can start, and when the dropped one ends
 * at last it clears nothing of the next one's ([end] takes only its own run).
 */
internal class ProofUnderWay {
    class Run(val session: String)

    private val _run = MutableStateFlow<Run?>(null)
    val run: StateFlow<Run?> = _run

    /** A new run for [session], or null while another is on its way. */
    fun begin(session: String): Run? {
        if (_run.value != null) return null
        return Run(session).also { _run.value = it }
    }

    fun end(run: Run) { _run.compareAndSet(run, null) }

    fun drop() { _run.value = null }
}

/**
 * One proof: a challenge, the attestation for it ([attestFor] makes the chain and sends it), and
 * the server's verdict. A challenge the server no longer holds (`409`: it expired while the
 * phone's hardware worked, or another proof spent it) is asked for once more straight away.
 */
internal suspend fun proveOnce(challenge: suspend () -> String, attestFor: suspend (String) -> VnAttestAnswer): VnAttestAnswer =
    try {
        attestFor(challenge())
    } catch (e: VillaNetException) {
        if (e.status != 409) throw e
        attestFor(challenge())
    }

/**
 * The app check's line, where the plugin is listed and in the panel's menu, or null while the
 * server's check is off (or it didn't say). [local] is why this phone could not prove itself, if
 * it could not; [proving] is a proof on its way.
 */
internal fun appCheckWords(word: VnAppWord?, local: String?, proving: Boolean): Pair<String, Boolean>? {
    val check = word?.check.orEmpty()
    if (check != APP_CHECK_REPORT && check != APP_CHECK_REQUIRE) return null
    val required = check == APP_CHECK_REQUIRE
    val reason = word!!.reason.trim()
    return when (word.state) {
        APP_VERIFIED -> "This phone's Matty is verified by this server" + (if (word.development) " (development build)" else "") to false
        // The server's reason is a sentence of its own.
        APP_UNVERIFIED -> (if (reason.isEmpty()) "This phone's Matty is not verified by this server" else "Not verified by this server. $reason") to true
        else -> {
            val own = local?.trim()?.ifEmpty { null }
            when {
                proving -> "This phone's Matty is being checked by this server" to false
                // This phone could not make the proof: nothing reached the server.
                own != null -> "Not checked by this server yet. $own" to required
                // The server's reason says why it holds nothing (its rules changed since): alone,
                // as this Matty may well have been checked before.
                reason.isNotEmpty() -> reason to required
                else -> "This phone's Matty has not been checked by this server yet" to required
            }
        }
    }
}

/**
 * The app check's word in hand once the server has refused a request `app_unverified`: it holds
 * no verdict of verified for this session, whatever was last read, so one of verified becomes
 * none (until `/access` says what it holds); any other stands.
 */
internal fun afterAppRefusal(word: VnAppWord?): VnAppWord? =
    if (word?.state == APP_VERIFIED) word.copy(state = APP_NONE, reason = "", development = false, versionCode = 0) else word

/**
 * The server's refusal of this Matty for the app check, as [refused] holds it, from what its
 * answers say. Each answer is heard on whichever thread it came, and applied where [heard]'s
 * `post` runs it (the main thread, in the order posted), so a gated answer heard after a refusal
 * is always applied after it. [onRefused] runs for each refusal applied.
 */
internal class AppRefusalHearing(private val refused: MutableStateFlow<String?>, private val onRefused: (String) -> Unit) {
    /** An answer: the server's sentence for a request refused `app_unverified`, null for a gated route answered. */
    fun heard(sentence: String?, post: (() -> Unit) -> Unit) = post {
        if (sentence == null) {
            if (refused.value != null) refused.value = null
        } else {
            refused.value = sentence
            onRefused(sentence)
        }
    }

    /**
     * The server's verdict on a proof: verified is its own word that this Matty is no longer
     * refused, so the refusal goes with it (a gated read puts it back if it still stands).
     */
    fun proved(state: String) {
        if (state == APP_VERIFIED) refused.value = null
    }
}

/** What follows once `GET /access` has been read again for a refusal: see [afterRefusedAccess]. */
internal enum class RefusedNext { Nothing, AskGated, Stop }

/**
 * After a refusal, `GET /access` read again: the stream stops only while the refusal stands, no
 * proof is on its way and the server does not hold this Matty verified. When it does, the refusal
 * came before its verdict, and a gated read decides.
 */
internal fun afterRefusedAccess(refused: String?, proving: Boolean, word: VnAppWord?): RefusedNext = when {
    refused == null || proving -> RefusedNext.Nothing
    word?.state == APP_VERIFIED -> RefusedNext.AskGated
    else -> RefusedNext.Stop
}
