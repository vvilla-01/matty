package ceo.villa.matty

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import ceo.villa.matty.ui.chat.ScrollAway
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAwayTest {
    private fun ScrollAway.drag(dy: Float) = connection.onPostScroll(Offset(0f, dy), Offset.Zero, NestedScrollSource.UserInput)

    @Test
    fun scrollingUpThroughOlderMessagesHidesItAndScrollingDownBringsItBack() {
        val a = ScrollAway(threshold = 30f)
        a.drag(20f); assertFalse("a wobble isn't enough", a.hidden)
        a.drag(20f); assertTrue(a.hidden)
        a.drag(-20f); assertTrue("not yet", a.hidden)
        a.drag(-20f); assertFalse(a.hidden)
    }

    @Test
    fun aChangeOfDirectionStartsTheCountAgain() {
        val a = ScrollAway(threshold = 30f)
        a.drag(25f); a.drag(-10f); a.drag(25f)
        assertFalse(a.hidden)
    }

    @Test
    fun itStaysWhileKept() {
        val a = ScrollAway(threshold = 30f)
        a.keep = true
        a.drag(200f)
        assertFalse(a.hidden)
    }

    @Test
    fun nothingConsumedChangesNothing() {
        val a = ScrollAway(threshold = 30f)
        a.drag(0f); a.drag(0f)
        assertFalse(a.hidden)
    }
}
