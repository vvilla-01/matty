package ceo.villa.matty.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * What one push says once it is opened: the plaintext JSON Matty Plugin encrypts (its contract's
 * "Push notifications" section). `at` is the server's clock in Unix milliseconds when the push
 * was made.
 */
sealed interface PushPayload {
    /** The Mattermost user the push is for: it must be the saved server's own account. */
    val user: String
    val at: Long

    /**
     * A post Mattermost decided to push. It carries only what the server's push-contents setting
     * lets out; in id-loaded mode [sender] is empty and the phone fetches the rest.
     */
    data class Message(
        override val user: String,
        val post: String,
        val channel: String,
        val root: String,
        val team: String,
        val sender: String,
        val senderName: String,
        val channelName: String,
        val channelType: String,
        val text: String,
        val crt: Boolean,
        val idLoaded: Boolean,
        val fromWebhook: Boolean,
        override val at: Long,
    ) : PushPayload

    /** A conversation was read elsewhere: its notification goes. */
    data class Clear(override val user: String, val channel: String, val root: String, val crt: Boolean, override val at: Long) : PushPayload

    /** The server's check that this address reaches the phone: answered with [nonce]. */
    data class Hello(override val user: String, val nonce: String, override val at: Long) : PushPayload {
        override fun toString() = "Hello(at=$at)"
    }

    /** "Send a test". */
    data class Test(override val user: String, override val at: Long) : PushPayload

    /** The session this registration belonged to is over. */
    data class Ended(override val user: String, override val at: Long) : PushPayload

    companion object {
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The payload [plain] holds, or null for anything else: not UTF-8, not the JSON the
         * contract gives, another `v`, an unknown type, or a field the type needs left empty.
         */
        fun parse(plain: ByteArray): PushPayload? {
            val text = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
            }.getOrNull() ?: return null
            val w = runCatching { json.decodeFromString(Wire.serializer(), text) }.getOrNull() ?: return null
            if (w.v != VERSION || w.user.isEmpty() || w.at <= 0) return null
            return when (w.type) {
                "message" -> if (w.post.isEmpty() || w.channel.isEmpty()) null else Message(
                    w.user, w.post, w.channel, w.root, w.team, w.sender, w.senderName, w.channelName, w.channelType,
                    w.text, w.crt, w.idLoaded, w.fromWebhook, w.at,
                )
                "clear" -> if (w.channel.isEmpty()) null else Clear(w.user, w.channel, w.root, w.crt, w.at)
                "hello" -> if (w.nonce.isEmpty()) null else Hello(w.user, w.nonce, w.at)
                "test" -> Test(w.user, w.at)
                "ended" -> Ended(w.user, w.at)
                else -> null
            }
        }
    }
}

/** The JSON as it travels. */
@Serializable
private data class Wire(
    val v: Int = 0,
    val type: String = "",
    val user: String = "",
    val post: String = "",
    val channel: String = "",
    val root: String = "",
    val team: String = "",
    val sender: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("channel_name") val channelName: String = "",
    @SerialName("channel_type") val channelType: String = "",
    val text: String = "",
    val crt: Boolean = false,
    @SerialName("id_loaded") val idLoaded: Boolean = false,
    @SerialName("from_webhook") val fromWebhook: Boolean = false,
    val nonce: String = "",
    val at: Long = 0,
)

/** What the receiver does with a payload that opened, decided from the payload and the clock alone. */
sealed interface Arrival {
    /** Not for this account, or too old to mean anything now: nothing happens. */
    data object Drop : Arrival

    /** Answer the server's check with [nonce] (`POST /me/push/confirm`). */
    data class Confirm(val nonce: String) : Arrival {
        override fun toString() = "Confirm"
    }

    /** Show "Push notifications are working". */
    data object Tested : Arrival

    /** Show [message]; [silent] when it is older than a few minutes. */
    data class Show(val message: PushPayload.Message, val silent: Boolean) : Arrival

    data class Cleared(val clear: PushPayload.Clear) : Arrival

    data object Ended : Arrival

    companion object {
        /** A hello or a test older than this is dropped. */
        const val CHECK_KEPT_MS = 10 * 60 * 1000L
        /** A message older than this is dropped. */
        const val MESSAGE_KEPT_MS = 24 * 60 * 60 * 1000L
        /** A message older than this is shown without a sound. */
        const val MESSAGE_LOUD_MS = 5 * 60 * 1000L

        /** What to do with [payload] for the saved server signed in as [userId], at [now]. */
        fun of(payload: PushPayload, userId: String, now: Long): Arrival {
            if (userId.isEmpty() || payload.user != userId) return Drop
            val age = (now - payload.at).coerceAtLeast(0)
            return when (payload) {
                is PushPayload.Hello -> if (age > CHECK_KEPT_MS) Drop else Confirm(payload.nonce)
                is PushPayload.Test -> if (age > CHECK_KEPT_MS) Drop else Tested
                is PushPayload.Message -> if (age > MESSAGE_KEPT_MS) Drop else Show(payload, silent = age > MESSAGE_LOUD_MS)
                is PushPayload.Clear -> Cleared(payload)
                is PushPayload.Ended -> Ended
            }
        }
    }
}
