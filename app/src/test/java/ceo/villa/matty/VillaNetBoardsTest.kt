package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.BOARDS_FRESH_MS
import ceo.villa.matty.plugins.villanet.BoardsHeld
import ceo.villa.matty.plugins.villanet.BoardsShelf
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VnBoards
import ceo.villa.matty.plugins.villanet.VnLeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The app's copy of the boards: read only when somebody looks, kept from then on, and asked
 * about again when it is old or when the snapshot says the boards have changed.
 */
class VillaNetBoardsTest {
    private class Fixture(scope: CoroutineScope, var onScreen: Boolean = true, freshMs: Long = BOARDS_FRESH_MS, retryMs: Long = 20) {
        var now = 1_000_000L
        val held = MutableStateFlow(BoardsHeld())

        /** Each request's tag, as it is made; null for "whatever is held". */
        val calls = Channel<String>(Channel.UNLIMITED)
        var boards = boardsOf("v1", 60)
        var tag = "\"t1\""
        var fail: Throwable? = null
        /** While set, a request waits for it before it is answered. */
        var gate: CompletableDeferred<Unit>? = null
        val shelf = BoardsShelf(
            scope = scope, clock = { now },
            fetch = { sent ->
                calls.send(sent ?: "(no tag)")
                // Answered as things stand when it is asked, however long it then takes.
                val answer = if (sent == tag) null else VillaNetApi.Tagged(boards, tag)
                gate?.await()
                fail?.let { throw it }
                answer
            },
            held = held, visible = { onScreen }, jitter = { 0.0 }, freshMs = freshMs, spreadMs = 1, retryMs = retryMs,
        )

        suspend fun nextCall(): String = withTimeout(2_000) { calls.receive() }
        suspend fun noCallWithin(ms: Long) = assertNull("an unexpected read of the boards", withTimeoutOrNull(ms) { calls.receive() })
        suspend fun settle() { repeat(5) { yield() }; delay(5) }
    }

    companion object {
        fun boardsOf(version: String, seconds: Long) = VnBoards(version = version, allTime = listOf(VnLeader(rank = 1, title = "Dave", seconds = seconds, userId = "u-dave")))
    }

    @Test
    fun nothingIsReadUntilSomebodyLooks() = runBlocking {
        val f = Fixture(this)
        // Snapshots come and go, each with its word about the boards: nobody has asked for them.
        f.shelf.follow("v1")
        f.shelf.follow("v2")
        f.noCallWithin(30)
        assertNull(f.held.value.boards)

        // A profile opens: read, whole.
        f.shelf.lookedAt()
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        assertEquals(60L, f.held.value.boards?.allTime?.single()?.seconds)

        // Another, a moment later: the copy in hand is shown, and nothing is asked.
        f.now += BOARDS_FRESH_MS - 1
        f.shelf.lookedAt()
        f.noCallWithin(30)

        // A minute after the read: asked with the tag, and "that one still" changes nothing.
        f.now += 1
        val before = f.held.value
        f.shelf.lookedAt()
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertTrue(before === f.held.value)
        // Which vouched for the copy: it is fresh for another minute.
        f.now += BOARDS_FRESH_MS - 1
        f.shelf.lookedAt()
        f.noCallWithin(30)
        f.shelf.close()
    }

    @Test
    fun aCopyInHandFollowsTheSnapshotsWord() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        // The same word, however often: nothing.
        f.shelf.follow("v1")
        f.shelf.follow("v1")
        f.shelf.follow(null)
        f.shelf.follow("")
        f.noCallWithin(30)

