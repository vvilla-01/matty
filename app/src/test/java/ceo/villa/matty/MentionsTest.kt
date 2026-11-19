package ceo.villa.matty

import ceo.villa.matty.ui.chat.Markdown
import ceo.villa.matty.ui.chat.MdBlock
import ceo.villa.matty.ui.chat.Mentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {
    private val labels = mapOf("itzdave" to "Dave", "dave" to "Dave B", "alice.maria" to "Alice")

    @Test fun aKnownPersonIsDrawnByTheirName() {
        assertEquals(Mentions.Drawn("itzdave", "Dave", ""), Mentions.drawn("itzdave", labels))
        assertEquals(Mentions.Drawn("itzdave", "Dave", ""), Mentions.drawn("ItzDave", labels), "usernames match in any case")
    }

    @Test fun somebodyUnknownAndEverybodyStayAsTyped() {
        assertEquals(Mentions.Drawn(null, "nobody", ""), Mentions.drawn("nobody", labels))
        for (s in listOf("channel", "here", "all")) assertEquals(Mentions.Drawn(null, s, ""), Mentions.drawn(s, labels + (s to "Not them")))
    }

    @Test fun aPrefixUpToASeparatorIsThePersonAndTheRestIsText() {
        assertEquals(Mentions.Drawn("dave", "Dave B", ".thanks"), Mentions.drawn("dave.thanks", labels))
        assertEquals(Mentions.Drawn("dave", "Dave B", "_"), Mentions.drawn("dave_", labels))
        assertEquals(Mentions.Drawn("dave", "Dave B", "-x.y"), Mentions.drawn("dave-x.y", labels))
        // The whole token wins over a prefix of it.
        assertEquals(Mentions.Drawn("alice.maria", "Alice", ""), Mentions.drawn("alice.maria", labels + ("alice" to "Other")))
        assertEquals(listOf("a.b-c_d", "a.b-c", "a.b", "a"), Mentions.candidates("a.b-c_d"))
        assertNull(Mentions.resolve("davex", labels::containsKey), "a prefix without a separator is not the person")
    }

    @Test fun theParsersTokenResolves() {
        val p = Markdown.parse("thanks @dave. and @dave.thanks").single() as MdBlock.Paragraph
        assertEquals(setOf("dave", "dave.thanks"), Mentions.names(p.inlines))
    }

    @Test fun onlyUnknownNamesAreAskedFor() {
        val known = setOf("dave")
        val asked = Mentions.unknown(listOf("hi @dave, @dave.thanks @Bob.", "mail me@x.com, @here", "`@walter` none"), known::contains)
        // "dave.thanks" may be its own person; "dave" is known; an email and @here are nobody.
        assertEquals(setOf("dave.thanks", "bob", "walter"), asked)
    }
}
