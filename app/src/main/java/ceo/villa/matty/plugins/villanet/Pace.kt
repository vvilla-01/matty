package ceo.villa.matty.plugins.villanet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Runs [round] one at a time, and never starts two within [gapMs] of each other. Asked for while
 * one is running (or sooner than [gapMs] after one started), exactly one more follows, however
 * many times it was asked: an unlock, the event the server sends about it and a panel opening
 * are a round or two of reads between them, not one each.
 *
 * Not thread-safe: everything happens on [scope]'s one thread.
 */
internal class SingleFlight(
    private val scope: CoroutineScope,
    private val gapMs: Long,
    private val clock: () -> Long,
    private val round: suspend () -> Unit,
) {
    private var job: Job? = null
    private var queued = false

    /** When the last round started, by [clock]; null before the first. */
    var lastStart: Long? = null
        private set

    val running: Boolean get() = job?.isActive == true

    fun request() {
        if (running) { queued = true; return }
        job = scope.launch {
            do {
                lastStart?.let { delay((it + gapMs - clock()).coerceAtLeast(0)) }
                // Whatever was asked up to this moment, this round answers.
                queued = false
                lastStart = clock()
                try {
                    round()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A round that failed is over; the next one asked for still runs.
                }
            } while (queued)
        }
    }

    /** Waits for the round in flight (and the one queued behind it), if any. */
    suspend fun join() { job?.join() }

    fun cancel() {
        job?.cancel()
        job = null
        queued = false
        lastStart = null
    }
}

/**
 * Something to do once, [waitMs] from the first time it is asked for: asked again while it waits,
 * it is still done once. For an answer that says "ask again" (`GET /access` saying "unknown"), so
 * that however often it says so there is at most one read waiting.
 *
 * Not thread-safe: everything happens on [scope]'s one thread.
 */
internal class OnceLater(scope: CoroutineScope, private val waitMs: Long, action: suspend () -> Unit) {
    private val later = Later(scope, action)

    fun request() {
        if (!later.waiting) later.after(waitMs)
    }

    fun cancel() = later.cancel()
}

/**
 * Something to do once, after a wait given each time it is set: set again while it waits, the new
 * wait replaces the old one, so there is at most one waiting. For a moment known only when it is
 * set (the proof of this app that is due in so many seconds).
 *
 * Not thread-safe: everything happens on [scope]'s one thread.
 */
internal class Later(private val scope: CoroutineScope, private val action: suspend () -> Unit) {
    private var job: Job? = null

    /** Whether it is set and still waiting. */
    val waiting: Boolean get() = job?.isActive == true

    fun after(waitMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(waitMs)
            // Done with waiting: what the action learns may set it again.
            job = null
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * The order the snapshots on their way to the screen are shown in. The server pushes a copy for
 * every change between two polls under the same `fetchedAt`, and each push is decoded on its own,
 * so two can finish the wrong way round: they are shown in the order they arrived, never an
 * earlier one after a later one. A read's answer from the same poll as what is shown replaces it
 * only if no push was shown after the read started.
 *
 * Not thread-safe: the main thread only.
 */
internal class ArrivalOrder {
    private var arrived = 0L
    private var shown = 0L

    /** A push has arrived: its place in line. */
    fun pushed(): Long = ++arrived

    /** A read is starting: where in line its answer stands (after every push so far). */
    fun reading(): Long = arrived

    /** Whether the push at [place] may be shown: no push that came after it has been. */
    fun mayShowPush(place: Long): Boolean = place > shown

    /** Whether a read started at [place] may replace what is shown from the same poll. */
    fun mayShowRead(place: Long): Boolean = shown <= place

    /** What stood at [place] has been shown. */
    fun showed(place: Long) { shown = maxOf(shown, place) }
}

/**
 * Whether a read that failed is worth one more try: anything but a refusal (a 4xx), which says
 * what it says and would say it again.
 */
internal fun worthAnotherTry(e: Throwable): Boolean = !(e is VillaNetException && e.status in 400..499)

/**
 * VillaNET's rule for whoever plays a stream: nothing can end a playing stream from outside (the
 * relay only carries its start), so while one plays, on screen or minimised, the player itself
 * asks at least every [everyMs] whether it may still watch. [ask] does the asking and acts on the
 * answer. Returns only when cancelled.
 */
internal suspend fun askWhilePlaying(playing: Flow<String?>, everyMs: Long, ask: suspend () -> Unit) {
    playing.collectLatest { handle ->
        if (handle != null) while (coroutineContext.isActive) { delay(everyMs); ask() }
    }
}

/** Whether the server's VillaNET and this app are a pair: see [pairState]. */
internal enum class PairState { Ok, TooOld, Newer }

/**
 * The plugin and the app are written to one contract and released as a pair. [listed] is the
 * plugin's version as Mattermost's own plugin list gives it ("" when it doesn't say); [contract]
 * is what VillaNET's `/access` said, or null when it hasn't been asked.
 *
 * A version from before the contract this app is written for is too old, and known to be before
 * anything is asked of it: an old VillaNET is never sent requests it would only refuse. After
 * that the contract number decides, whatever the version: a VillaNET that speaks a later contract
 * is newer than this app, and the app shows what it can read.
 */
internal fun pairState(listed: String, contract: Int?): PairState = when {
    listed.isNotBlank() && olderThan(listed, VnAccess.SINCE) -> PairState.TooOld
    contract == null -> PairState.Ok
    contract < VnAccess.CONTRACT -> PairState.TooOld
    contract > VnAccess.CONTRACT -> PairState.Newer
    else -> PairState.Ok
}
