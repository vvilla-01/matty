package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.CARD_FRESH_MS
import ceo.villa.matty.plugins.villanet.CARD_RETRY_MS
import ceo.villa.matty.plugins.villanet.CARD_VOTES_EVERY_MS
import ceo.villa.matty.plugins.villanet.CARD_VOTES_SPREAD_MS
import ceo.villa.matty.plugins.villanet.CLIP_UNPLAYED_WORDS
import ceo.villa.matty.plugins.villanet.CLIP_UNREACHED_WORDS
import ceo.villa.matty.plugins.villanet.CardEntry
import ceo.villa.matty.plugins.villanet.CardStatus
import ceo.villa.matty.plugins.villanet.ClipList
import ceo.villa.matty.plugins.villanet.ClipOpen
import ceo.villa.matty.plugins.villanet.ClipShelf
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.VnClip
import ceo.villa.matty.plugins.villanet.VnClipCard
import ceo.villa.matty.plugins.villanet.VnVote
import ceo.villa.matty.plugins.villanet.cardExpired
import ceo.villa.matty.plugins.villanet.cardNeedsFetch
import ceo.villa.matty.plugins.villanet.cardRefreshDelay
import ceo.villa.matty.plugins.villanet.clipOpenAfterPlay
import ceo.villa.matty.plugins.villanet.clipOpenOf
import ceo.villa.matty.plugins.villanet.clipViewerShown
import ceo.villa.matty.plugins.villanet.isClipId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Real clip ids: sixteen lower-case hex digits, the only shape that is ever asked about. */
private const val A = "aaaaaaaaaaaaaaaa"
private const val B = "bbbbbbbbbbbbbbbb"
private const val C = "cccccccccccccccc"

/** Clips in the app: why one didn't open, and the one copy that the list, the viewer and the cards share. */
class VillaNetClipsTest {
    // ---- why a clip didn't open ------------------------------------------------------------------

    @Test
    fun onlyTheServersGoneMeansGone() {
        // Told in the server's own sentence.
        assertEquals(ClipOpen.Gone("This clip has gone."), clipOpenOf(VillaNetException(VillaNetApi.CLIP_GONE, "This clip has gone.", 410, "This clip has gone.")))
        // The status alone says it too; with no sentence of the server's, the app words it.
        assertEquals(ClipOpen.Gone(null), clipOpenOf(VillaNetException("", "VillaNET returned 410", 410)))
    }

    @Test
    fun aLockedReaderIsToldToUnlockNotThatTheClipIsGone() {
        // In the server's own sentence, as every refusal is.
        assertEquals(ClipOpen.Locked("VillaNET is locked. Unlock it with your bearer token."),
            clipOpenOf(VillaNetException("locked", "VillaNET is locked. Unlock it with your bearer token.", 403, "VillaNET is locked. Unlock it with your bearer token.")))
        assertEquals(ClipOpen.Locked("Link your stream."), clipOpenOf(VillaNetException("watch_only", "Link your stream.", 403, "Link your stream.")))
        assertEquals(ClipOpen.Locked(null), clipOpenOf(VillaNetException("locked", "VillaNET returned 403", 403)))
    }

    @Test
    fun aClipThatIsListedButWontPlaySaysSoOnce() {
        val found = ClipOpen.Found(VnClip(id = A))
        assertEquals("nothing failed: played", found, clipOpenAfterPlay(found, playFailed = false))
        // Its video failed and the server still lists it: said, with Try again, not played again.
        assertEquals(ClipOpen.Failed(CLIP_UNPLAYED_WORDS), clipOpenAfterPlay(found, playFailed = true))
        // Asked again, the server's answer about it stands.
        assertEquals(ClipOpen.Gone("This clip has gone."), clipOpenAfterPlay(ClipOpen.Gone("This clip has gone."), playFailed = true))
        assertEquals(ClipOpen.Locked(null), clipOpenAfterPlay(ClipOpen.Locked(null), playFailed = true))
    }

