package ceo.villa.matty.push

import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.runCatchingCancellable
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnPushState
import ceo.villa.matty.service.Kept
import ceo.villa.matty.service.KeptTimes
import ceo.villa.matty.service.OneOff
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * What one saved server last said about push and about its registration (`GET /access` and the
 * `/me/push` answers), and what Matty still owes it. Kept beside the registrations, so a push
 * that wakes the app and the next app start pick up where the other left off.
 */
@Serializable
data class PushSide(
    /** The server's offer: [OFFER_ON], [OFFER_OFF], [OFFER_OLD] (a plugin before 4.1.0), [OFFER_NONE] (no plugin), "" unknown. */
    val offer: String = "",
    /** The server's sentence while it has push switched off. */
    val reason: String = "",
    /** The latest `/me/push` answer's state: none | pending | confirmed | refused | failing | refresh; "" before any. */
    val state: String = "",
    val host: String = "",
    val status: Int = 0,
    val kind: String = "",
    /** When the latest answer's state began, as the server says (Unix ms; 0 when it did not say). */
    val since: Long = 0,
    /** When the latest PUT was answered "pending" (ms); 0 while no hello is awaited. */
    val pendingAt: Long = 0,
    /** PUTs made since the latest confirmation (the first and its repeats for a missing hello). */
    val puts: Int = 0,
    /** Every PUT allowed went unanswered by a hello while the server still waited for one: the distributor is not delivering. */
    val unheard: Boolean = false,
    /**
     * The one PUT made by itself for a registration the server says is refused or failing has gone:
     * no other until the server answers confirmed, none or refresh, or a PUT carries a new address.
     * The same address again (the UnifiedPush app saying it at a start, or the server's none once it
     * let that registration go) is the same try ([PushPlan.sameTry]).
     */
    val retried: Boolean = false,
    /** A PUT that did not reach the server (no network, the app check, a limit): done again at the next start. */
    val owesPut: Boolean = false,
    /** A hello's nonce whose confirm did not reach the server: confirmed at the next start. */
    val owedNonce: String = "",
    /** The endpoint the latest PUT carried: what a confirm confirms. */
    val sent: String = "",
    /** The server's sentence for the latest PUT it refused (an address it will not take, the app check); "" once one is taken. */
    val refusal: String = "",
    /** The endpoint the server refused for good (400): not sent again until another endpoint or a new set-up. */
    val rejected: String = "",
    /** The server could not be reached when it was last asked: it is asked again when the network is back. */
    val unreachable: Boolean = false,
) {
    /** Never the endpoint or a nonce. */
    override fun toString() = "PushSide(offer=$offer, state=$state, puts=$puts, unheard=$unheard, owes=${owesPut || owedNonce.isNotEmpty()})"

    /** Whether something is owed to the server: a PUT, a confirm, or a look that could not be made. */
    val owes: Boolean get() = owesPut || owedNonce.isNotEmpty() || unreachable

    companion object {
        const val OFFER_ON = "on"
        const val OFFER_OFF = "off"
        const val OFFER_OLD = "old"
        const val OFFER_NONE = "none"
    }
}

/**
 * The rules for talking to a server about one registration, as pure functions of what the server
 * answered, what is remembered and the clock. [PushCenter] carries them out.
 */
object PushPlan {
    /** How long a `hello` is waited for after the server says "pending" before the server is asked again. */
    const val HELLO_WAIT_MS = 45_000L

    /** PUTs for one address before the distributor is said not to deliver: the first and two more. */
    const val MAX_PUTS = 3

    /** Why a PUT is made: [REPEAT] alone is the same address again for a hello that never came. */
    enum class Why { ENDPOINT, START, REPEAT }

    enum class Next { NOTHING, PUT, CONFIRM }

