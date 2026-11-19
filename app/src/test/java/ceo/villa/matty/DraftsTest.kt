package ceo.villa.matty

import ceo.villa.matty.ui.chat.Drafts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DraftsTest {
    @After fun clear() { Drafts.map.clear(); Drafts.live.clear(); Drafts.forgetUploads() }

    @Test
    fun picksStayWithTheirDraftWhenItsBoxGoes() {
        // A new box for the same conversation (after the lock, or coming back to it) gets the
        // same chips the last one had; another conversation has its own.
        val chips = Drafts.uploads("c|")
        assertSame(chips, Drafts.uploads("c|"))
        assertNotSame(chips, Drafts.uploads("d|"))
        // Signing out forgets them.
        Drafts.forgetUploads()
        assertNotSame(chips, Drafts.uploads("c|"))
    }

    @Test
    fun mentionsJoinWithASpace() {
        assertEquals("@bob ", Drafts.join("", "@bob "))
        assertEquals("hey @bob ", Drafts.join("hey  ", "@bob "))
    }

    @Test
    fun quotesGoOnLinesOfTheirOwn() {
        assertEquals("> hi\n\n", Drafts.join("  ", "> hi\n\n", block = true))
        assertEquals("so\n\n> hi\n\n", Drafts.join("so ", "> hi\n\n", block = true))
    }

    @Test
    fun aDraftWithNoBoxIsWrittenAndAnnounced() {
        Drafts.map["c|"] = "draft"
        Drafts.append("c|", "> q\n\n", block = true)
        assertEquals("draft\n\n> q\n\n", Drafts.map["c|"])
        assertEquals(true, Drafts.added?.block)
    }

    @Test
    fun aLiveBoxIsLeftToAddItItself() {
        // The box on screen adds it to what's typed there (which the saved draft may lag behind).
        Drafts.live += "c|"
        Drafts.map["c|"] = "old"
        Drafts.append("c|", "> q\n\n", block = true)
        assertEquals("old", Drafts.map["c|"])
        assertEquals("> q\n\n", Drafts.added?.text)
    }
}