    @Test
    fun theViewerIsDrawnOnlyWhileTheReaderMayWatchAndTheVillaNetIsInStep() {
        val linked = VnAccess(access = "linked", configured = true)
        assertTrue(clipViewerShown(outdated = null, access = linked))
        assertTrue(clipViewerShown(null, VnAccess(access = "letIn", configured = true)))
        // A locked reader unlocks first; the clip asked for waits for that.
        assertFalse(clipViewerShown(null, VnAccess(access = "locked", configured = true)))
        // The server couldn't tell: the tabs are there, so the viewer opens and says what it's told.
        assertTrue(clipViewerShown(null, VnAccess(access = "unknown", configured = true)))
        assertFalse("VillaNET isn't set up", clipViewerShown(null, VnAccess(access = "linked", configured = false)))
        assertFalse("not known yet", clipViewerShown(null, null))
        // An older VillaNET is asked nothing: not for a clip either.
        assertFalse(clipViewerShown(outdated = "3.5.0", access = linked))
        assertFalse(clipViewerShown(outdated = "", access = linked))
    }

    @Test
    fun onlyAnIdShapedAsTheServerMakesThemIsAClipsId() {
        assertTrue(isClipId("0123456789abcdef"))
        assertFalse(isClipId(""))
        assertFalse(isClipId("x"))
        assertFalse("upper case", isClipId("0123456789ABCDEF"))
        assertFalse("too long", isClipId("0123456789abcdef0"))
        assertFalse("something tacked on", isClipId("0123456789abcdef&x=1"))
    }

    @Test
    fun anythingElseSaysNothingAboutTheClip() {
        // The server refusing for another reason: its own sentence.
        assertEquals(
            ClipOpen.Failed("Clips could not be saved just now. Try again."),
            clipOpenOf(VillaNetException("store", "Clips could not be saved just now. Try again.", 503, "Clips could not be saved just now. Try again.")),
        )
        // No answer of VillaNET's at all (offline, a proxy's page): the app's own words, and
        // never "gone".
        assertEquals(ClipOpen.Failed(CLIP_UNREACHED_WORDS), clipOpenOf(IOException("timeout")))
        assertEquals(ClipOpen.Failed(CLIP_UNREACHED_WORDS), clipOpenOf(VillaNetException("", "VillaNET returned 502", 502)))
        assertEquals(ClipOpen.Failed(CLIP_UNREACHED_WORDS), clipOpenOf(VillaNetException("", "VillaNET returned 404", 404)))
    }

    // ---- how long a card is good for (the web panel's rules) -------------------------------------

    @Test
    fun aCardAsksWhenNothingWasHeardOrWhatWasHeardIsOld() {
        val now = 1_000_000L
        assertTrue(cardNeedsFetch(null, now))
        assertFalse("never once it has gone: it can't come back", cardNeedsFetch(CardEntry(CardStatus.Gone, 0), now))
        assertFalse("nor while it's being asked", cardNeedsFetch(CardEntry(CardStatus.Loading, 0), now))
        assertFalse(cardNeedsFetch(CardEntry(CardStatus.Ok, now - CARD_FRESH_MS + 1), now))
        assertTrue(cardNeedsFetch(CardEntry(CardStatus.Ok, now - CARD_FRESH_MS), now))
        // A failure is tried again sooner than an answer is.
        assertFalse(cardNeedsFetch(CardEntry(CardStatus.Error, now - CARD_RETRY_MS + 1), now))
        assertTrue(cardNeedsFetch(CardEntry(CardStatus.Error, now - CARD_RETRY_MS), now))
    }

    @Test
    fun aChangeToTheClipsIsFollowedByTheCardsOnScreenAndNotInAStorm() {
        val now = 500_000L
        assertNull("no card on show", cardRefreshDelay(shown = 0, visible = true, lastAt = null, now = now, jitter = 0.5))
        assertNull("nobody looking", cardRefreshDelay(shown = 3, visible = false, lastAt = null, now = now, jitter = 0.5))
        // Spread over three seconds, so a channel's readers don't all ask at once.
        assertEquals(0L, cardRefreshDelay(3, true, null, now, 0.0))
        assertEquals(CARD_VOTES_SPREAD_MS, cardRefreshDelay(3, true, null, now, 1.0))
        assertEquals(CARD_VOTES_SPREAD_MS, cardRefreshDelay(3, true, null, now, 7.0))
        // At most once every thirty seconds.
        assertEquals(CARD_VOTES_EVERY_MS - 10_000, cardRefreshDelay(3, true, now - 10_000, now, 0.0))
        assertEquals(0L, cardRefreshDelay(3, true, now - CARD_VOTES_EVERY_MS - 1, now, 0.0))
    }

