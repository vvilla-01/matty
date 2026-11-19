package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What came of asking VillaNET for one clip (`GET /clips/clip`), as the viewer shows it. */
internal sealed interface ClipOpen {
    data class Found(val clip: VnClip) : ClipOpen

    /**
     * Expired or deleted. Only ever because the server said so: its 410, coded "clip_gone".
     * [words] is the server's own sentence, when it sent one.
     */
    data class Gone(val words: String? = null) : ClipOpen

    /**
     * The reader may not watch (locked since the card was drawn). It opens once they unlock.
     * [words] is the server's own sentence, when it sent one.
     */
    data class Locked(val words: String? = null) : ClipOpen

    /** Anything else, which says nothing about the clip: [words] to show, and worth trying again. */
    data class Failed(val words: String) : ClipOpen
}

/** What a clip the reader may not watch says, when the server sent no sentence; [name] is the server's name for the plugin. */
internal fun clipLockedWords(name: String) = "Unlock $name to watch this clip."
internal const val CLIP_UNREACHED_WORDS = "Couldn't load this clip. Check your connection and try again."
internal const val CLIP_UNPLAYED_WORDS = "This clip couldn't be played just now. Try again in a moment."

/** The shape of a clip's id, as the server makes them and the web panel holds them to: 16 lower-case hex digits. */
private val CLIP_ID = Regex("^[0-9a-f]{16}$")

/**
 * Whether [id] can be a clip's at all. One that can't (a card made or patched while VillaNET
 * wasn't there to check it) is never asked about or opened: the server would refuse it, and with
 * it every real id asked about in the same request.
 */
internal fun isClipId(id: String): Boolean = CLIP_ID.matches(id)

/**
 * Why a clip didn't open. A failure is never read as "this clip is gone": an expired clip, a
 * locked reader and a server that didn't answer are three different things to be told, and only
 * the first is final. A refusal is said in the server's own words.
 */
internal fun clipOpenOf(e: Throwable): ClipOpen = when {
    e is VillaNetException && (e.code == VillaNetApi.CLIP_GONE || e.status == 410) -> ClipOpen.Gone(e.serverMessage)
    e is VillaNetException && (e.code == "locked" || e.code == "watch_only") -> ClipOpen.Locked(e.serverMessage)
    e is VillaNetException && e.serverMessage != null -> ClipOpen.Failed(e.serverMessage)
    else -> ClipOpen.Failed(CLIP_UNREACHED_WORDS)
}

/**
 * Whether the panel draws the clip it was asked to open: not over its lock screen (a locked
 * reader, or a VillaNET that isn't set up), not before the reader's access is known, and never
 * over a VillaNET too old for this app ([outdated] set), which is asked nothing. Until then the
 * clip asked for is kept, and opens once it may. A reader whose access the server couldn't tell
 * ("unknown") sees the tabs, so the viewer opens for them too and says what the server answers.
 */
internal fun clipViewerShown(outdated: String?, access: VnAccess?): Boolean =
    outdated == null && access != null && access.configured && access.access != "locked"

/**
 * The clip asked about again because its video wouldn't play ([playFailed]). If the server still
 * says it's there, the file is what failed (missing, or the store not answering): that is said,
 * with Try again, rather than drawing the player again, which would only fail again.
 */
internal fun clipOpenAfterPlay(opened: ClipOpen, playFailed: Boolean): ClipOpen =
    if (playFailed && opened is ClipOpen.Found) ClipOpen.Failed(CLIP_UNPLAYED_WORDS) else opened

// ---- how long a shared clip's card is good for (the web panel's rules, its clipLogic.ts) -------

/** What the server said about a clip is asked again once it is this old, when its card is next shown. */
internal const val CARD_FRESH_MS = 10 * 60_000L
/** A card the server couldn't answer for is asked about again this soon… */
internal const val CARD_RETRY_MS = 30_000L
/** …and, while it still can't, twice as long each time after that, up to this. */
internal const val CARD_RETRY_MAX_MS = 10 * 60_000L
/** After somebody's vote (or any other change to the clips), the cards on screen ask again at most this often… */
internal const val CARD_VOTES_EVERY_MS = 30_000L
/** …and after a random wait of up to this, so a channel's readers don't all ask at once. */
internal const val CARD_VOTES_SPREAD_MS = 3_000L

