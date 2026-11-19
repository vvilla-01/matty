package ceo.villa.matty.push

import android.content.Intent

/** A distributor's broadcast, as read from its intent and nothing more. */
class Inbound(
    val action: String?,
    val token: String?,
    val id: String? = null,
    val endpoint: String? = null,
    val reason: String? = null,
    val bytes: ByteArray? = null,
    val newDistributor: String? = null,
    val useDistributor: String? = null,
) {
    override fun toString() = "Inbound($action)"

    companion object {
        /**
         * Reads the action and the token, and the other extras the protocol gives only when the
         * action is one Matty hears and [known] says the token is one this install made: nothing
         * else is read from somebody else's broadcast. One of the wrong type reads as missing.
         */
        fun of(intent: Intent, known: (String) -> Boolean): Inbound? =
            read(intent.action, { runCatching { intent.getStringExtra(it) }.getOrNull() }, { runCatching { intent.getByteArrayExtra(it) }.getOrNull() }, known)

        /** [of] with the extras read through [string] and [bytes]. */
        fun read(action: String?, string: (String) -> String?, bytes: (String) -> ByteArray?, known: (String) -> Boolean): Inbound? {
            if (action == null || action !in UnifiedPush.RECEIVED) return null
            val token = string(UnifiedPush.EXTRA_TOKEN)?.takeIf(UnifiedPush::isToken) ?: return null
            if (!known(token)) return null
            return Inbound(
                action = action,
                token = token,
                id = string(UnifiedPush.EXTRA_ID),
                endpoint = string(UnifiedPush.EXTRA_ENDPOINT),
                reason = string(UnifiedPush.EXTRA_REASON),
                bytes = bytes(UnifiedPush.EXTRA_BYTES_MESSAGE),
                newDistributor = string(UnifiedPush.EXTRA_NEW_DISTRIBUTOR),
                useDistributor = string(UnifiedPush.EXTRA_USE_DISTRIBUTOR),
            )
        }
    }
}

/**
 * What [PushReceiver] lets through, decided before it takes a wake lock or leaves the main thread:
 * the receiver is exported (any app can send to it), so the token is the only guard. An unknown
 * token, a token of a distributor that is no longer the chosen one, and anything with a missing or
 * out-of-size extra are ignored without a word.
 */
object PushGate {
    sealed interface Admitted {
        val token: String
        val distributor: String
        /** The distributor's message id, to be acknowledged (MESSAGE_ACK). */
        val id: String?

        /** A change to the token's registration. An UNREGISTERED's migration target is as named, not yet checked. */
        class Changed(override val token: String, override val distributor: String, override val id: String?, val event: PushRegistration.Event) : Admitted {
            override fun toString() = "Changed($event)"
        }

        /** A push to open. */
        class Message(override val token: String, override val distributor: String, override val id: String?, val body: ByteArray) : Admitted {
            override fun toString() = "Message(${body.size} bytes)"
        }
    }

    /**
     * [inbound] as something to act on, or null. [distributorOf] gives a known token's distributor
     * (the in-memory registrations); [chosen] is the distributor the person chose.
     */
    fun admit(inbound: Inbound, distributorOf: (String) -> String?, chosen: String?): Admitted? {
        val action = inbound.action ?: return null
        if (action !in UnifiedPush.RECEIVED) return null
        val token = inbound.token?.takeIf(UnifiedPush::isToken) ?: return null
        val distributor = distributorOf(token) ?: return null
        if (chosen == null || distributor != chosen) return null
        val id = inbound.id?.takeIf { it.isNotEmpty() }
        if (id != null && id.utf8Size() > UnifiedPush.MAX_ID_BYTES) return null
        fun changed(event: PushRegistration.Event) = Admitted.Changed(token, distributor, id, event)
        return when (action) {
            UnifiedPush.ACTION_MESSAGE -> {
                val body = inbound.bytes ?: return null
                if (body.isEmpty() || body.size > UnifiedPush.MAX_MESSAGE_BYTES) return null
                Admitted.Message(token, distributor, id, body)
            }
            UnifiedPush.ACTION_NEW_ENDPOINT -> {
                val endpoint = inbound.endpoint ?: return null
                if (endpoint.isEmpty() || endpoint.utf8Size() > UnifiedPush.MAX_ENDPOINT_BYTES) return null
                changed(PushRegistration.Event.NewEndpoint(endpoint))
            }
            UnifiedPush.ACTION_REGISTRATION_FAILED -> changed(PushRegistration.Event.Failed(FailReason.of(inbound.reason)))
            UnifiedPush.ACTION_UNREGISTERED -> {
                val named = (inbound.newDistributor ?: inbound.useDistributor)
                    ?.takeIf { it.isNotBlank() && it.length <= UnifiedPush.MAX_PACKAGE_CHARS }
                changed(PushRegistration.Event.Unregistered(named))
            }
            UnifiedPush.ACTION_TEMP_UNAVAILABLE -> changed(PushRegistration.Event.TempUnavailable)
            else -> null
        }
    }

    /**
     * What a push [body] for [registration] comes to for the saved server signed in as [userId]:
     * [Arrival.Drop] when its keys no longer open, or it does not decrypt or parse.
     */
    fun open(store: PushStore, registration: PushRegistration, body: ByteArray, userId: String, now: Long): Arrival {
        val keys = store.keys(registration) ?: return Arrival.Drop
        val plain = WebPush.decrypt(body, keys) ?: return Arrival.Drop
        val payload = PushPayload.parse(plain) ?: return Arrival.Drop
        return Arrival.of(payload, userId, now)
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
}
