package ceo.villa.matty

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The value only this install knows, carried by the intents Matty makes for itself (a
 * notification's tap). MainActivity is exported, so any app can send it an intent: one asking to
 * open a channel (which also picks where a pending share goes, or switches the server) is only
 * followed when it carries this.
 */
internal object IntentKey {
    /** A new value: 32 random bytes. */
    fun create(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** Whether [given] is [key], compared in constant time; nothing given is never it. */
    fun matches(given: String?, key: String): Boolean =
        given != null && key.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), key.toByteArray())

    /**
     * Whether an intent with [action] that carries [given] is ignored (taken as the app simply
     * being opened): opening a channel needs the key; anything else (a share, the launcher)
     * is open to every app as it always was.
     */
    fun ignored(action: String?, given: String?, key: String): Boolean =
        action == MainActivity.ACTION_OPEN_CHANNEL && !matches(given, key)
}