    /**
     * What to do at an app start (or when Settings looks) for [registration], once `GET /me/push`
     * answered [answer]: the side to keep and what to send. A registration with no endpoint yet
     * waits for its NEW_ENDPOINT, which sends it; an endpoint the server refused for good is not
     * sent again.
     */
    fun atStart(answer: VnPushState, registration: PushRegistration, side: PushSide, now: Long): Pair<Next, PushSide> {
        val heard = heard(side, answer)
        if (registration.endpoint == null) return Next.NOTHING to heard
        if (side.rejected.isNotEmpty() && side.rejected == registration.endpoint) return Next.NOTHING to heard
        if (side.owedNonce.isNotEmpty() && answer.state == "pending") return Next.CONFIRM to heard
        if (side.owesPut) return Next.PUT to heard
        // A refused or failing first registration the server let go: the same address is the same try, and its line stays.
        if (answer.state == "none" && sameTry(side, registration.endpoint)) return Next.NOTHING to side
        return when (answer.state) {
            "none", "refresh" -> Next.PUT to heard
            // The server confirmed an address; unless it is known to be this one, the same PUT
            // again is answered "confirmed" at no cost, or sends a hello for the new one.
            "confirmed" -> if (registration.isConfirmed) Next.NOTHING to heard.copy(pendingAt = 0, puts = 0, unheard = false, owedNonce = "")
            else Next.PUT to heard
            "pending" -> when {
                side.unheard -> Next.NOTHING to heard
                side.pendingAt != 0L && now - side.pendingAt < HELLO_WAIT_MS -> Next.NOTHING to heard
                side.puts >= MAX_PUTS -> Next.NOTHING to heard.copy(unheard = true)
                else -> Next.PUT to heard
            }
            // Once, until the server says otherwise: the person may have fixed the push server since.
            "refused", "failing" -> if (side.retried) Next.NOTHING to heard else Next.PUT to heard.copy(retried = true)
            else -> Next.NOTHING to heard
        }
    }

    /**
     * [atStart]'s answer ([decided], made for [side] as it was) for a look that only reads, as
     * Settings makes when a test push has not arrived: the one PUT Matty makes by itself for a
     * registration the server says is refused or failing is not made now and stays to be made, so
     * that its line and "Try again" show at once. Everything else is as [atStart] decided.
     */
    fun readOnly(decided: Pair<Next, PushSide>, answer: VnPushState, side: PushSide): Pair<Next, PushSide> {
        val (next, kept) = decided
        val itsOwn = next == Next.PUT && !side.owesPut && !side.retried && answer.state in TRIED
        return if (itsOwn) Next.NOTHING to kept.copy(retried = false) else decided
    }

    /** The side after a PUT of [endpoint], made for [why], was answered [answer] at [now]. */
    fun afterPut(answer: VnPushState, side: PushSide, endpoint: String, why: Why, now: Long): PushSide {
        val heard = heard(side, answer).copy(owesPut = false, sent = endpoint, refusal = "", rejected = "")
            .let { if (why == Why.ENDPOINT && endpoint != side.sent) it.copy(retried = false) else it }
        return when (answer.state) {
            "confirmed" -> heard.copy(pendingAt = 0, puts = 0, unheard = false, owedNonce = "")
            "pending" -> heard.copy(pendingAt = now, puts = if (why == Why.REPEAT) side.puts + 1 else 1, unheard = false)
            else -> heard
        }
    }

    /**
     * Whether [endpoint] is the address whose refusal or failure already had its one PUT made by
     * itself ([PushSide.retried]): the same address again is the same try, and nothing is sent for
     * it until the server answers confirmed or refresh, "Try again" is pressed or the address changes.
     */
    fun sameTry(side: PushSide, endpoint: String?): Boolean =
        endpoint != null && side.retried && side.sent == endpoint && side.state in TRIED

    private val TRIED = setOf("refused", "failing")

    /**
     * Whether a saved server is asked when Matty comes to the front: it owes something ([owes]),
     * or the chosen UnifiedPush app takes REGISTER ([usable]) and the server has no registration
     * there yet ([registered]) and no stop ([stopped]), and its last [offer] was push on or is not
     * known yet: one with no Matty Plugin, push switched off or a plugin too old is not asked again
     * until the next start or Settings looks.
     */
    fun askedAtFront(owes: Boolean, usable: Boolean, registered: Boolean, stopped: Boolean, offer: String): Boolean =
        owes || (usable && !registered && !stopped && (offer == PushSide.OFFER_ON || offer == ""))

    /** The side after a confirm was answered [answer]. */
    fun afterConfirm(answer: VnPushState, side: PushSide): PushSide =
        heard(side, answer).copy(pendingAt = 0, puts = 0, unheard = false, owedNonce = "", owesPut = false)

    /** Whether the hello for the latest "pending" is overdue: [HELLO_WAIT_MS] went by without it. */
    fun helloDue(side: PushSide, now: Long): Boolean =
        side.state == "pending" && side.pendingAt != 0L && !side.unheard && now - side.pendingAt >= HELLO_WAIT_MS

