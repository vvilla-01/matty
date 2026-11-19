package dev.standin.distributor

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * The distributor's half of the UnifiedPush Android protocol (AND_3.1), without Android: what it
 * keeps, what it tells the stand-in push server and what it sends to the apps. The Android side
 * (Android.kt) reads the broadcasts, carries out [StandIn] and [Apps], and polls.
 */

/** The stand-in push server, as `adb reverse tcp:7788 tcp:7788` makes it reachable from the phone. */
const val STAND_IN = "http://localhost:7788"

/** The endpoint an app is given for [id]: the stand-in's push address, which the app's server posts to. */
fun endpointUrl(id: String): String = "$STAND_IN/push/$id"

/** How an app's identity was learnt from its REGISTER or UNREGISTER. */
enum class How {
    /** The broadcast shared its sender's identity (Android 14 and later; what AND_3 asks for). */
    SHARED,
    /** From the creator of its `pi` PendingIntent (AND_3 before Android 14). */
    PI,
    /** From its `application` extra (AND_2): what the app says it is, unproven. */
    APPLICATION,
}

/** One app's registration: its connection token and the endpoint it was given. */
data class Registration(
    val app: String,
    val token: String,
    /** The endpoint's last part at the stand-in push server. */
    val endpointId: String,
    /** The VAPID key the app registered with, as it gave it; "" for none. */
    val vapid: String,
    val description: String,
    val how: How,
    /** The stand-in push server said the endpoint was dropped: it is not polled until registered again. */
    val gone: Boolean = false,
)

object Identity {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isPackage(name: String?): Boolean = name != null && name.length <= 255 && PACKAGE.matches(name)

    /**
     * Who is asking, in the order the real distributors read it (ntfy and android-distributor):
     * the shared identity, then the `pi` PendingIntent's creator, then the `application` extra.
     */
    fun of(sentFrom: String?, piCreator: String?, application: String?): Pair<String, How>? = when {
        isPackage(sentFrom) -> sentFrom!! to How.SHARED
        isPackage(piCreator) -> piCreator!! to How.PI
        isPackage(application) -> application!! to How.APPLICATION
        else -> null
    }
}

object Protocol {
    const val MAX_TOKEN_BYTES = 100
    const val MAX_ID_BYTES = 100
    const val MAX_DESCRIPTION_BYTES = 100
    /** As many registrations as it keeps, which is what the stand-in push server keeps endpoints for. */
    const val MAX_REGISTRATIONS = 1000

    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val NETWORK = "NETWORK"
    const val ACTION_REQUIRED = "ACTION_REQUIRED"

    private val VAPID = Regex("^[A-Za-z0-9_-]{87}$")

    fun isToken(token: String?): Boolean = !token.isNullOrEmpty() && token.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES

    /**
     * The VAPID key REGISTER carried, as android-distributor takes it: 87 base64url characters of
     * an uncompressed P-256 point; anything else registers without a key (null).
     */
    fun vapid(raw: String?): String? {
        if (raw == null || !VAPID.matches(raw)) return null
        val point = runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull() ?: return null
        return raw.takeIf { point.size == 65 && point[0] == 4.toByte() }
    }
}

/** Where the distributor keeps what it holds, as one text. */
interface Saved {
    fun read(): String?
    fun write(text: String)
}

/** The stand-in push server's own routes for a distributor (`/fake/push/<id>`). */
interface StandIn {
    /** The endpoint [id] exists, and takes only messages signed with [vapid] when there is one. */
    fun put(id: String, vapid: String?): Boolean

    /** The endpoint [id] is dropped: what is sent to it is answered 404. */
    fun delete(id: String): Boolean
}

/** What the distributor sends to the apps (the connector's actions). */
interface Apps {
    fun newEndpoint(app: String, token: String, endpoint: String, id: String)
    fun failed(app: String, token: String, reason: String?)
    fun unregistered(app: String, token: String, moveTo: String?)
    fun tempUnavailable(app: String, token: String, fallback: String?)
    fun message(app: String, token: String, body: ByteArray, id: String)
}

/** The components an app finds a distributor by: its REGISTER receiver and its link activity. */
interface Components {
    /** Switches both on (as the manifest has them) or off, so the apps no longer find this distributor. */
    fun enabled(on: Boolean)
}

