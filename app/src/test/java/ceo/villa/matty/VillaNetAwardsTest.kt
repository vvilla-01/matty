package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.villanet.AwardMark
import ceo.villa.matty.plugins.villanet.VillaNetAwardLogic
import ceo.villa.matty.plugins.villanet.VnBoards
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A profile's awards. Which rows somebody has, in which order and under what title is the
 * server's to say (its awards_decide.go, held by its own tests and by boards.json in the
 * contract); what is checked here is what the app makes of rows it is given: their figures
 * written out, their marks, and what is read aloud.
 */
class VillaNetAwardsTest {
    private val boards = MattermostJson.decodeFromString(VnBoards.serializer(), """
    {"version":"3f2a9c0d1e2f3a4b",
     "allTime":[{"rank":1,"title":"Dave","seconds":1483200,"userId":"dave","username":"dave","displayName":"Dave"},
                {"rank":2,"title":"Unnamed stream","seconds":900000},
                {"rank":3,"title":"Alice","seconds":712800,"userId":"alice","twitchSeconds":36000}],
     "month":[{"rank":1,"title":"Alice","seconds":43200,"userId":"alice"}],
     "monthLabel":"November 2026",
     "fans":[{"streamerId":"dave","streamer":"Dave","fanId":"alice","fan":"Alice","fanUsername":"alice","hours":41}],"fansOn":true,
     "people":{
       "dave":{"rows":[
         {"kind":"board","id":"alltime","rank":1,"title":"Champion","board":"All time","seconds":1483200},
         {"kind":"achievements","id":"achievements","rank":0,"title":"Achievements","steps":[
           {"id":"streamed","tier":4,"name":"Streamed 250 hours"},{"id":"podium","tier":2,"name":"On the podium 3 months"}]},
         {"kind":"months","id":"past","rank":1,"title":"Monthly champion","gold":["2026-09","2026-08"],"tallies":[{"rank":3,"months":["2026-05"]}]}]},
       "alice":{"rows":[
         {"kind":"board","id":"alltime","rank":3,"title":"Third place","board":"All time","seconds":712800},
         {"kind":"board","id":"month","rank":1,"title":"Champion","board":"November 2026","seconds":43200},
         {"kind":"board","id":"snooper","rank":1,"title":"Top Snooper","board":"All time","seconds":1101600,"doing":"watching others"},
         {"kind":"clipper","id":"clipper","rank":1,"title":"Best Clipper","upvotes":142,"clips":9},
         {"kind":"fan","id":"fan:dave","rank":1,"title":"Biggest fan of Dave","streamerId":"dave","streamer":"Dave","hours":41},
         {"kind":"achievements","id":"achievements","rank":0,"title":"Achievements","steps":[
           {"id":"streamed","tier":3,"name":"Streamed 100 hours"},{"id":"snooped","tier":4,"name":"Snooped 250 hours"},{"id":"thumbs","tier":3,"name":"100 thumbs up"}]}]},
       "dan":{"rows":[
         {"kind":"months","id":"past","rank":2,"title":"Monthly podium","tallies":[{"rank":2,"months":["2026-06"]},{"rank":3,"months":["2026-05","2026-04"]}]},
         {"kind":"marathon","id":"marathon","rank":2,"title":"Longest stream"}]}}}
    """)

    @Test fun dave() {
        val rows = VillaNetAwardLogic.rowsFor(boards, "dave")
        assertEquals(listOf("Champion", "Achievements", "Monthly champion"), rows.map { it.title })
        assertEquals("All time · 412h", rows[0].meta)
        assertEquals(1, rows[0].tint)
        assertEquals("Champion for airtime, all time", rows[0].a11y)
        assertEquals(listOf("Streamed 250 hours", "On the podium 3 months"), rows[1].chips.map { it.text })
        assertEquals(AwardMark.Medal, rows[1].mark)
        assertEquals(0, rows[1].tint)
        assertEquals("Achievements: Streamed 250 hours, On the podium 3 months", rows[1].a11y)
        assertEquals("×2", rows[2].pill)
        // Newest first, as sent: the app sorts nothing.
        assertTrue(rows[2].meta!!.contains("2026") && rows[2].meta!!.indexOf("Sep") < rows[2].meta!!.indexOf("Aug"), rows[2].meta)
        assertEquals(listOf(3 to 1), rows[2].tallies.map { it.rank to it.count })
        // What a screen reader is told of the row: the months won, then the third place, which
        // is drawn only as a picture and "×1". (Months as written in tests' English.)
        assertEquals("Monthly champion, 2 months: Sep\u00A02026, Aug\u00A02026. Third place in 1 month: May\u00A02026", rows[2].a11y)
        val fan = VillaNetAwardLogic.biggestFan(boards, "dave")!!
        assertEquals("Alice", fan.fan); assertEquals(41, fan.hours)
    }