internal enum class CardStatus { Loading, Ok, Gone, Error }

/** What was last heard about one clip's card, and when (by the shelf's clock). */
internal data class CardEntry(val status: CardStatus, val at: Long)

/**
 * Whether a card showing now should ask about its clip: always when nothing was heard; never once
 * the server said it has gone (it can't come back), nor while it's being asked already; otherwise
 * once what was heard is old. A failure is tried again sooner than an answer is.
 */
internal fun cardNeedsFetch(entry: CardEntry?, now: Long): Boolean = when (entry?.status) {
    null -> true
    CardStatus.Gone, CardStatus.Loading -> false
    CardStatus.Error -> now - entry.at >= CARD_RETRY_MS
    CardStatus.Ok -> now - entry.at >= CARD_FRESH_MS
}

/**
 * How long to wait before asking again about the cards on screen after the clips changed, or
 * null for not now (no card on show, or nobody looking: asked when Matty is next looked at).
 * [jitter] is a number from 0 to 1.
 */
internal fun cardRefreshDelay(shown: Int, visible: Boolean, lastAt: Long?, now: Long, jitter: Double): Long? {
    if (shown <= 0 || !visible) return null
    val wait = if (lastAt == null) 0 else (lastAt + CARD_VOTES_EVERY_MS - now).coerceAtLeast(0)
    return wait + (jitter.coerceIn(0.0, 1.0) * CARD_VOTES_SPREAD_MS).toLong()
}

/**
 * Whether the clip a card is for has gone, by the card itself: the server says when it goes
 * ([VnClipCard.expiresAt]), so a card in hand turns at that moment without asking again and
 * without the app multiplying days. A card that doesn't say never expires by this.
 */
internal fun cardExpired(card: VnClipCard?, nowMs: Long): Boolean = card != null && card.expiresAt in 1..nowMs

/** The Clips tab's list: null [clips] until a first answer; [error] when the last read failed. */
internal data class ClipList(val clips: List<VnClip>? = null, val error: Throwable? = null)

/**
 * One copy of VillaNET's clips for everything that shows them: the Clips tab's list, the viewer,
 * and the cards of clips shared into chats. A vote made in one shows in the others, and nothing is
 * read twice that hasn't changed.
 *
 * What it holds is good for as long as the snapshot's `clips.version` stays the same ([follow]):
 * the server changes it whenever a clip, a title, a picture or a count does. The list is then
 * read again when it is next looked at (at once if it's on screen), and the cards on screen ask
 * again by the web panel's rules ([cardRefreshDelay]).
 *
 * Cards are asked for in batches of [BATCH], as the web client does. The loop keeps going until
 * nothing is waiting: an id that arrives while a batch is out is in the next one, never left
 * behind.
 *
 * Not thread-safe: everything happens on [scope]'s one thread. [clock] is in milliseconds and
 * only ever compared with itself.
 */