/**
 * UNREGISTERED's extras. A migration names its target only as `newDistributor`: that is what
 * android-distributor sends and the connector library reads (the specification's `useDistributor`
 * is sent by none).
 */
fun unregisteredExtras(token: String, moveTo: String?): List<Pair<String, String>> =
    listOfNotNull("token" to token, moveTo?.let { "newDistributor" to it })

/** How long after binding to an app's raise-to-foreground service a MESSAGE waits for it to connect, and when it unbinds. */
const val RAISE_WAIT_MS = 5_000L

/**
 * One MESSAGE waiting on its binding to the app's RAISE_TO_FOREGROUND service: the first thing
 * that happens decides. A connection sends it; a null binding, a binding that died or
 * [RAISE_WAIT_MS] without a connection drops it. Whatever happens after that decides nothing
 * (null). The binding ends [RAISE_WAIT_MS] after it was made, as ntfy's does, however late the
 * connection came, or at once when the message is dropped ([unbinds]).
 */
class Raise {
    enum class Event { CONNECTED, NULL_BINDING, DIED, TIMED_OUT }
    enum class Outcome { SEND, DROP }

    private var decided = false

    @Synchronized fun on(event: Event): Outcome? {
        if (decided) return null
        decided = true
        return if (event == Event.CONNECTED) Outcome.SEND else Outcome.DROP
    }

    companion object {
        /**
         * Whether [event], which [on] answered [outcome], ends the binding: the timer set when it
         * was bound always does, and so does a drop; a connection that sends never does.
         */
        fun unbinds(event: Event, outcome: Outcome?): Boolean = event == Event.TIMED_OUT || outcome == Outcome.DROP
    }
}

/** A steering command, from `adb shell am broadcast`. */
sealed interface Steer {
    /** The next [times] REGISTERs are answered REGISTRATION_FAILED, with [reason] (null: none given). */
    data class Fail(val reason: String?, val times: Int) : Steer
    /** Every registration is dropped and its app told UNREGISTERED, naming [moveTo] when there is one. */
    data class UnregisterAll(val moveTo: String?) : Steer
    /** Every registration gets a new endpoint; the old ones are dropped at the stand-in. */
    data object NewEndpoints : Steer
    /** TEMP_UNAVAILABLE to every app (naming [fallback] when there is one); nothing is delivered until [Available]. */
    data class Unavailable(val fallback: String?) : Steer
    /** Available again: NEW_ENDPOINT to every app, and what was held is delivered. */
    data object Available : Steer
    /** Whether the link activity answers as if the person cancelled. */
    data class Link(val cancel: Boolean) : Steer
    /**
     * Set up or not. Not set up, it does what ntfy does with its UnifiedPush switched off: a
     * REGISTER is answered ACTION_REQUIRED and its receiver and link activity are then switched
     * off. Set up again, both are switched back on.
     */
    data class SetUp(val on: Boolean) : Steer
    /** Says what it holds. */
    data object State : Steer

