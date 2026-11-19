package ceo.villa.matty.data.session

import ceo.villa.matty.data.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * What a server requires of the app while it is on screen. Matty is social-first and has no such
 * rules of its own: it only honours the ones a server's admin has switched on.
 */
@Serializable
data class ServerPolicy(
    /** No screenshots, screen recordings or casts of the app, and no picture in the app switcher. */
    val blocksCapture: Boolean = false,
    /** The app opens only after the person proves who they are to the phone (fingerprint, face or screen lock). */
    val asksUnlock: Boolean = false,
) {
    /** Whatever either one requires. */
    infix fun or(other: ServerPolicy) =
        ServerPolicy(blocksCapture || other.blocksCapture, asksUnlock || other.asksUnlock)

    companion object {
        /**
         * The policy a server's client config states. Mattermost sends these keys only from an
         * Enterprise-tier licence up (Mattermost Entry included) and leaves them out otherwise, so
         * a missing key is a server that requires nothing. Its own mobile app takes "true" alone as
         * on; any other value is off here too, whatever its case.
         */
        fun fromClientConfig(config: Map<String, String>) = ServerPolicy(
            blocksCapture = config.isOn("MobilePreventScreenCapture"),
            asksUnlock = config.isOn("MobileEnableBiometrics"),
        )

        /**
         * Every source a server's policy is heard from, joined: today Mattermost's own settings
         * only. A second source would be one more `or` here.
         */
        fun of(config: Map<String, String>) = fromClientConfig(config)

        private fun Map<String, String>.isOn(key: String) = this[key]?.trim().equals("true", ignoreCase = true)
    }
}

/**
 * The policy last heard from one server, and the rule for changing it: anything that says more
 * is required is believed at once; only the answer to a config read that succeeded, and that was
 * asked after everything already believed, may say less. A read that fails changes nothing, and
 * an answer that was overtaken (by a newer read, or by a push that required more) can only add.
 */
class PolicyKeeper(remembered: ServerPolicy, private val remember: (ServerPolicy) -> Unit = {}) {
    private val _policy = MutableStateFlow(remembered)
    val policy: StateFlow<ServerPolicy> = _policy

    /** How many config reads were started; each one's ticket is its number. */
    private var asked = 0L
    /** The newest read anything believed so far is known to be current with. */
    private var settled = 0L

    /** A config read is about to start: the ticket to hand back with its answer. */
    @Synchronized
    fun asking(): Long = ++asked

    /** The server answered the read with [ticket] (successfully: a failed read is never reported). */
    @Synchronized
    fun answered(ticket: Long, config: Map<String, String>) {
        val heard = ServerPolicy.of(config)
        if (ticket > settled) {
            settled = ticket
            set(heard)
        } else set(_policy.value or heard)
    }

    /**
     * The server pushed its config (Mattermost's config_changed). It can only add: no read
     * started before it may take away what it required.
     */
    @Synchronized
    fun pushed(config: Map<String, String>) {
        val next = _policy.value or ServerPolicy.of(config)
        if (next == _policy.value) return
        settled = asked
        set(next)
    }

    private fun set(p: ServerPolicy) {
        if (p == _policy.value) return
        _policy.value = p
        remember(p)
    }
}

/** A server that requires two-factor authentication of an account that hasn't set it up. */
object TwoFactor {
    /** What Mattermost answers every call but a few with until the account has it. */
    const val REQUIRED_ID = "api.context.mfa_required.app_error"

    const val SET_UP_ON_WEB =
        "This server needs two-factor authentication on your account. Turn it on in Mattermost on the web (Profile › Security), then try again."

    fun isRequired(e: Throwable?): Boolean = (e as? ApiException)?.let { it.status == 403 && it.errorId == REQUIRED_ID } == true
}
