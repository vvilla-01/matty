package ceo.villa.matty.push

import android.content.Context
import android.content.SharedPreferences
import ceo.villa.matty.container
import ceo.villa.matty.data.session.SessionStore
import ceo.villa.matty.service.Kept
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.UUID

/** Seals what must never be on disk in the clear, and opens it again (null: it no longer opens). */
interface Sealer {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/** The Keystore key that seals the session tokens ([SessionStore.seal]) seals the push keys too. */
class SessionSealer(private val sessions: SessionStore) : Sealer {
    override fun seal(plain: String): String = sessions.seal(plain)
    override fun open(sealed: String): String? = sessions.unseal(sealed)
}

/**
 * The push registrations, in the app's private preferences "matty_push" (excluded from backups
 * like everything else): one per saved server and distributor, the distributor the person
 * chose (with its signing certificates), whether push is switched on, what each server last said
 * about its registration ([PushSide]), and the servers a `DELETE /me/push` is owed to. Tokens are plain (the receiver looks them up before anything else); the private
 * key and the authentication secret are sealed. Everything is kept in memory once read. Beside
 * them, for the notifications, the posts shown lately and the latest clear of each conversation.
 */
class PushStore internal constructor(
    private val prefs: SharedPreferences,
    private val sealer: Sealer,
    private val random: SecureRandom = SecureRandom(),
) {
    constructor(context: Context, sessions: SessionStore) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), SessionSealer(sessions))

    @Volatile private var registrations: List<PushRegistration> = load()
    @Volatile private var stops: Map<String, Stop> = loadStops()
    @Volatile private var sides: Map<String, PushSide> = loadSides()

    private val _changes = MutableStateFlow(0L)

    /** Moves on with every write, for whatever shows the state (Settings). */
    val changes: StateFlow<Long> = _changes

    private fun changed() { _changes.value = _changes.value + 1 }

    private fun loadSides(): Map<String, PushSide> = prefs.getString(KEY_SIDES, null)?.let {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), PushSide.serializer()), it) }.getOrNull()
    } ?: emptyMap()

    private fun load(): List<PushRegistration> = prefs.getString(KEY_REGISTRATIONS, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(PushRegistration.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun loadStops(): Map<String, Stop> = prefs.getString(KEY_STOPS, null)?.let {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), Stop.serializer()), it) }.getOrNull()
    } ?: emptyMap()

    // ---- reading ----------------------------------------------------------------------------

    fun all(): List<PushRegistration> = registrations

    /** The distributor the person chose, if any. */
    val distributor: String? get() = prefs.getString(KEY_DISTRIBUTOR, null)

    /**
     * The chosen distributor's signing certificates as they were when it was chosen
     * ([UnifiedPush.signers]); null when none was read. Another app installed under the same
     * package name is not the one chosen.
     */
    val distributorSigners: String? get() = prefs.getString(KEY_DISTRIBUTOR_SIGNERS, null)

    /** The saved servers a `DELETE /me/push` is owed to: it did not reach them, and is sent again. */
    fun owedDeletes(): Set<String> = prefs.getStringSet(KEY_OWED_DELETES, null).orEmpty()

    fun registration(serverId: String, distributor: String): PushRegistration? =
        registrations.firstOrNull { it.serverId == serverId && it.distributor == distributor }

    fun byToken(token: String): PushRegistration? = registrations.firstOrNull { it.token == token }

    /** The distributor [token] belongs to, or null for a token this install never made. */
    fun distributorOf(token: String): String? = byToken(token)?.distributor

    /** Why push stopped for [serverId] by itself, until it is set up again. */
    fun stop(serverId: String): Stop? = stops[serverId]

    /** Whether the person switched push notifications on (off until they do). */
    val enabled: Boolean get() = prefs.getBoolean(KEY_ON, false)

    /** What [serverId] last said about its registration, and what is owed to it. */
    fun side(serverId: String): PushSide = sides[serverId] ?: PushSide()

    /** [registration]'s keys, opened; null when the seal no longer opens or holds something else. */
    fun keys(registration: PushRegistration): WebPush.Keys? = runCatching {
        val scalar = B64.decode(sealer.open(registration.sealedKey)) ?: return null
        val auth = B64.decode(sealer.open(registration.sealedAuth)) ?: return null
        val public = B64.decode(registration.p256dh) ?: return null
        if (scalar.size != 32) return null
        WebPush.Keys(P256.privateKey(scalar), public, auth)
    }.getOrNull()

    /** The posts shown lately ([ceo.villa.matty.service.ShownPosts]): at most 500, none older than a day. */
    val shownKept: Kept get() = kept(KEY_SHOWN)

    /** The latest clear of each conversation ([Clears]), the last 256. */
    val clearsKept: Kept get() = kept(KEY_CLEARS)

    private fun kept(key: String) = object : Kept {
        override fun read(): String? = prefs.getString(key, null)
        override fun write(text: String) = prefs.edit().putString(key, text).apply()
    }

    // ---- writing ----------------------------------------------------------------------------

    /** [distributor] as the chosen one, with its [signers] as read now (null: not known). */
    @Synchronized
    fun rememberDistributor(distributor: String?, signers: String? = null) {
        prefs.edit().apply {
            if (distributor == null) remove(KEY_DISTRIBUTOR) else putString(KEY_DISTRIBUTOR, distributor)
            if (distributor == null || signers == null) remove(KEY_DISTRIBUTOR_SIGNERS) else putString(KEY_DISTRIBUTOR_SIGNERS, signers)
        }.commit()
        changed()
    }

    /** A `DELETE /me/push` is owed to [serverId] until [paidDelete]. Written before it is tried. */
    @Synchronized
    fun oweDelete(serverId: String) {
        val owed = owedDeletes()
        if (serverId !in owed) prefs.edit().putStringSet(KEY_OWED_DELETES, owed + serverId).commit()
    }

    @Synchronized
    fun paidDelete(serverId: String) {
        val owed = owedDeletes()
        if (serverId in owed) prefs.edit().putStringSet(KEY_OWED_DELETES, owed - serverId).commit()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).commit()
        changed()
    }

    /** [serverId]'s side, changed by [change] (read and written in one step). */
    @Synchronized
    fun updateSide(serverId: String, change: (PushSide) -> PushSide): PushSide {
        val next = change(side(serverId))
        persistSides(sides + (serverId to next))
        return next
    }

    /** Every note of why push stopped, as when the person sets it up again. */
    @Synchronized
    fun clearStops() {
        if (stops.isNotEmpty()) persistStops(emptyMap())
    }

    /**
     * [event] applied to the registration that holds [token] now, and written, in one step: the
     * receiver and the app may both be changing it. Null for a token no registration holds.
     */
    @Synchronized
    fun step(token: String, event: PushRegistration.Event): Pair<PushRegistration, PushRegistration.Step>? {
        val before = byToken(token) ?: return null
        val step = before.on(event, ::newToken)
        apply(before, step)
        return before to step
    }

    /** A token for a new registration: a random UUID. */
    fun newToken(): String = UUID.randomUUID().toString()

    /**
     * A new registration for [serverId] at [distributor] with fresh keys and token, saved in
     * place of any earlier one for the same pair (whose token the caller UNREGISTERs). Throws when
     * the Keystore cannot seal right now.
     */
    @Synchronized
    fun create(serverId: String, distributor: String, vapid: String, description: String): PushRegistration {
        val keys = WebPush.newKeys(random)
        val registration = PushRegistration(
            serverId = serverId,
            distributor = distributor,
            token = newToken(),
            vapid = vapid,
            description = description,
            p256dh = keys.p256dh,
            sealedKey = sealer.seal(B64.url(P256.scalar(keys.private))),
            sealedAuth = sealer.seal(keys.authText),
        )
        save(registration)
        return registration
    }

    /** Saves [registration] in place of the one for its saved server and distributor. */
    @Synchronized
    fun save(registration: PushRegistration) {
        persist(registrations.filterNot { it.serverId == registration.serverId && it.distributor == registration.distributor } + registration)
        if (stops.containsKey(registration.serverId)) persistStops(stops - registration.serverId)
    }

    /**
     * Writes what [step] left of [before]: the new state, or nothing (token and keys deleted).
     * A [PushRegistration.Effect.Stopped] among its effects is remembered for Settings.
     */
    @Synchronized
    fun apply(before: PushRegistration, step: PushRegistration.Step) {
        val rest = registrations.filterNot { it.serverId == before.serverId && it.distributor == before.distributor }
        persist(if (step.registration == null) rest else rest + step.registration)
        step.effects.filterIsInstance<PushRegistration.Effect.Stopped>().lastOrNull()?.let { persistStops(stops + (before.serverId to it.why)) }
    }

    /** Deletes every registration of [serverId] (its tokens and keys) and what was noted for it, a DELETE owed included. */
    @Synchronized
    fun forget(serverId: String) {
        persist(registrations.filterNot { it.serverId == serverId })
        if (stops.containsKey(serverId)) persistStops(stops - serverId)
        if (sides.containsKey(serverId)) persistSides(sides - serverId)
        paidDelete(serverId)
    }

    /** Deletes [registration] alone (its token and keys), keeping what was noted for its server. */
    @Synchronized
    fun remove(registration: PushRegistration) {
        persist(registrations.filterNot { it.serverId == registration.serverId && it.distributor == registration.distributor })
    }

    /** What every server said: forgotten when push is switched off or set up again. A DELETE owed stays owed. */
    @Synchronized
    fun forgetSides() {
        if (sides.isNotEmpty()) persistSides(emptyMap())
    }

    private fun persist(list: List<PushRegistration>) {
        registrations = list
        prefs.edit().putString(KEY_REGISTRATIONS, json.encodeToString(ListSerializer(PushRegistration.serializer()), list)).commit()
        changed()
    }

    private fun persistStops(map: Map<String, Stop>) {
        stops = map
        prefs.edit().putString(KEY_STOPS, json.encodeToString(MapSerializer(String.serializer(), Stop.serializer()), map)).commit()
        changed()
    }

    private fun persistSides(map: Map<String, PushSide>) {
        sides = map
        prefs.edit().putString(KEY_SIDES, json.encodeToString(MapSerializer(String.serializer(), PushSide.serializer()), map)).commit()
        changed()
    }

    companion object {
        const val PREFS = "matty_push"
        private const val KEY_REGISTRATIONS = "registrations"
        private const val KEY_DISTRIBUTOR = "distributor"
        private const val KEY_DISTRIBUTOR_SIGNERS = "distributor_signers"
        private const val KEY_OWED_DELETES = "owed_deletes"
        private const val KEY_STOPS = "stops"
        private const val KEY_SIDES = "sides"
        private const val KEY_ON = "on"
        private const val KEY_SHOWN = "shown"
        private const val KEY_CLEARS = "clears"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: PushStore? = null

        /** The process's one store, read from disk the first time it is asked for. */
        fun of(context: Context): PushStore = instance ?: synchronized(this) {
            instance ?: PushStore(context.applicationContext, context.container.sessionStore).also { instance = it }
        }
    }
}