    /**
     * What an overdue hello asks for, once `GET /me/push` answered [answer] ([confirmedHere]: the
     * registration's own endpoint is the one the server confirmed). A push server that refused it,
     * or that the server cannot reach, is kept as the server says, and nothing is sent. Only while
     * the server still waits does the same address go again ([Next.PUT]), until [MAX_PUTS]; after
     * the last, a server still waiting means the distributor did not deliver.
     */
    fun helloMissing(side: PushSide, answer: VnPushState, confirmedHere: Boolean, now: Long): Pair<Next, PushSide> {
        if (!helloDue(side, now)) return Next.NOTHING to side
        val heard = heard(side, answer)
        fun again() = if (side.puts >= MAX_PUTS) Next.NOTHING to heard.copy(unheard = answer.state == "pending") else Next.PUT to heard
        return when (answer.state) {
            "refused", "failing" -> Next.NOTHING to heard.copy(pendingAt = 0, retried = true)
            "confirmed" -> if (confirmedHere) Next.NOTHING to heard.copy(pendingAt = 0, puts = 0, unheard = false, owedNonce = "") else again()
            "pending", "none", "refresh" -> again()
            else -> Next.NOTHING to heard
        }
    }

    /** What `GET /users/me` said about a session the server sent `ended` for. */
    enum class SessionCheck {
        /** Refused with Mattermost's own 401: the session is over. */
        OVER,
        /** Answered for the person signed in: the session lives; only its registration went. */
        ALIVE,
        /** No answer, or another one: the server is asked again later. */
        UNKNOWN,
    }

    /** How long after a first 401 `/users/me` is asked once more. */
    const val SESSION_CONFIRM_MS = 1_000L

    /**
     * [SessionCheck] from one `/users/me` ([answer]) for the saved server's user [signedInAs]. Only
     * Mattermost's own 401 is [SessionCheck.OVER] ([OneOff.mattermosts401]); a 401 page from
     * something in front of the server, any other status and no answer are [SessionCheck.UNKNOWN].
     */
    fun sessionCheck(answer: Result<User>, signedInAs: String): SessionCheck {
        answer.getOrNull()?.let { return if (it.id == signedInAs) SessionCheck.ALIVE else SessionCheck.UNKNOWN }
        return if (answer.exceptionOrNull()?.let(OneOff::mattermosts401) == true) SessionCheck.OVER else SessionCheck.UNKNOWN
    }

    /**
     * Whether a session the server sent `ended` for is over: `/users/me` ([me]) is asked, and a 401
     * is asked once more after [pause]; the session is over only when both are Mattermost's own
     * 401. An answer for the person at either look means it lives.
     */
    suspend fun sessionAsked(signedInAs: String, pause: suspend () -> Unit = { delay(SESSION_CONFIRM_MS) }, me: suspend () -> User): SessionCheck {
        val first = sessionCheck(runCatchingCancellable { me() }, signedInAs)
        if (first != SessionCheck.OVER) return first
        pause()
        return sessionCheck(runCatchingCancellable { me() }, signedInAs)
    }

    /**
     * Whether a `DELETE /me/push` that ended with [error] (null: answered) is settled. Only an
     * answer settles it: the plugin answers one even when there is nothing to delete, and a 404 is
     * Mattermost's while the plugin is not running.
     */
    fun deleteDone(error: Throwable?): Boolean = error == null

    /**
     * What a `DELETE /me/push` owed to [serverId] that ended with [error] leaves in [store]: paid
     * when it was answered ([deleteDone]), else still owed. Whether it was settled.
     */
    fun settleDelete(store: PushStore, serverId: String, error: Throwable?): Boolean {
        if (!deleteDone(error)) return false
        store.paidDelete(serverId)
        return true
    }

    /**
     * What the process's start does first, with push [enabled] or not and DELETEs [owed]: every
     * owed DELETE is sent (push on or off), the network is listened for while anything may be
     * owed, and the registrations are seen to only while push is on.
     */
    data class ProcessStart(val deletes: Set<String>, val listen: Boolean, val register: Boolean)

    fun atProcessStart(enabled: Boolean, owed: Set<String>) = ProcessStart(deletes = owed, listen = owed.isNotEmpty() || enabled, register = enabled)

    /**
     * What a push route's refusal [e] leaves of [side] (a session over and a key the server
     * replaced are dealt with before): push switched off there is remembered with its sentence;
     * the app check keeps its sentence and is [owe]d; an [endpoint] refused for good (400) keeps
     * the server's sentence and is not sent again; anything else (no network, a limit, a 404 while
     * the plugin is not running, a key that could not be replaced) is [owe]d.
     */
    fun afterRefusal(side: PushSide, e: Throwable, owe: (PushSide) -> PushSide, endpoint: String? = null): PushSide {
        val v = e as? VillaNetException
        return when {
            v?.code == VillaNetApi.PUSH_UNAVAILABLE -> side.copy(offer = PushSide.OFFER_OFF, reason = v.serverMessage.orEmpty())
            v?.code == VillaNetApi.PUSH_KEY -> owe(side)
            v?.code == VillaNetApi.APP_UNVERIFIED -> owe(side).copy(refusal = v.serverMessage.orEmpty())
            endpoint != null && v?.status == 400 -> side.copy(refusal = v.serverMessage?.trim().orEmpty().ifEmpty { REJECTED }, rejected = endpoint, owesPut = false)
            else -> owe(side)
        }
    }

