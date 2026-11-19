package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A copy of the boards this old is asked about again when somebody looks, and while they keep looking. */
internal const val BOARDS_FRESH_MS = 60_000L

/** After the snapshot says the boards have changed, the read waits a random share of this, so everybody's apps don't ask at once. */
internal const val BOARDS_SPREAD_MS = 3_000L

/** A profile open with nothing to show, because the read for it failed, asks again this often. */
internal const val BOARDS_RETRY_MS = 15_000L

/** The boards in hand, if any, and why the last read failed, if it did. */
internal data class BoardsHeld(val boards: VnBoards? = null, val error: Throwable? = null)

/**
 * The app's copy of VillaNET's boards and of everybody's record (`GET /boards`), for the Boards
 * tab and the awards on a profile.
 *
 * Nothing is read until somebody looks: [lookedAt] when a profile or the panel opens, [shown]
 * while the Boards tab is on screen. From then on the copy is kept: it is asked about again
 *
 *  - when somebody looks and it is a minute old ([BOARDS_FRESH_MS]);
 *  - once a minute while the Boards tab stays on screen, since the seconds on a board tick and
 *    nothing says so;
 *  - when the snapshot's one word about the boards ([follow]) is no longer the copy's version: a
 *    place, a medal, a fan or a step has changed. A moment later, not at the same moment as
 *    everybody else's app; and when Matty is next looked at, if nobody is looking now.
 *
 * Every read sends the tag of the copy in hand, so most are answered "that one still". A read
 * that fails keeps what there is to show. With no copy in hand and no Boards tab on screen,
 * nothing is ever read: a reader who never looks at a board or a profile costs the server nothing.
 *
 * Not thread-safe: everything happens on [scope]'s one thread. [clock] is in milliseconds and
 * only ever compared with itself.
 */
