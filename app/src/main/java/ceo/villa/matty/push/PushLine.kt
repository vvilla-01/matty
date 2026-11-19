package ceo.villa.matty.push

/** Everything Settings knows about push for the server in view, gathered by [PushCenter.facts]. */
data class PushFacts(
    /** Whether the person switched push on. */
    val on: Boolean,
    /** The distributors installed now. */
    val installed: List<String>,
    /** The distributor the person chose, if any, and what it is called on this phone. */
    val chosen: String?,
    val appName: String,
    /** Whether Matty may post notifications at all (the permission and its messages channel). */
    val permitted: Boolean,
    /** Why push stopped for this server by itself, if it did. */
    val stop: Stop?,
    val side: PushSide,
    /** This server's registration at the chosen distributor. */
    val registration: PushRegistration?,
    /** How many saved servers push works for, and how many there are. */
    val working: Int,
    val servers: Int,
    /**
     * Whether the chosen distributor is still installed, signed as when it was chosen, whether or
     * not it takes REGISTER now (one that needs setting up switches its receiver off).
     */
    val chosenInstalled: Boolean = false,
)

/** The one line of state Settings shows for push, and what it offers beside it. */
data class PushLine(val text: String, val action: Action = Action.NONE, val warning: Boolean = false, val working: Boolean = false) {
    enum class Action { NONE, WHAT_IS, SET_UP_AGAIN, SEND_TEST, TRY_AGAIN }

