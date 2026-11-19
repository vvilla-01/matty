package ceo.villa.matty.push

import kotlinx.serialization.Serializable

/** Why a distributor refused a registration (REGISTRATION_FAILED's `reason`). */
@Serializable
enum class FailReason {
    INTERNAL_ERROR, NETWORK, ACTION_REQUIRED, VAPID_REQUIRED;

    companion object {
        /** An unknown or missing reason counts as INTERNAL_ERROR, as the connector library takes it. */
        fun of(reason: String?): FailReason = entries.firstOrNull { it.name == reason } ?: INTERNAL_ERROR
    }
}

/** Why a saved server's registration is gone without the person switching push off. */
@Serializable
enum class Stop {
    /** The distributor sent a plain UNREGISTERED: "<app> stopped delivering for Matty". */
    STOPPED,
    /** The remembered distributor is gone (not installed, or signed by somebody else): "Your UnifiedPush app was removed". */
    REMOVED,
}

/** What the server confirmed for a registration: an equal NEW_ENDPOINT sends the server nothing. */
@Serializable
data class Confirmed(val endpoint: String, val p256dh: String, val vapid: String) {
    override fun toString() = "Confirmed"
}

/**
 * One saved server's registration with one distributor: its own token, keys, and the server's
 * VAPID key and the description it registered with (re-sent byte for byte). The private key and
 * the authentication secret are only ever stored sealed (see [PushStore]).
 */