    companion object {
        const val PREFIX = "dev.standin.distributor."
        private val REASON = Regex("^[A-Z_]{1,40}$")

        /** The command [action] names, with its extras from [extra]; null when it is not one. */
        fun of(action: String?, extra: (String) -> Any?): Steer? {
            fun string(name: String) = (extra(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            fun target(name: String): String? = string(name)?.let { if (Identity.isPackage(it)) it else return null }
            return when (action?.removePrefix(PREFIX)?.takeIf { action.startsWith(PREFIX) }) {
                "FAIL" -> {
                    val reason = when (val given = string("reason")) {
                        null -> Protocol.INTERNAL_ERROR
                        "none" -> null
                        else -> given.takeIf { REASON.matches(it) } ?: return null
                    }
                    val times = when (val given = extra("times")) {
                        null -> 1
                        is Int -> given
                        is String -> given.trim().toIntOrNull() ?: return null
                        else -> return null
                    }
                    if (times !in 1..100) return null
                    Fail(reason, times)
                }
                "UNREGISTER_ALL" -> UnregisterAll(if (extra("to") == null) null else target("to") ?: return null)
                "NEW_ENDPOINTS" -> NewEndpoints
                "UNAVAILABLE" -> Unavailable(if (extra("fallback") == null) null else target("fallback") ?: return null)
                "AVAILABLE" -> Available
                "LINK" -> when (val cancel = extra("cancel")) {
                    is Boolean -> Link(cancel)
                    "true" -> Link(true)
                    "false" -> Link(false)
                    else -> null
                }
                "SETUP" -> when (val on = extra("on")) {
                    is Boolean -> SetUp(on)
                    "true", "on" -> SetUp(true)
                    "false", "off" -> SetUp(false)
                    else -> null
                }
                "STATE" -> State
                else -> null
            }
        }
    }
}

/**
 * Everything the distributor holds and decides. Every call is made under its lock, and what it
 * keeps is written through [saved] before an app hears of it.
 */
class Core(
    private val saved: Saved,
    private val standIn: StandIn,
    private val apps: Apps,
    private val components: Components,
    private val random: Random = SecureRandom(),
) {
    private var registrations: List<Registration> = emptyList()
    private var failing: Steer.Fail? = null
    /** Non-null while temporarily unavailable: the fallback it named ("" for none). */
    private var unavailable: String? = null
    private var linkCancels = false
    private var setUp = true
    /** Its receiver and link activity were switched off because it is not set up. */
    private var switchedOff = false

    // Only in memory: what acknowledging looks like since the process started.
    private val unacked = LinkedHashSet<String>()
    private var acked = 0
    private val held = ArrayList<Triple<String, String, ByteArray>>()
    /** Messages dropped because the app's raise-to-foreground service did not connect; not under the lock. */
    private val notRaised = AtomicInteger()

    /** Called after anything that changes what is polled. */
    @Volatile var onChange: (() -> Unit)? = null

    init {
        load(saved.read())
    }

    @Synchronized fun registrations(): List<Registration> = registrations

    /** The endpoints to poll: none while temporarily unavailable. */
    @Synchronized fun pollTargets(): List<String> = if (unavailable != null) emptyList() else registrations.filter { !it.gone }.map { it.endpointId }

    @Synchronized fun linkCancels(): Boolean = linkCancels

    /** REGISTER from [app] (known [how]). */
    @Synchronized fun register(app: String, how: How, token: String?, vapid: String?, description: String?) {
        if (!Protocol.isToken(token)) return
        token!!
        if (description != null && description.toByteArray(Charsets.UTF_8).size > Protocol.MAX_DESCRIPTION_BYTES) return
        if (!setUp) {
            if (!switchedOff) {
                switchedOff = true
                persist()
                components.enabled(false)
            }
            return apps.failed(app, token, Protocol.ACTION_REQUIRED)
        }
        val existing = registrations.firstOrNull { it.token == token }
        if (existing != null && existing.app != app) return apps.failed(app, token, Protocol.INTERNAL_ERROR)
        unavailable?.let { return apps.tempUnavailable(app, token, it.ifEmpty { null }) }
        failing?.let { fail ->
            failing = if (fail.times > 1) fail.copy(times = fail.times - 1) else null
            persist()
            return apps.failed(app, token, fail.reason)
        }
        if (existing == null && registrations.size >= Protocol.MAX_REGISTRATIONS) return apps.failed(app, token, Protocol.ACTION_REQUIRED)

        val key = Protocol.vapid(vapid) ?: ""
        val registration = existing?.copy(vapid = key, description = description ?: existing.description, how = how, gone = false)
            ?: Registration(app, token, newEndpointId(), key, description ?: "", how)
        // The stand-in learns the key first, so nothing the app's server sends is refused for it.
        if (!standIn.put(registration.endpointId, key.ifEmpty { null })) return apps.failed(app, token, Protocol.NETWORK)
        registrations = if (existing == null) registrations + registration else registrations.map { if (it.token == token) registration else it }
        persist()
        changed()
        apps.newEndpoint(app, token, endpointUrl(registration.endpointId), newMessageId())
    }

    /** UNREGISTER from [app]: forgotten, dropped at the stand-in, and UNREGISTERED sent back. */
    @Synchronized fun unregister(app: String, token: String?) {
        val registration = registrations.firstOrNull { it.token == token && it.app == app } ?: return
        registrations = registrations - registration
        persist()
        standIn.delete(registration.endpointId)
        changed()
        apps.unregistered(app, registration.token, null)
    }

    /** MESSAGE_ACK from [app] for a NEW_ENDPOINT or MESSAGE it was sent. */
    @Synchronized fun ack(app: String, token: String?, id: String?) {
        if (id == null || registrations.none { it.token == token && it.app == app }) return
        if (unacked.remove(id)) acked++
    }

    /** A message the stand-in handed over for [endpointId]: false when no registration has it. */
    @Synchronized fun deliver(endpointId: String, id: String, body: ByteArray): Boolean {
        val registration = registrations.firstOrNull { it.endpointId == endpointId && !it.gone } ?: return false
        if (unavailable != null) {
            held += Triple(endpointId, id, body)
            if (held.size > MAX_HELD) held.removeAt(0)
            return true
        }
        val messageId = id.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_ID_BYTES } ?: newMessageId()
        expectAck(messageId)
        apps.message(registration.app, registration.token, body, messageId)
        return true
    }

    /** A MESSAGE was dropped because the app's raise-to-foreground service did not connect in time. */
    fun notRaised() {
        notRaised.incrementAndGet()
    }

    /** The stand-in answered 404 for [endpointId]: dropped there, so it is not polled until registered again. */
    @Synchronized fun gone(endpointId: String) {
        if (registrations.none { it.endpointId == endpointId && !it.gone }) return
        registrations = registrations.map { if (it.endpointId == endpointId) it.copy(gone = true) else it }
        persist()
        changed()
    }

    /** Carries out [command]; the answer says what happened, for the shell that asked. */
    @Synchronized fun steer(command: Steer): String = when (command) {
        is Steer.Fail -> {
            failing = command
            persist()
            "The next ${command.times} registration(s) fail with ${command.reason ?: "no reason given"}."
        }
        is Steer.UnregisterAll -> {
            val all = registrations
            registrations = emptyList()
            persist()
            for (registration in all) {
                standIn.delete(registration.endpointId)
                apps.unregistered(registration.app, registration.token, command.moveTo)
            }
            changed()
            "Unregistered ${all.size}" + (command.moveTo?.let { ", naming $it to move to." } ?: ".")
        }
        Steer.NewEndpoints -> {
            var moved = 0
            val after = registrations.map { old ->
                val new = old.copy(endpointId = newEndpointId(), gone = false)
                if (!standIn.put(new.endpointId, new.vapid.ifEmpty { null })) return@map old
                moved++
                new
            }
            val before = registrations
            registrations = after
            persist()
            for ((old, new) in before.zip(after)) if (old !== new) {
                standIn.delete(old.endpointId)
                apps.newEndpoint(new.app, new.token, endpointUrl(new.endpointId), newMessageId())
            }
            changed()
            "New endpoints for $moved of ${after.size}."
        }
        is Steer.Unavailable -> {
            unavailable = command.fallback ?: ""
            persist()
            for (registration in registrations) apps.tempUnavailable(registration.app, registration.token, command.fallback)
            changed()
            "Temporarily unavailable for ${registrations.size}" + (command.fallback?.let { ", naming $it as the fallback." } ?: ".")
        }
        Steer.Available -> {
            val was = unavailable != null
            unavailable = null
            persist()
            if (was) for (registration in registrations) apps.newEndpoint(registration.app, registration.token, endpointUrl(registration.endpointId), newMessageId())
            val waiting = held.toList()
            held.clear()
            waiting.forEach { (endpointId, id, body) -> deliver(endpointId, id, body) }
            changed()
            if (was) "Available again; ${waiting.size} held message(s) delivered." else "It was available."
        }
        is Steer.Link -> {
            linkCancels = command.cancel
            persist()
            if (command.cancel) "The link activity answers as if cancelled." else "The link activity answers with this distributor."
        }
        is Steer.SetUp -> {
            setUp = command.on
            if (command.on) switchedOff = false
            persist()
            if (command.on) {
                components.enabled(true)
                "Set up: its receiver and link activity are on."
            } else {
                "Not set up: the next REGISTER is answered ACTION_REQUIRED, and its receiver and link activity are then switched off."
            }
        }
        Steer.State -> describe()
    }

    /** What it holds, for a person: never a connection token. */
    @Synchronized fun describe(): String = buildString {
        appendLine("Push server: $STAND_IN")
        appendLine(if (unavailable == null) "Available." else "Temporarily unavailable" + (unavailable!!.ifEmpty { null }?.let { " (fallback $it)." } ?: "."))
        failing?.let { appendLine("The next ${it.times} registration(s) fail with ${it.reason ?: "no reason given"}.") }
        if (linkCancels) appendLine("The link activity answers as if cancelled.")
        if (!setUp) appendLine("Not set up: REGISTER is answered ACTION_REQUIRED" + if (switchedOff) "; its receiver and link activity are off." else ".")
        appendLine("Acknowledged: $acked; waiting for an acknowledgement: ${unacked.size}; held: ${held.size}.")
        appendLine("Dropped, the app's raise-to-foreground service not connected within ${RAISE_WAIT_MS / 1000} s: ${notRaised.get()}.")
        appendLine("Registrations: ${registrations.size}")
        for (r in registrations) {
            append("- ").append(r.app)
            if (r.description.isNotEmpty()) append(" \"").append(r.description).append('"')
            append(": endpoint ").append(r.endpointId)
            append(if (r.vapid.isEmpty()) ", no VAPID key" else ", VAPID key")
            append(", identity ").append(r.how.name.lowercase())
            if (r.gone) append(", dropped at the push server")
            appendLine()
        }
    }.trimEnd()

    private fun expectAck(id: String) {
        unacked += id
        if (unacked.size > MAX_UNACKED) unacked.remove(unacked.first())
    }

    private fun changed() {
        onChange?.invoke()
    }

    /** 160 random bits, as the specification recommends for an endpoint. */
    private fun newEndpointId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(20).also(random::nextBytes))