internal class ClipShelf(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val fetchList: suspend () -> List<VnClip>,
    private val fetchCards: suspend (List<String>) -> Map<String, VnClipCard?>,
    /** Written here, read by the UI: by clip id, null for a clip the server says has gone. */
    private val cards: MutableStateFlow<Map<String, VnClipCard?>>,
    private val list: MutableStateFlow<ClipList>,
    private val visible: () -> Boolean = { true },
    private val jitter: () -> Double = { Math.random() },
    private val batchDelayMs: Long = 120,
    private val retryMs: Long = CARD_RETRY_MS,
) {
    private val entries = HashMap<String, CardEntry>()
    private val waiting = LinkedHashSet<String>()
    private val shown = HashMap<String, Int>()
    private var batch: Job? = null
    private var again: Job? = null
    private var retry: Job? = null
    /** How long the next retry of failed cards waits: [retryMs], doubled after each that fails too. */
    private var retryWait = retryMs
    /** Failed cards that waited for Matty to be looked at before being asked about again. */
    private var retryBehind = false
    private var listJob: Job? = null
    private var listShown = 0

    /** `clips.version` as last followed, and the one the list in hand was read under. */
    private var version: String? = null
    /** How many clips there were, and when the newest was made, as last followed. */
    private var count = 0
    private var newest = 0L
    private var listVersion: String? = null
    private var refreshedAt: Long? = null
    private var behind = false

    // ---- the list ----

    /** The Clips tab is on screen ([on]) or has left it. Coming on reads the list if it's behind. */
    fun showList(on: Boolean) {
        listShown = (listShown + if (on) 1 else -1).coerceAtLeast(0)
        if (on) readListIfBehind()
    }

    /** Reads the list again whatever is in hand: a pull to refresh, or Try again. */
    fun reloadList() {
        listVersion = null
        readListIfBehind()
    }

    private fun readListIfBehind() {
        val held = list.value
        if (held.clips != null && held.error == null && listVersion == version) return
        if (listJob?.isActive == true) return
        listJob = scope.launch {
            // Until the answer is for the version that stands when it lands.
            do {
                val asked = version
                val answer = runCatchingCancellable { fetchList() }
                answer.onSuccess { clips ->
                    list.value = ClipList(clips)
                    listVersion = asked
                    // The list is the newest word on every clip in it: a card in hand agrees.
                    val byId = clips.associateBy { it.id }
                    cards.update { held -> held.mapValues { (id, card) -> byId[id]?.let { c -> card?.copy(upvotes = c.upvotes, voted = c.voted, title = c.title) } ?: card } }
                }.onFailure { e ->
                    // What was in hand stays to be looked at; the tab says the read failed.
                    list.value = list.value.copy(error = e)
                    return@launch
                }
            } while (listVersion != version)
        }
    }

    // ---- the cards ----

    /**
     * A card for [id] is on screen: asked about, unless what's known is fresh. Paired with [hide].
     * An id that can't be a clip's is never asked about ([isClipId]).
     */
    fun show(id: String) {
        if (!isClipId(id)) return
        shown[id] = (shown[id] ?: 0) + 1
        ask(id, force = false)
    }

    fun hide(id: String) {
        if (!isClipId(id)) return
        val left = (shown[id] ?: 0) - 1
        if (left > 0) shown[id] = left else shown.remove(id)
    }

    private fun ask(id: String, force: Boolean) {
        val entry = entries[id]
        if (force) {
            if (entry?.status == CardStatus.Gone || entry?.status == CardStatus.Loading) return
        } else if (!cardNeedsFetch(entry, clock())) return
        entries[id] = CardEntry(CardStatus.Loading, clock())
        waiting += id
        if (batch?.isActive == true) return
        batch = scope.launch {
            // A channel opening shows many cards in the same moment: one request for them all.
            delay(batchDelayMs)
            while (waiting.isNotEmpty()) {
                val ids = waiting.take(BATCH)
                waiting.removeAll(ids.toSet())
                val answer = runCatchingCancellable { fetchCards(ids) }
                val now = clock()
                answer.onSuccess { said ->
                    retryWait = retryMs
                    for (one in ids) entries[one] = CardEntry(if (said[one] == null) CardStatus.Gone else CardStatus.Ok, now)
                    cards.update { held -> held + ids.associateWith { one -> said[one] } }
                }.onFailure {
                    // Nothing was learnt about these: they aren't shown as gone, and the ones
                    // still on screen ask again in a while.
                    for (one in ids) entries[one] = CardEntry(CardStatus.Error, now)
                    retrySoon()
                }
            }
        }
    }

    /**
     * The cards on screen that couldn't be answered for are asked about again in a while: longer
     * each time it fails again (to [CARD_RETRY_MAX_MS]), so a VillaNET that isn't answering isn't
     * sent a steady trickle of failed requests, and only while somebody is looking at Matty.
     */
    private fun retrySoon() {
        if (retry?.isActive == true) return
        if (!visible()) { retryBehind = true; return }
        val wait = retryWait
        retryWait = (retryWait * 2).coerceAtMost(CARD_RETRY_MAX_MS)
        retry = scope.launch {
            delay(wait)
            if (!visible()) { retryBehind = true; return@launch }
            shown.keys.filter { entries[it]?.status == CardStatus.Error }.forEach { ask(it, force = true) }
        }
    }

    // ---- what changes them ----

    /**
     * The snapshot's `clips.version`, each time a snapshot is shown, with its count of clips and
     * when the newest was made ([newest], 0 when it didn't say). When the version has moved, the
     * list and the cards in hand are behind.
     *
     * When a clip has gone (fewer of them, or the newest older than it was), any card may be
     * showing one that has: as on the web panel, what is known about clips no card shows is
     * counted as old, so a card shown later asks, and the cards on screen ask again in a moment
     * of their own, not after the wait between votes.
     */
    fun follow(now: String, count: Int = 0, newest: Long = 0) {
        val before = version
        if (now == before) return
        val went = count < this.count || (this.newest > 0 && newest < this.newest)
        version = now
        this.count = count
        this.newest = newest
        // The first one is where things start, not a change.
        if (before == null) return
        if (listShown > 0) readListIfBehind()
        if (went) clipWent() else refreshShown()
    }

    private fun clipWent() {
        val old = clock() - CARD_FRESH_MS
        for ((id, e) in entries) {
            if (id !in shown && (e.status == CardStatus.Ok || e.status == CardStatus.Error)) entries[id] = e.copy(at = old)
        }
        if (shown.isEmpty()) return
        // Nobody looking: when they next are.
        if (!visible()) { behind = true; return }
        again?.cancel()
        again = scope.launch {
            delay((jitter().coerceIn(0.0, 1.0) * CARD_VOTES_SPREAD_MS).toLong())
            refreshedAt = clock()
            shown.keys.toList().forEach { ask(it, force = true) }
        }
    }

    /** Matty is being looked at again: whatever waited for that happens now. */
    fun lookedAt() {
        if (behind) refreshShown()
        if (retryBehind) { retryBehind = false; retrySoon() }
    }

    private fun refreshShown() {
        if (again?.isActive == true) return
        val wait = cardRefreshDelay(shown.size, visible(), refreshedAt, clock(), jitter())
        // Nobody looking: when they next are. No card on show: each asks for itself when it is.
        behind = wait == null && shown.isNotEmpty()
        if (wait == null) return
        again = scope.launch {
            delay(wait)
            refreshedAt = clock()
            shown.keys.toList().forEach { ask(it, force = true) }
        }
    }

    /** The reader's vote, as the server counted it: the same in the list, the viewer and the card. */
    fun voted(id: String, vote: VnVote) {
        cards.update { held -> held[id]?.let { held + (id to it.copy(upvotes = vote.upvotes, voted = vote.voted)) } ?: held }
        list.update { held -> held.copy(clips = held.clips?.map { if (it.id == id) it.copy(upvotes = vote.upvotes, voted = vote.voted) else it }) }
    }

    /** One clip, read fresh for the viewer: the list and its card take its word. */
    fun took(clip: VnClip) {
        list.update { held -> held.copy(clips = held.clips?.map { if (it.id == clip.id) clip else it }) }
        cards.update { held -> held[clip.id]?.let { held + (clip.id to it.copy(title = clip.title, upvotes = clip.upvotes, voted = clip.voted, expiresAt = clip.expiresAt)) } ?: held }
    }

    /** The server said [id] has gone: it leaves the list, and its card says so. */
    fun gone(id: String) {
        entries[id] = CardEntry(CardStatus.Gone, clock())
        waiting -= id
        cards.update { it + (id to null) }
        list.update { held -> held.copy(clips = held.clips?.filter { it.id != id }) }
    }

    /**
     * Nothing of the server just left, or of a reader just locked out, stays to be shown. For a
     * reader locked out, the snapshot stays in hand ([keepFollowing]): what it said of the clips
     * is still where they start, so the first change after they unlock is followed as one.
     */
    fun clear(keepFollowing: Boolean = false) {
        listOf(batch, again, retry, listJob).forEach { it?.cancel() }
        batch = null; again = null; retry = null; listJob = null
        retryWait = retryMs
        retryBehind = false
        entries.clear()
        waiting.clear()
        if (!keepFollowing) {
            version = null
            count = 0
            newest = 0
        }
        listVersion = null
        refreshedAt = null
        behind = false
        cards.value = emptyMap()
        list.value = ClipList()
    }

    companion object {
        /** As many ids as one request for cards may ask about (the plugin's limit). */
        const val BATCH = 25
    }
}