        // A place changed: the word moves, and the copy is brought up to date, once.
        f.boards = boardsOf("v2", 75); f.tag = "\"t2\""
        f.shelf.follow("v2")
        f.shelf.follow("v2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v2", f.held.value.boards?.version)
        f.shelf.follow("v2")
        f.noCallWithin(30)
        f.shelf.close()
    }

    @Test
    fun aChangeWhileNobodyIsLookingWaitsUntilSomebodyIs() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        // Minimised: the boards change, and nothing is read for nobody.
        f.onScreen = false
        f.boards = boardsOf("v2", 75); f.tag = "\"t2\""
        f.shelf.follow("v2")
        f.noCallWithin(30)

        // Back, and a profile opened within the minute: the copy is behind, so it is asked about.
        f.onScreen = true
        f.shelf.lookedAt()
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v2", f.held.value.boards?.version)
        f.shelf.close()
    }

    @Test
    fun theBoardsTabReadsEachMinuteWhileItIsOnScreen() = runBlocking {
        val f = Fixture(this, freshMs = 40)
        f.shelf.shown(true)
        assertEquals("(no tag)", f.nextCall())
        f.settle()

        // The seconds tick, and nothing says so: asked again each minute, whatever the clock says
        // of the copy (here it hasn't moved at all: by its age, the copy is never a minute old).
        f.boards = boardsOf("v1", 120); f.tag = "\"t2\""
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals(120L, f.held.value.boards?.allTime?.single()?.seconds)
        f.boards = boardsOf("v1", 180); f.tag = "\"t3\""
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        assertEquals(180L, f.held.value.boards?.allTime?.single()?.seconds)

        // Off screen, nothing more.
        f.shelf.shown(false)
        f.now += 400
        f.noCallWithin(120)
        f.shelf.close()
    }

    @Test
    fun aReadThatFailsKeepsWhatWasInHand() = runBlocking {
        val f = Fixture(this)
        // The first read fails: nothing to show, and the tab can say why.
        f.fail = IOException("offline")
        f.shelf.shown(true)
        f.nextCall()
        f.settle()
        assertNull(f.held.value.boards)
        assertTrue(f.held.value.error is IOException)

        // The server is back, and says so with its next snapshot: the tab was waiting for it.
        f.fail = null
        f.shelf.follow("v1")
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        assertEquals("v1", f.held.value.boards?.version)
        assertNull(f.held.value.error)

        // A later failure takes nothing away, and Try again asks whatever the copy's age.
        f.fail = IOException("offline")
        f.shelf.reload()
        f.nextCall()
        f.settle()
        assertEquals("v1", f.held.value.boards?.version)
        assertTrue(f.held.value.error is IOException)
        f.fail = null
        f.shelf.reload()
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertNull("\"that one still\" after a failure: what is shown is right again", f.held.value.error)

        // The server left: nothing of it stays, and the next look reads whole.
        f.shelf.clear()
        assertEquals(BoardsHeld(), f.held.value)
        f.shelf.lookedAt()
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        f.shelf.close()
    }

    @Test
    fun aProfileWhoseFirstReadFailedAsksAgainWhileItStaysOpen() = runBlocking {
        val f = Fixture(this)
        f.fail = IOException("offline")
        val open = launch { f.shelf.profileOpen() }
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        assertNull(f.held.value.boards)

        // Still open: asked again by itself, a while later, until there is something to show.
        f.fail = null
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        assertEquals("v1", f.held.value.boards?.version)
        f.noCallWithin(80)

        // Closed: nothing more, whatever happens.
        open.cancel()
        f.shelf.clear()
        f.noCallWithin(80)
        f.shelf.close()
    }

    @Test
    fun aReadForAMovedWordThatFailsIsMadeAgain() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        f.boards = boardsOf("v2", 75); f.tag = "\"t2\""
        f.fail = IOException("offline")
        f.shelf.follow("v2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v1", f.held.value.boards?.version)

        // The next snapshot saying the same word reads again.
        f.fail = null
        f.shelf.follow("v2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v2", f.held.value.boards?.version)
        f.shelf.close()
    }

    @Test
    fun anAnswerFromBeforeTheWordIsAskedAboutOnceMore() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        // The snapshot says v2, and the server that answers has not caught up: "that one still".
        f.shelf.follow("v2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v1", f.held.value.boards?.version)

        // The next snapshot saying the word reads once more, and now it is there.
        f.boards = boardsOf("v2", 75); f.tag = "\"t2\""
        f.shelf.follow("v2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v2", f.held.value.boards?.version)

        // Once more only: a server that stays behind is not asked by every snapshot.
        f.shelf.follow("v3")
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        f.shelf.follow("v3")
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        f.shelf.follow("v3")
        f.noCallWithin(60)
        assertEquals("v2", f.held.value.boards?.version)

        // But the copy is behind, and whoever looks next is given a fresh read.
        f.shelf.lookedAt()
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        f.shelf.close()
    }

    @Test
    fun aReadForAMovedWordIsNotDroppedForOneAlreadyOut() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        // A profile opens a minute on: a read goes out, and the server answers it from before
        // the change…
        f.now += BOARDS_FRESH_MS
        val gate = CompletableDeferred<Unit>()
        f.gate = gate
        f.shelf.lookedAt()
        assertEquals("\"t1\"", f.nextCall())
        // …while the snapshot says the boards have changed.
        f.shelf.follow("v2")
        f.settle()
        f.gate = null
        f.boards = boardsOf("v2", 75)
        f.tag = "\"t2\""
        gate.complete(Unit)
        // The read that answered from before brought "that one still": the new word is read.
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("v2", f.held.value.boards?.version)
        f.shelf.close()
    }

    @Test
    fun comingBackReadsOnlyACopyThatFellBehind() = runBlocking {
        val f = Fixture(this)
        f.shelf.lookedAt()
        f.nextCall()
        f.settle()

        // Merely old: nobody is looking at a board, and nothing is read.
        f.now += BOARDS_FRESH_MS * 5
        f.shelf.cameBack()
        f.noCallWithin(30)

        // Changed while Matty was minimised: read as it comes back.
        f.onScreen = false
        f.shelf.follow("v2")
        f.noCallWithin(30)
        f.onScreen = true
        f.shelf.cameBack()
        assertEquals("\"t1\"", f.nextCall())
        f.shelf.close()
    }
}