    /** When the server refused an address and gave no sentence. */
    const val REJECTED = "This server doesn't accept the address your UnifiedPush app gave."

    /** Tries an owed PUT or confirm is sent again by itself, while the process runs. */
    const val OWED_TRIES = 3

    /** The longest wait before such a try. */
    const val OWED_MAX_WAIT_MS = 10 * 60_000L

    /** The wait when the server named none. */
    const val OWED_FIRST_WAIT_MS = 30_000L

    /**
     * How long before the owed PUT or confirm is sent again by itself, for the try after [tried]
     * earlier ones (0 for the first): the wait the server named ([retryAfterMs], a limit's
     * `retry_after_ms`; 0 when it named none) or else [OWED_FIRST_WAIT_MS], doubling with each
     * try, [OWED_MAX_WAIT_MS] at most; null once [OWED_TRIES] were made.
     */
    fun owedWait(tried: Int, retryAfterMs: Long): Long? {
        if (tried < 0 || tried >= OWED_TRIES) return null
        val first = if (retryAfterMs > 0) retryAfterMs else OWED_FIRST_WAIT_MS
        return minOf(OWED_MAX_WAIT_MS, first shl tried)
    }

    /** Whether a PUT or a confirm is owed to the server (what [owedWait] is for). */
    fun owesSend(side: PushSide): Boolean = side.owesPut || side.owedNonce.isNotEmpty()

    /** The side as "Try again" sends from: an address refused for good is tried once more, its sentence gone with it. */
    fun beforeTryAgain(side: PushSide): PushSide = if (side.rejected.isEmpty()) side else side.copy(rejected = "", refusal = "")

    /**
     * Whether push works for [registration]'s server ([side]): confirmed here, the server's last
     * word "confirmed" too, push not switched off there, and the distributor not unavailable.
     */
    fun works(registration: PushRegistration, side: PushSide): Boolean =
        registration.isConfirmed && side.offer != PushSide.OFFER_OFF && side.state == "confirmed" && !registration.unavailable

    /** The answers after which a PUT for refused or failing may be made by itself once more. */
    private val SETTLED = setOf("confirmed", "none", "refresh")

    private fun heard(side: PushSide, answer: VnPushState) = side.copy(
        state = answer.state, host = answer.host, status = answer.status, kind = answer.kind, since = answer.since,
        retried = side.retried && answer.state !in SETTLED,
    )
}

/**
 * The latest `clear` of each conversation, by saved server: a message that is not newer than the
 * clear of its conversation was read elsewhere before it arrived, and is not shown. Bounded: the
 * oldest conversations are forgotten first. Kept in [kept] when there is one, so that a push
 * delivered to a new process knows what was read meanwhile.
 */
class Clears(private val max: Int = 256, private val kept: Kept? = null) {
    private var read = false
    private val latest = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > max
    }

    private fun loaded(): LinkedHashMap<String, Long> {
        if (!read) {
            read = true
            KeptTimes.decode(runCatching { kept?.read() }.getOrNull()).forEach { (k, at) -> latest[k] = at }
        }
        return latest
    }

    @Synchronized
    fun remember(serverId: String, clear: PushPayload.Clear) {
        val map = loaded()
        val key = key(serverId, clear.channel, clear.root, clear.crt)
        map[key] = maxOf(map[key] ?: 0L, clear.at)
        kept?.let { k -> runCatching { k.write(KeptTimes.encode(map)) } }
    }

    /** Whether [m] was read elsewhere before it came. */
    fun hides(serverId: String, m: PushPayload.Message): Boolean = hides(serverId, m.channel, threadRoot(m.root, m.crt), m.at)

    /** Whether a message of [at] in [channel] ([threadRoot]: its collapsed thread, or "") was read elsewhere before it came. */
    @Synchronized
    fun hides(serverId: String, channel: String, threadRoot: String, at: Long): Boolean {
        val read = loaded()[key(serverId, channel, threadRoot)] ?: return false
        return at <= read
    }

    companion object {
        /** A collapsed thread is its own conversation; otherwise the channel is. */
        fun threadRoot(root: String, crt: Boolean): String = if (crt) root else ""

        private fun key(serverId: String, channel: String, root: String, crt: Boolean) = key(serverId, channel, threadRoot(root, crt))

        private fun key(serverId: String, channel: String, threadRoot: String) = "$serverId/$channel/$threadRoot"
    }
}