    @Test
    fun aCardInHandSaysWhenItsClipGoes() {
        val card = VnClipCard(id = "a", expiresAt = 5_000)
        assertFalse(cardExpired(card, 4_999))
        assertTrue(cardExpired(card, 5_000))
        // A card that doesn't say never expires by this; nor does no card.
        assertFalse(cardExpired(VnClipCard(id = "a"), Long.MAX_VALUE))
        assertFalse(cardExpired(null, Long.MAX_VALUE))
    }

    // ---- the one copy ----------------------------------------------------------------------------

    private class Fixture(scope: CoroutineScope, retryMs: Long = 20, visible: () -> Boolean = { true }) {
        var now = 1_000_000L
        val cards = MutableStateFlow<Map<String, VnClipCard?>>(emptyMap())
        val list = MutableStateFlow(ClipList())
        val cardCalls = Channel<List<String>>(Channel.UNLIMITED)
        var listCalls = 0
        var clips = listOf(VnClip(id = A, title = "one", upvotes = 1), VnClip(id = B, title = "two"))
        var answerCards: suspend (List<String>) -> Map<String, VnClipCard?> = { ids -> ids.associateWith { VnClipCard(id = it, title = "card $it") } }
        var failList: Throwable? = null
        val shelf = ClipShelf(
            scope = scope, clock = { now },
            fetchList = { listCalls++; failList?.let { throw it }; clips },
            fetchCards = { ids -> cardCalls.send(ids); answerCards(ids) },
            cards = cards, list = list, visible = visible, jitter = { 0.0 }, batchDelayMs = 1, retryMs = retryMs,
        )

        suspend fun nextCall(): List<String> = withTimeout(2_000) { cardCalls.receive() }
        suspend fun noCallWithin(ms: Long) = assertNull("an unexpected request for cards", withTimeoutOrNull(ms) { cardCalls.receive() })
        suspend fun settle() { repeat(5) { yield() }; delay(5) }
    }

    @Test
    fun cardsShownTogetherAreAskedForTogether() = runBlocking {
        val f = Fixture(this)
        listOf(A, B, C).forEach(f.shelf::show)
        assertEquals(listOf(A, B, C), f.nextCall())
        f.settle()
        assertEquals(setOf(A, B, C), f.cards.value.keys)
        // Shown again while what's known is fresh: nothing is asked.
        f.shelf.show(A)
        f.noCallWithin(30)
        // Ten minutes on, it is.
        f.now += CARD_FRESH_MS
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.shelf.clear()
    }

    @Test
    fun anIdThatArrivesWhileABatchIsOutIsNotLeftBehind() = runBlocking {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.answerCards = { ids -> gate.await(); ids.associateWith { VnClipCard(id = it) } }

        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        // The first request is still out when another card comes on screen. (This id used to be
        // stranded: noted as wanted, with nothing left running to ask for it.)
        f.shelf.show(B)
        f.shelf.show(C)
        gate.complete(Unit)
        assertEquals(listOf(B, C), f.nextCall())
        f.settle()
        assertEquals(setOf(A, B, C), f.cards.value.keys)
        f.shelf.clear()
    }

    @Test
    fun moreThanARequestHoldsGoInSeveral() = runBlocking {
        val f = Fixture(this)
        val ids = (1..60).map { "%016x".format(it) }
        ids.forEach(f.shelf::show)
        assertEquals(ids.take(25), f.nextCall())
        assertEquals(ids.subList(25, 50), f.nextCall())
        assertEquals(ids.drop(50), f.nextCall())
        f.shelf.clear()
    }

    @Test
    fun aFailedRequestIsNotAGoneClipAndIsTriedAgain() = runBlocking {
        val f = Fixture(this)
        var fail = true
        f.answerCards = { ids -> if (fail) throw IOException("offline") else ids.associateWith { VnClipCard(id = it) } }

        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertFalse("nothing was learnt, so nothing is said: least of all that it's gone", f.cards.value.containsKey(A))

        // Still on screen: asked again by itself, a while later.
        fail = false
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertTrue(f.cards.value.containsKey(A))
        f.shelf.clear()
    }

    @Test
    fun aGoneClipSaysSoAndIsNeverAskedAboutAgain() = runBlocking {
        val f = Fixture(this)
        f.answerCards = { ids -> ids.associateWith { null } }
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertTrue(f.cards.value.containsKey(A))
        assertNull(f.cards.value[A])
        f.now += CARD_FRESH_MS * 3
        f.shelf.show(A)
        f.noCallWithin(30)
        f.shelf.clear()
    }