@Serializable
data class PushRegistration(
    val serverId: String,
    val distributor: String,
    val token: String,
    val vapid: String,
    val description: String,
    /** The public key, base64url: what the server is given as `p256dh`. */
    val p256dh: String,
    val sealedKey: String,
    val sealedAuth: String,
    /** The distributor's latest endpoint for [token]; null until NEW_ENDPOINT. */
    val endpoint: String? = null,
    /** The latest REGISTRATION_FAILED's reason, until the next NEW_ENDPOINT. */
    val failure: FailReason? = null,
    /** Whether a token that failed before it had an endpoint was already registered again at once, since the last ask. */
    val replacedToken: Boolean = false,
    /** TEMP_UNAVAILABLE, until the next NEW_ENDPOINT. */
    val unavailable: Boolean = false,
    val confirmed: Confirmed? = null,
    /**
     * A REGISTER of [token] was held back because the chosen distributor took none (it needs
     * setting up): sent once it takes REGISTER again, until then or the next NEW_ENDPOINT.
     */
    val owesRegister: Boolean = false,
) {
    /** Where it stands with its distributor, for Settings. */
    val phase: Phase get() = when {
        unavailable -> Phase.UNAVAILABLE
        endpoint == null && failure != null -> Phase.FAILED
        endpoint == null -> Phase.ASKING
        else -> Phase.DELIVERING
    }

    /** Whether the server has confirmed exactly this endpoint, these keys and this VAPID key. */
    val isConfirmed: Boolean get() = endpoint != null && confirmed == Confirmed(endpoint, p256dh, vapid)

    /**
     * Whether Matty coming to the front REGISTERs this again: a REGISTER held back, or one never
     * answered or refused (not for want of a network, which waits for the network to come back).
     */
    val asksOnReturn: Boolean get() = owesRegister || (endpoint == null && failure != FailReason.NETWORK)

    /** Never the token, the endpoint or a key. */
    override fun toString() = "PushRegistration(server=$serverId, distributor=$distributor, phase=$phase)"

    enum class Phase { ASKING, FAILED, UNAVAILABLE, DELIVERING }

    /** Everything that changes a registration. */
    sealed interface Event {
        /**
         * REGISTER again: the app starts (the first `ensureActive` of a process), Settings looks at
         * a registration that has no endpoint yet, Matty comes to the front ([asksOnReturn]), or the
         * network a failure waited for is back.
         */
        data object Started : Event
        data class NewEndpoint(val endpoint: String) : Event {
            override fun toString() = "NewEndpoint"
        }
        data class Failed(val reason: FailReason) : Event
        /** UNREGISTERED; [moveTo] is an installed distributor it names (see [UnifiedPush.migrationTarget]). */
        data class Unregistered(val moveTo: String?) : Event
        /** TEMP_UNAVAILABLE; a fallback it names is ignored. */
        data object TempUnavailable : Event
        /** A push came through: whatever made the distributor temporarily unavailable is over. */
        data object Delivered : Event
        /** The remembered distributor is gone: not installed, or signed by somebody else. */
        data object DistributorRemoved : Event
        /** The REGISTER this asked for was not sent: the chosen distributor takes none right now. */
        data object HeldBack : Event
        /** The server's VAPID key changed (`GET /me/push` said `refresh`): register afresh with it. */
        data class VapidChanged(val vapid: String) : Event
        /** The server confirmed [endpoint] with this registration's keys and VAPID key. */
        data class ServerConfirmed(val endpoint: String) : Event {
            override fun toString() = "ServerConfirmed"
        }
        /** The person switched push off. */
        data object SwitchedOff : Event
    }

    /** What an event asks to be done, in this order. */
    sealed interface Effect {
        /** REGISTER [token] at this registration's distributor with its VAPID key and description. */
        data class Register(val token: String) : Effect {
            override fun toString() = "Register"
        }
        /** UNREGISTER [token] at [distributor]. */
        data class Unregister(val distributor: String, val token: String) : Effect {
            override fun toString() = "Unregister($distributor)"
        }
        /** Give the server [endpoint] (`PUT /me/push`). */
        data class SendEndpoint(val endpoint: String) : Effect {
            override fun toString() = "SendEndpoint"
        }
        /** `DELETE /me/push` for this saved server. */
        data object DeleteAtServer : Effect
        /** A fresh registration for this saved server at [distributor] (new token, new keys), REGISTERed there. */
        data class MoveTo(val distributor: String) : Effect
        /** Remember why push stopped for this saved server, for Settings. */
        data class Stopped(val why: Stop) : Effect
    }

    /** The registration after an event ([registration] null: its token and keys are deleted) and what to do. */
    data class Step(val registration: PushRegistration?, val effects: List<Effect>)

    /**
     * This registration after [event]. Pure: [newToken] makes a token when one is replaced.
     * Keys never change here; a registration that moves gets new ones through [Effect.MoveTo].
     */
    fun on(event: Event, newToken: () -> String): Step = when (event) {
        Event.Started -> Step(copy(replacedToken = false, owesRegister = false), listOf(Effect.Register(token)))
        is Event.NewEndpoint -> {
            val next = copy(endpoint = event.endpoint, failure = null, unavailable = false, owesRegister = false)
            Step(next, if (next.isConfirmed) emptyList() else listOf(Effect.SendEndpoint(event.endpoint)))
        }
        is Event.Failed -> when {
            // A token that has had an endpoint keeps it: the reason is shown, the next start retries.
            endpoint != null -> Step(copy(failure = event.reason), emptyList())
            else -> {
                // A refused token is never registered again (AND_3.1): the next REGISTER has a new
                // one, and the refused one is always UNREGISTERed (a distributor may have kept it).
                // The new one goes at once only for an error that a second try may get past, once
                // per ask; a distributor that needs setting up, or a network, is asked again later
                // (by Settings, when Matty comes to the front, at the next start, when the network
                // is back).
                val fresh = newToken()
                val now = !replacedToken && event.reason != FailReason.ACTION_REQUIRED && event.reason != FailReason.NETWORK
                Step(
                    copy(token = fresh, failure = event.reason, replacedToken = replacedToken || now),
                    if (now) listOf(Effect.Unregister(distributor, token), Effect.Register(fresh)) else listOf(Effect.Unregister(distributor, token)),
                )
            }
        }
        is Event.Unregistered -> {
            val target = event.moveTo?.takeIf { it != distributor }
            if (target != null) Step(null, listOf(Effect.Unregister(distributor, token), Effect.MoveTo(target)))
            else Step(null, listOf(Effect.DeleteAtServer, Effect.Stopped(Stop.STOPPED)))
        }
        Event.TempUnavailable -> Step(copy(unavailable = true), emptyList())
        Event.Delivered -> Step(if (unavailable) copy(unavailable = false) else this, emptyList())
        Event.DistributorRemoved -> Step(null, listOf(Effect.DeleteAtServer, Effect.Stopped(Stop.REMOVED)))
        Event.HeldBack -> Step(copy(owesRegister = true), emptyList())
        is Event.VapidChanged -> if (event.vapid == vapid) Step(this, emptyList()) else {
            val fresh = newToken()
            Step(
                copy(token = fresh, vapid = event.vapid, endpoint = null, failure = null, unavailable = false, replacedToken = false, owesRegister = false),
                listOf(Effect.Unregister(distributor, token), Effect.Register(fresh)),
            )
        }
        is Event.ServerConfirmed ->
            if (event.endpoint != endpoint) Step(this, emptyList())
            else Step(copy(confirmed = Confirmed(event.endpoint, p256dh, vapid)), emptyList())
        Event.SwitchedOff -> Step(null, listOf(Effect.DeleteAtServer, Effect.Unregister(distributor, token)))
    }
}
