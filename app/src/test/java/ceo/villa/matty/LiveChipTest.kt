package ceo.villa.matty

import ceo.villa.matty.data.api.User
import ceo.villa.matty.plugins.PersonFacts
import ceo.villa.matty.ui.chat.HeaderMarks
import ceo.villa.matty.ui.chat.headerMarks
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChipTest {
    private fun withStatus(emoji: String) = User("u1", "dave", props = mapOf("customStatus" to """{"emoji":"$emoji","text":"Live now","duration":"dont_clear"}"""))

    @Test
    fun whileVillaNetHoldsTheStatusTheChipTakesTheRedCirclesPlace() {
        assertEquals(HeaderMarks(null, live = true), headerMarks(withStatus("red_circle"), PersonFacts(live = true, holdsStatus = true)))
    }

    @Test
    fun aStatusOfTheirOwnStaysWithTheChipAfterIt() {
        assertEquals(HeaderMarks("tada", live = true), headerMarks(withStatus("tada"), PersonFacts(live = true)))
        assertEquals(HeaderMarks("birthday", live = true), headerMarks(withStatus("birthday"), PersonFacts(live = true)))
    }

    @Test
    fun nobodyLiveIsAsBefore() {
        assertEquals(HeaderMarks("tada", live = false), headerMarks(withStatus("tada"), PersonFacts.None))
        assertEquals(HeaderMarks(null, live = false), headerMarks(User("u1", "dave"), PersonFacts.None))
        assertEquals(HeaderMarks(null, live = false), headerMarks(null, PersonFacts.None))
    }
}