    @Test fun alice() {
        val rows = VillaNetAwardLogic.rowsFor(boards, "alice")
        assertEquals(listOf("Third place", "Champion", "Top Snooper", "Best Clipper", "Biggest fan of Dave", "Achievements"), rows.map { it.title })
        assertEquals("All time · 198h", rows[0].meta)
        assertEquals(3, rows[0].tint)
        assertEquals("November 2026 · 12h", rows[1].meta)
        assertEquals("Champion for airtime this month", rows[1].a11y)
        assertEquals("All time · 306h watching others", rows[2].meta)
        assertEquals("Top Snooper, all time", rows[2].a11y)
        assertEquals("All time · 142 upvotes on 9 clips", rows[3].meta)
        assertEquals("Best Clipper, the most upvoted clips of all time", rows[3].a11y)
        assertEquals("41 hours watching", rows[4].meta)
        assertEquals("Biggest fan of Dave", rows[4].a11y)
        assertEquals(listOf("Streamed 100 hours", "Snooped 250 hours", "100 thumbs up"), rows[5].chips.map { it.text })
        assertNull(VillaNetAwardLogic.biggestFan(boards, "alice"))
    }

    @Test fun aPodiumWithNoWinAndARowThisAppHasNeverHeardOf() {
        val rows = VillaNetAwardLogic.rowsFor(boards, "dan")
        assertEquals(listOf("Monthly podium", "Longest stream"), rows.map { it.title })
        // No month won: no line of months and no ×N, only the tallies.
        assertNull(rows[0].meta)
        assertNull(rows[0].pill)
        assertEquals(2, rows[0].tint)
        assertEquals(listOf(2 to 1, 3 to 2), rows[0].tallies.map { it.rank to it.count })
        // Read aloud: the places themselves, which are drawn only as pictures.
        assertEquals("Monthly podium. Runner-up in 1 month: Jun\u00A02026. Third place in 2 months: May\u00A02026, Apr\u00A02026", rows[0].a11y)
        // A kind a newer VillaNET sends is shown by what the server calls it, never dropped.
        assertEquals("Longest stream", rows[1].a11y)
        assertNull(rows[1].meta)
        assertEquals(AwardMark.Medal, rows[1].mark)
    }

    @Test fun nobodyAndSwitches() {
        assertTrue(VillaNetAwardLogic.rowsFor(boards, "heidi").isEmpty())
        // Before the boards have been read there is nothing to show about anybody.
        assertTrue(VillaNetAwardLogic.rowsFor(null, "dave").isEmpty())
        assertNull(VillaNetAwardLogic.biggestFan(null, "dave"))
        // A list of fans without the switch isn't trusted: biggest fans are off.
        assertNull(VillaNetAwardLogic.biggestFan(boards.copy(fansOn = false), "dave"))
    }

    @Test fun formatting() {
        assertEquals("0m", VillaNetAwardLogic.duration(0))
        assertEquals("45s", VillaNetAwardLogic.duration(45))
        assertEquals("59m", VillaNetAwardLogic.duration(3599))
        assertEquals("1h 30m", VillaNetAwardLogic.duration(5400))
        assertEquals("25h", VillaNetAwardLogic.duration(90000 + 60))
        assertEquals(2, (VillaNetAwardLogic.coin("ch-lofi", 7) as AwardMark.Art).studs)
        assertEquals(3, VillaNetAwardLogic.coin("ch-lofi", 8).studs)
    }
}