    companion object {
        const val WHAT_IS_URL = "https://unifiedpush.org/"

        /** How long "Sent: …" stays when the test push does not arrive first. */
        const val TEST_SHOWN_MS = 10_000L

        const val TEST_SENT = "Sent: the test notification should arrive in a moment."

        /**
         * Whether the test asked for at [sentAt] (0: none asked, or the server did not take it) is
         * overdue at [now]: [TEST_SHOWN_MS] went by and no test arrived since ([arrivedAt]).
         */
        fun testMissing(sentAt: Long, arrivedAt: Long, now: Long): Boolean =
            sentAt != 0L && arrivedAt < sentAt && now - sentAt >= TEST_SHOWN_MS

        /**
         * What shows under "Send a test" for the test asked for at [sentAt], at [now]: [TEST_SENT]
         * for [TEST_SHOWN_MS], then, while no test has arrived since ([arrivedAt]), a warning
         * naming [appName]; null once a test arrived, or when none was asked for.
         */
        fun testSaid(sentAt: Long, arrivedAt: Long, now: Long, appName: String): PushLine? = when {
            sentAt == 0L || arrivedAt >= sentAt -> null
            testMissing(sentAt, arrivedAt, now) ->
                PushLine("The test notification has not arrived: open ${appName.ifBlank { "your UnifiedPush app" }} and make sure it is connected", warning = true)
            else -> PushLine(TEST_SENT)
        }

        /** The line for [f]; null when there is nothing to say (push off and a UnifiedPush app installed). */
        fun of(f: PushFacts): PushLine? {
            val app = f.appName.ifBlank { "Your UnifiedPush app" }
            // Installed and signed as chosen, but taking no REGISTER: a distributor that needs setting
            // up switches its receiver off. Matty keeps its registrations meanwhile; "Set up again"
            // opens it when there is nothing else to choose from.
            val notSetUp = f.chosen != null && f.chosenInstalled && f.chosen !in f.installed
            val removed = when {
                notSetUp -> PushLine("$app isn't set up for UnifiedPush right now: open it, then Set up again", Action.SET_UP_AGAIN, warning = true)
                // With none installed, setting up again has nothing to choose from.
                f.installed.isEmpty() -> PushLine("Your UnifiedPush app was removed", Action.WHAT_IS, warning = true)
                // Taken as gone once, and installed and taking REGISTER again since: only Matty's registration is gone.
                f.stop == Stop.REMOVED && f.chosenInstalled -> PushLine("$app no longer has Matty's registration: Set up again", Action.SET_UP_AGAIN, warning = true)
                else -> PushLine("Your UnifiedPush app was removed", Action.SET_UP_AGAIN, warning = true)
            }
            if (f.installed.isEmpty()) {
                return when {
                    f.on && f.chosen != null -> removed
                    notSetUp -> PushLine("$app isn't set up for UnifiedPush right now: open it first")
                    else -> PushLine("No UnifiedPush app is installed on this phone", Action.WHAT_IS)
                }
            }
            if (!f.on) return null
            if (f.stop == Stop.REMOVED || (f.chosen != null && f.chosen !in f.installed)) return removed
            if (!f.permitted) return PushLine("Notifications are off for Matty", warning = true)
            when (f.side.offer) {
                PushSide.OFFER_NONE, PushSide.OFFER_OLD -> return PushLine("This server doesn't offer push notifications: it needs Matty Plugin 4.1.0")
                PushSide.OFFER_OFF -> return PushLine(f.side.reason.trim().ifEmpty { "Push notifications are switched off on this server." })
            }
            if (f.stop == Stop.STOPPED) return PushLine("$app stopped delivering for Matty", Action.SET_UP_AGAIN, warning = true)
            val r = f.registration ?: return if (f.side.unreachable) PushLine("This server can't be reached right now: Matty asks again when you come back here or the network changes", warning = true)
            else PushLine("Asking $app…")
            if (r.unavailable) return PushLine("$app is temporarily unavailable", warning = true)
            when (r.phase) {
                PushRegistration.Phase.FAILED -> return when (r.failure) {
                    FailReason.ACTION_REQUIRED -> PushLine("Open $app and finish setting it up", warning = true)
                    FailReason.NETWORK -> PushLine("$app can't reach its push server: trying again when the network is back", warning = true)
                    else -> PushLine("$app did not register Matty", warning = true)
                }
                PushRegistration.Phase.ASKING -> return PushLine("Asking $app…")
                else -> Unit
            }
            // The server's own sentence for an address it would not take (sent again on Try again,
            // once its admin has changed what it takes), or for the app check.
            f.side.refusal.trim().takeIf { it.isNotEmpty() }?.let {
                return PushLine(it, if (f.side.rejected.isNotEmpty()) Action.TRY_AGAIN else Action.NONE, warning = true)
            }
            val host = f.side.host
            when (f.side.state) {
                "refused" -> {
                    val ntfy = f.chosen.orEmpty().startsWith(NTFY) || host.startsWith("ntfy.") || host.contains(".ntfy.")
                    val more = if (ntfy) ". If it is ntfy, it may need to allow anonymous publishing to up* topics" else ""
                    return PushLine("Your push server (${host.ifEmpty { "unknown" }}) refused this server's messages$more", Action.TRY_AGAIN, warning = true)
                }
                "failing" -> {
                    val named = host.ifEmpty { "unknown" }
                    // A push server that answers is reached: it only says no.
                    val head = if (f.side.kind == "answer") "Your push server ($named) doesn't take this server's messages" else "This server can't reach your push server ($named)"
                    return PushLine(head + failingWhy(f.side.kind), Action.TRY_AGAIN, warning = true)
                }
            }
            // The address has not reached the server yet; it is sent again by itself, and on Try again.
            if (f.side.owesPut) return PushLine("Couldn't register with this server yet: trying again", Action.TRY_AGAIN, warning = true)
            // Try again sends the same address once more: a new check message, waited for as the first was.
            if (f.side.unheard) return PushLine("$app has not delivered the check message: open it and make sure it is connected", Action.TRY_AGAIN, warning = true)
            // Working only on the server's word: a hello under way may yet be refused.
            if (r.isConfirmed && f.side.state == "confirmed") {
                val through = "Working through $app" + if (host.isNotEmpty()) " ($host)" else ""
                val count = if (f.servers > 1) ", for ${f.working} of your ${f.servers} servers" else ""
                return PushLine(through + count, Action.SEND_TEST, working = true)
            }
            return PushLine("Waiting for the first push")
        }

        private fun failingWhy(kind: String) = when (kind) {
            "address" -> ": the server's address rules don't allow it"
            "certificate" -> ": its certificate did not verify"
            "answer" -> ": it answers, but not with a yes"
            else -> ""
        }

        /** ntfy's package, whose servers often refuse anonymous publishing. */
        private const val NTFY = "io.heckel.ntfy"
    }
}
