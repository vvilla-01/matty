package ceo.villa.matty.ui.lock

import ceo.villa.matty.data.session.ServerPolicy

/**
 * How long a server stays unlocked while the app is away from it (in the background, or with
 * another server on screen): five minutes, as in Mattermost's own app (PROMPT_AUTH_AFTER).
 */
const val UNLOCK_KEPT_MS = 5 * 60_000L

/**
 * Which servers are unlocked, by the clock of the phone since it started (which no setting can
 * turn back). It lives in memory only, so a process that died starts with every server locked.
 * Nothing here knows whether a server asks for the unlock: the gate asks it only of those that do.
 */
class Unlocks(private val keptMs: Long = UNLOCK_KEPT_MS) {
    /** When each unlocked server was last on screen. */
    private val seen = HashMap<String, Long>()
    /** The server on screen now, unlocked, with the app in front; its time doesn't run. */
    private var open: String? = null

    /** Whether [server] needs unlocking before it is shown at [now]. */
    @Synchronized
    fun isLocked(server: String, now: Long): Boolean {
        if (server == open) return false
        val at = seen[server] ?: return true
        // A clock that went backwards is no proof of anything.
        return now < at || now - at >= keptMs
    }

    /** [server] comes on screen at [now]: true while it is locked (and then it stays locked until [unlocked]). */
    @Synchronized
    fun shown(server: String, now: Long): Boolean {
        val locked = isLocked(server, now)
        if (locked) seen.remove(server) else {
            // The server it replaces is told it left only after this (its gate goes later): its
            // time starts now, not at whatever it was last stamped with.
            open?.takeIf { it != server }?.let { seen[it] = now }
            open = server
        }
        return locked
    }

    /** The person proved who they are with [server] on screen. */
    @Synchronized
    fun unlocked(server: String, now: Long) {
        seen[server] = now
        open = server
    }

    /**
     * [server] left the screen at [now] (the app went to the background, or another server took
     * its place): its time starts running. Named, because the next server may already be shown.
     */
    @Synchronized
    fun hidden(server: String, now: Long) {
        if (open != server) return
        seen[server] = now
        open = null
    }
}

/** Whether the window is kept out of captures: when the server says so, and always while its lock shows. */
fun windowSecure(policy: ServerPolicy, locked: Boolean): Boolean = policy.blocksCapture || locked

/** What a pending intent does while the server on screen is locked. */
sealed interface WhileLocked {
    /** It waits for the unlock, then goes on as it would have. */
    data object Waits : WhileLocked
    /** Matty's own notification from another signed-in server: that server is switched to now (its own lock, if any, then shows). */
    data class Switch(val serverId: String) : WhileLocked
}

/**
 * Nothing pending reaches a locked server's content. The one thing done at once is what the
 * unlocked app would do first anyway: a notification of another server switches to it, so the
 * person unlocks the server they meant to open, not the one in front.
 */
fun whileLocked(ownNotification: Boolean, serverId: String?, current: String, signedIn: Set<String>): WhileLocked =
    if (ownNotification && serverId != null && serverId != current && serverId in signedIn) WhileLocked.Switch(serverId)
    else WhileLocked.Waits

/**
 * The ways off a server's lock screen other than unlocking, as in Mattermost's own app: to another
 * signed-in server ([switchTo], in the order they are kept; none when it is the only one), and
 * signing out of the locked server, which is always there (it is the only way out on a phone
 * with no screen lock).
 */
data class LockExits(val switchTo: List<String>, val signOut: Boolean = true)

fun lockExits(current: String, signedIn: List<String>): LockExits = LockExits(signedIn.filter { it != current }.distinct())

/**
 * What the lock screen's list says under another server's name: the account and the address, as
 * the You tab does, to tell two servers of one name apart. A server that asks for its own unlock
 * shows no more here than its own lock screen would: its name alone.
 */
fun lockSheetHint(asksUnlock: Boolean, username: String, url: String): String? =
    if (asksUnlock) null else "@$username · ${url.removePrefix("https://")}"

/**
 * Which server's unlock a pending intent (a notification tap, a share) waits for once the person
 * has left that server's lock by an exit. Without it the next server would act on it: a
 * notification of the locked server would switch straight back to it, and a share would go to
 * the other server. Held by identity: an intent that arrives later is new, and goes on as usual.
 */
class WaitingBehindLock<T : Any> {
    private var item: T? = null
    private var server: String? = null

    /** Whether [pending] waits for another server's unlock than [current]'s. */
    @Synchronized
    fun waitsElsewhere(pending: T, current: String): Boolean = item === pending && server != current

    /** The person switched away from [locked]'s lock: [pending] keeps waiting for it (or for the server it already waited for). */
    @Synchronized
    fun switchedAway(pending: T?, locked: String) {
        if (pending == null || item === pending) return
        item = pending
        server = locked
    }

    /**
     * The person signed out of [locked] from its lock: true when [pending] was waiting for it, and
     * is to be dropped with it (one waiting for another server's unlock is kept).
     */
    @Synchronized
    fun signedOut(pending: T?, locked: String): Boolean {
        if (pending == null) return false
        if (item === pending && server != locked) return false
        item = null
        server = null
        return true
    }

    /** [pending] went on (or was dropped) with its own server on screen. */
    @Synchronized
    fun done(pending: T) {
        if (item === pending) { item = null; server = null }
    }
}