    private fun newMessageId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes)).also(::expectAck)

    private fun persist() {
        saved.write(encode())
    }

    internal fun encode(): String = buildString {
        appendLine(VERSION)
        appendLine(fields("unavailable" to unavailable, "fail" to failing?.let { it.reason ?: "" }, "times" to failing?.times?.toString(), "cancel" to linkCancels.toString(), "setup" to setUp.toString(), "off" to switchedOff.toString()))
        for (r in registrations) {
            appendLine(fields("app" to r.app, "token" to r.token, "id" to r.endpointId, "vapid" to r.vapid, "description" to r.description, "how" to r.how.name, "gone" to r.gone.toString()))
        }
    }

    private fun load(text: String?) {
        val lines = text?.lines()?.filter { it.isNotEmpty() } ?: return
        if (lines.firstOrNull() != VERSION || lines.size < 2) return
        val flags = parse(lines[1])
        unavailable = flags["unavailable"]
        failing = flags["fail"]?.let { reason -> flags["times"]?.toIntOrNull()?.takeIf { it > 0 }?.let { Steer.Fail(reason.ifEmpty { null }, it) } }
        linkCancels = flags["cancel"] == "true"
        setUp = flags["setup"] != "false"
        switchedOff = flags["off"] == "true"
        registrations = lines.drop(2).mapNotNull { line ->
            val f = parse(line)
            val app = f["app"]?.takeIf(Identity::isPackage) ?: return@mapNotNull null
            val token = f["token"]?.takeIf(Protocol::isToken) ?: return@mapNotNull null
            val id = f["id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val how = runCatching { How.valueOf(f["how"] ?: "") }.getOrNull() ?: return@mapNotNull null
            Registration(app, token, id, f["vapid"] ?: "", f["description"] ?: "", how, f["gone"] == "true")
        }.distinctBy { it.token }
    }

    companion object {
        private const val VERSION = "stand-in distributor 1"
        private const val MAX_UNACKED = 1000
        private const val MAX_HELD = 100

        private fun fields(vararg pairs: Pair<String, String?>): String = pairs.filter { it.second != null }
            .joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, Charsets.UTF_8) }

        private fun parse(line: String): Map<String, String> = line.split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to runCatching { URLDecoder.decode(part.substring(at + 1), Charsets.UTF_8) }.getOrElse { return@mapNotNull null }
        }.toMap()
    }
}
