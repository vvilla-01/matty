package ceo.villa.matty

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.MonotonicFrameClock
import ceo.villa.matty.ui.chat.glideToPresent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpToPresentTest {
    /** A conversation [older] pixels tall above the newest message; 0 is the newest, at rest. */
    private class Conversation(val older: Float, start: Float) {
        var at = start
        val seen = ArrayList<Float>()
        var snaps = 0
        val state = ScrollableState { d ->
            val next = (at + d).coerceIn(0f, older)
            val used = next - at
            at = next
            seen += at
            used
        }
        suspend fun snap() { snaps++; at = 0f; seen += at }
    }

    /** Frames 16 ms apart, letting anything else waiting run before each one. */
    private object Frames : MonotonicFrameClock {
        var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); now += 16_000_000; return onFrame(now) }
    }

    @Test
    fun fromFarUpItIsOneGlideDownThatEndsAtTheNewest() = runBlocking(Frames) {
        val c = Conversation(older = 50_000f, start = 40_000f)
        c.state.glideToPresent(800f) { c.snap() }
        assertEquals(0f, c.at)
        // Straight to the newest, back up exactly the distance, then only ever downwards.
        val afterSnap = c.seen.drop(1)
        assertEquals(800f, afterSnap.first())
        afterSnap.zipWithNext().forEach { (a, b) -> assertTrue("never back up mid-glide: $a then $b", b <= a) }
        assertTrue("it's animated, not a jump", afterSnap.size > 5)
        // And it ends on the newest message exactly: once to go there, once more when the glide is over.
        assertEquals(2, c.snaps)
    }

    @Test
    fun aShortConversationGlidesWhatThereIs() = runBlocking(Frames) {
        val c = Conversation(older = 300f, start = 300f)
        c.state.glideToPresent(800f) { c.snap() }
        assertEquals(300f, c.seen[1])
        assertEquals(0f, c.at)
    }

    @Test
    fun withNothingAboveThereIsNothingToGlide() = runBlocking(Frames) {
        val c = Conversation(older = 0f, start = 0f)
        c.state.glideToPresent(800f) { c.snap() }
        assertEquals(1, c.snaps)
        assertEquals(0f, c.at)
    }

    @Test
    fun theReadersTouchWins() = runBlocking(Frames) {
        val c = Conversation(older = 50_000f, start = 40_000f)
        val glide = launch { c.state.glideToPresent(800f) { c.snap() } }
        var waited = 0
        while (!(c.snaps == 1 && c.at in 1f..600f)) { yield(); assertTrue("the glide never got under way", ++waited < 10_000) }
        c.state.scroll(MutatePriority.UserInput) { scrollBy(200f) }
        val left = c.at
        glide.join()
        assertTrue(glide.isCancelled)
        assertEquals("not pulled down to the newest after all", left, c.at)
        assertEquals(1, c.snaps)
    }
}