internal class BoardsShelf(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val fetch: suspend (tag: String?) -> VillaNetApi.Tagged<VnBoards>?,
    /** Written here, read by the UI. */
    private val held: MutableStateFlow<BoardsHeld>,
    private val visible: () -> Boolean = { true },
    private val jitter: () -> Double = { Math.random() },
    private val freshMs: Long = BOARDS_FRESH_MS,
    private val spreadMs: Long = BOARDS_SPREAD_MS,
    private val retryMs: Long = BOARDS_RETRY_MS,
) {
    private var tag: String? = null

    /** When the server last vouched for the copy in hand, by [clock]; null for never. */
    private var readAt: Long? = null
    private var reading: Job? = null
    private var following: Job? = null
    private var watching: Job? = null
    private var shown = 0

    /** The snapshot's word a read was last started for: one read a word, however often it is said. */
    private var chased: String? = null

    /** The word one read more has been spent on, after an answer that came back behind it. */
    private var readAgainFor: String? = null

    /** The boards changed while nobody was looking: read when somebody next is. */
    private var behind = false

    /** A profile or the panel has opened: asked about again if the copy is old, or behind, or was never read. */
    fun lookedAt() {
        val last = readAt
        if (behind || last == null || clock() - last >= freshMs) read()
    }

    /**
     * A profile is open: [lookedAt] as it opens, and, for as long as it stays open with nothing to
     * show because the read failed, asked again every [retryMs] while Matty is looked at.
     * [mayAsk] is whether the server may be asked anything just now. Returns only when cancelled
     * (the profile closing).
     */
    suspend fun profileOpen(mayAsk: () -> Boolean = { true }) {
        if (mayAsk()) lookedAt()
        while (true) {
            delay(retryMs)
            val now = held.value
            if (now.boards == null && now.error != null && visible() && mayAsk()) lookedAt()
        }
    }

    /** Matty is being looked at again: a copy that fell behind meanwhile is read now, and only such a copy. */
    fun cameBack() {
        if (behind) read()
    }

    /** The Boards tab is on screen ([on]) or has left it. On screen, it reads as [lookedAt] does, and again each minute. */
    fun shown(on: Boolean) {
        shown = (shown + if (on) 1 else -1).coerceAtLeast(0)
        if (shown == 0) { watching?.cancel(); watching = null; return }
        if (!on || watching?.isActive == true) return
        lookedAt()
        watching = scope.launch {
            while (isActive) {
                delay(freshMs)
                // The minute is the schedule: asked whatever the copy's age. (By its age the
                // minute is never quite up, since the answer to the read made as the tab came on
                // landed a moment after this began: going by that, it read every other minute.)
                // Minimised, nobody is reading a board: whoever comes back to it asks.
                if (visible()) read() else behind = true
            }
        }
    }

    /** Reads again whatever is in hand: Try again. */
    fun reload() = read()

    /**
     * The snapshot's word for the boards, each time a snapshot is shown. With a copy in hand whose
     * version it no longer is, the copy is behind: read a moment later if anybody is looking at
     * Matty, otherwise when they next are. With no copy, nobody has asked for the boards, and
     * nothing is read for them.
     */
    fun follow(version: String?) {
        val copy = held.value.boards
        if (copy == null) {
            // Unless the Boards tab is on screen with nothing on it (the server has just come
            // back, or its first answer failed): this is the word it was waiting for.
            if (shown > 0 && !version.isNullOrEmpty() && version != chased) { chased = version; read() }
            return
        }
        if (version.isNullOrEmpty() || version == copy.version || version == chased) return
        chased = version
        if (!visible()) { behind = true; return }
        if (following?.isActive == true) return
        following = scope.launch {
            delay((jitter().coerceIn(0.0, 1.0) * spreadMs).toLong())
            // A read already out may have been answered before the change: wait for it, and read
            // unless it brought the word (or failed, which gives the word back: see [read]).
            reading?.join()
            val wanted = chased ?: return@launch
            if (held.value.boards?.version != wanted) read()
        }
    }

    private fun read() {
        if (reading?.isActive == true) return
        behind = false
        reading = scope.launch {
            val answer = runCatchingCancellable { fetch(if (held.value.boards != null) tag else null) }
            answer.onSuccess { fresh ->
                readAt = clock()
                if (fresh != null) {
                    tag = fresh.tag
                    held.value = BoardsHeld(fresh.value)
                } else if (held.value.error != null) {
                    // "That one still", after a read that failed: what is shown is right again.
                    held.value = held.value.copy(error = null)
                }
                // An answer from before the word this was read for (another node of a cluster
                // had not caught up): the copy is behind, so the next look reads; and the word
                // is given back, once, so the next snapshot that says it reads once more.
                // (Not while a read for the word is already waiting its turn: see [follow].)
                val word = chased
                val copy = held.value.boards
                if (word != null && copy != null && copy.version != word) {
                    behind = true
                    if (following?.isActive != true && readAgainFor != word) { readAgainFor = word; chased = null }
                }
            }.onFailure { e ->
                // What was in hand stays to be looked at; with nothing in hand, the tab says why.
                held.value = held.value.copy(error = e)
                // The word this was read for is given back: the next snapshot that says it, or
                // the next look, reads again.
                val copy = held.value.boards
                if (copy != null && chased != null && copy.version != chased) behind = true
                chased = null
            }
        }
    }

    /**
     * Nothing of the server just left, or of one found too old to ask, stays to be shown. A
     * Boards tab still on screen keeps being watched for, and reads again when there is somebody
     * to ask ([follow]).
     */
    fun clear() {
        reading?.cancel()
        following?.cancel()
        reading = null; following = null
        tag = null
        readAt = null
        chased = null
        readAgainFor = null
        behind = false
        held.value = BoardsHeld()
    }

    /** [clear], and nothing left running: the plugin is letting go of this shelf. */
    fun close() {
        clear()
        watching?.cancel()
        watching = null
        shown = 0
    }
}