    @Test
    fun theListIsReadOnceUntilTheClipsChange() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1")
        f.shelf.showList(true)
        f.settle()
        assertEquals(1, f.listCalls)
        assertEquals(listOf(A, B), f.list.value.clips?.map { it.id })

        // Leaving the tab and coming back reads nothing: nothing has changed.
        f.shelf.showList(false)
        f.shelf.showList(true)
        f.settle()
        assertEquals(1, f.listCalls)

        // The server says the clips changed while the tab is on screen: read again at once.
        f.clips = f.clips + VnClip(id = C, title = "three")
        f.shelf.follow("v2")
        f.settle()
        assertEquals(2, f.listCalls)
        assertEquals(3, f.list.value.clips?.size)

        // And while it's away: read when it's next looked at, not before.
        f.shelf.showList(false)
        f.shelf.follow("v3")
        f.settle()
        assertEquals(2, f.listCalls)
        f.shelf.showList(true)
        f.settle()
        assertEquals(3, f.listCalls)
        f.shelf.clear()
    }

    @Test
    fun aListThatCouldNotBeReadKeepsWhatWasInHandAndIsReadAgain() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1")
        f.shelf.showList(true)
        f.settle()
        f.failList = IOException("offline")
        f.shelf.follow("v2")
        f.settle()
        assertEquals("what was in hand stays to be looked at", 2, f.list.value.clips?.size)
        assertTrue(f.list.value.error is IOException)

        f.failList = null
        f.shelf.reloadList()
        f.settle()
        assertNull(f.list.value.error)
        assertEquals(3, f.listCalls)
        f.shelf.clear()
    }

    @Test
    fun aVoteShowsEverywhere() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1")
        f.shelf.showList(true)
        f.shelf.show(A)
        f.nextCall()
        f.settle()

        f.shelf.voted(A, VnVote(upvotes = 7, voted = true))
        assertEquals(7, f.cards.value.getValue(A)?.upvotes)
        assertEquals(true, f.cards.value.getValue(A)?.voted)
        val inList = f.list.value.clips!!.first { it.id == A }
        assertEquals(7, inList.upvotes)
        assertTrue(inList.voted)
        assertEquals("nobody else's is touched", 0, f.list.value.clips!!.first { it.id == B }.upvotes)

        // The viewer reading the clip afresh is the newest word on it, for the other two as well.
        f.shelf.took(VnClip(id = A, title = "renamed", upvotes = 9, voted = true, expiresAt = 123))
        assertEquals("renamed", f.list.value.clips!!.first { it.id == A }.title)
        val card = f.cards.value.getValue(A)!!
        assertEquals(9, card.upvotes)
        assertEquals("renamed", card.title)
        assertEquals(123L, card.expiresAt)

        // And the viewer finding it gone takes it out of the list and marks its card.
        f.shelf.gone(A)
        assertEquals(listOf(B), f.list.value.clips!!.map { it.id })
        assertTrue(f.cards.value.containsKey(A))
        assertNull(f.cards.value[A])
        f.shelf.clear()
    }

    @Test
    fun theCardsOnScreenFollowTheClipsVersion() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1")
        f.shelf.show(A)
        f.shelf.show(B)
        assertEquals(listOf(A, B), f.nextCall())
        f.settle()
        f.shelf.hide(B)

        // Somebody voted somewhere: the version moves, and the card still on screen asks again.
        f.shelf.follow("v2")
        assertEquals(listOf(A), f.nextCall())
        f.settle()

        // Again within thirty seconds: once, and not before the thirty are up.
        f.shelf.follow("v3")
        f.shelf.follow("v4")
        f.noCallWithin(40)
        f.shelf.clear()
    }

    @Test
    fun aChangeWhileNobodyIsLookingWaitsUntilSomebodyIs() = runBlocking {
        var looking = false
        val f = Fixture(this) { looking }
        f.shelf.follow("v1")
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.settle()

        f.shelf.follow("v2")
        f.noCallWithin(40)
        looking = true
        f.shelf.lookedAt()
        assertEquals(listOf(A), f.nextCall())
        f.shelf.clear()
    }

    @Test
    fun anIdThatCantBeAClipsIsNeverAskedAboutAndFailsNoRealOne() = runBlocking {
        val f = Fixture(this)
        f.shelf.show("x")
        f.shelf.show(A)
        f.shelf.show("not-a-clip")
        f.shelf.show(B)
        assertEquals("one request, for the real ones only", listOf(A, B), f.nextCall())
        f.settle()
        assertEquals(setOf(A, B), f.cards.value.keys)
        // Nor is it asked about when the clips change.
        f.shelf.follow("v1")
        f.shelf.follow("v2")
        assertEquals(setOf(A, B), f.nextCall().toSet())
        f.shelf.hide("x")
        f.shelf.clear()
    }

    @Test
    fun cardsTheServerCantAnswerForAreAskedAboutLessAndLessOften() = runBlocking {
        val f = Fixture(this)
        f.answerCards = { throw IOException("VillaNET isn't answering") }
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        // Asked again after 20 ms, then 40, then 80, then 160: not every 20 ms (about fifteen
        // times in this while).
        var calls = 0
        withTimeoutOrNull(320) { while (true) { f.cardCalls.receive(); calls++ } }
        assertTrue("asked again $calls times in 320 ms", calls in 2..5)
        f.shelf.clear()
    }

    @Test
    fun cardsThatFailedWaitForSomebodyToLookBeforeBeingAskedAgain() = runBlocking {
        var looking = true
        var fail = true
        val f = Fixture(this, visible = { looking })
        f.answerCards = { ids -> if (fail) throw IOException("offline") else ids.associateWith { VnClipCard(id = it) } }
        f.shelf.show(A)
        looking = false
        assertEquals(listOf(A), f.nextCall())
        // Matty minimised: nothing is asked while nobody looks.
        f.noCallWithin(80)
        fail = false
        looking = true
        f.shelf.lookedAt()
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertTrue(f.cards.value.containsKey(A))
        f.shelf.clear()
    }

    @Test
    fun theFirstChangeAfterAnUnlockIsFollowed() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1|2", count = 2)
        // Locked: nothing is held for them; the snapshot in hand still says where the clips start.
        f.shelf.clear(keepFollowing = true)
        assertNull(f.list.value.clips)
        // Unlocked, on the Clips tab and with a card on screen.
        f.shelf.showList(true)
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertEquals(1, f.listCalls)
        // Somebody votes: the very first change is followed.
        f.shelf.follow("v2|2", count = 2)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        assertEquals(2, f.listCalls)
        // Whereas after a plain clear (another server), the first word is where things start.
        f.shelf.clear()
        f.shelf.follow("w1|2", count = 2)
        f.settle()
        assertEquals(2, f.listCalls)
        f.shelf.clear()
    }

    @Test
    fun aClipThatGoesIsFollowedByEveryCardAsThePanelDoes() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1|3", count = 3, newest = 300)
        listOf(A, B).forEach(f.shelf::show)
        assertEquals(listOf(A, B), f.nextCall())
        f.settle()
        f.shelf.hide(B)

        // Somebody votes: the card on screen asks again, and the thirty seconds start.
        f.shelf.follow("v2|3", count = 3, newest = 300)
        assertEquals(listOf(A), f.nextCall())
        f.settle()

        // A clip is deleted a moment later: the card on screen asks again at once, not after the
        // thirty seconds…
        f.shelf.follow("v3|2", count = 2, newest = 300)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        // …and the card off screen, fetched a moment ago, asks as soon as it is shown again.
        f.shelf.show(B)
        assertEquals(listOf(B), f.nextCall())
        f.settle()
        f.shelf.hide(B)

        // The newest clip going is a clip going too, whatever the count says.
        f.shelf.follow("v4|2", count = 2, newest = 200)
        assertEquals(listOf(A), f.nextCall())
        f.settle()
        f.shelf.show(B)
        assertEquals(listOf(B), f.nextCall())
        f.settle()
        f.shelf.hide(B)
        // A clip added is not: off screen, what's known stays fresh (and the card on screen
        // waits out the thirty seconds, as for a vote).
        f.shelf.follow("v5|3", count = 3, newest = 400)
        f.shelf.show(B)
        f.noCallWithin(40)
        f.shelf.clear()
    }

    @Test
    fun clearingLeavesNothingToShow() = runBlocking {
        val f = Fixture(this)
        f.shelf.follow("v1")
        f.shelf.showList(true)
        f.shelf.show(A)
        f.nextCall()
        f.settle()
        f.shelf.clear()
        assertTrue(f.cards.value.isEmpty())
        assertNull(f.list.value.clips)
        // And the next look starts from nothing.
        f.shelf.hide(A)
        f.shelf.show(A)
        assertEquals(listOf(A), f.nextCall())
        f.shelf.clear()
    }
}
