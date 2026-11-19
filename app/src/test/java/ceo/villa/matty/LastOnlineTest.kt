package ceo.villa.matty

import ceo.villa.matty.ui.chat.dateLabel
import ceo.villa.matty.ui.profile.lastOnlineWords
import ceo.villa.matty.ui.profile.showsLastOnline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** "Last online 5 min ago" on a profile: the words for each age, at their edges, and when it is said at all. */
class LastOnlineTest {
    private val now = 1_790_000_000_000L
    private val min = 60_000L
    private val hr = 60 * min
    private val day = 24 * hr

    private val utc = ZoneId.of("UTC")

    private fun words(agoMs: Long) = lastOnlineWords(now - agoMs, now, utc)

    private fun at(zone: ZoneId, text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun words(zone: ZoneId, last: String, now: String) = lastOnlineWords(at(zone, last), at(zone, now), zone)

    @Test
    fun eachAgeInItsWords() {
        assertEquals("Last online just now", words(0))
        assertEquals("Last online just now", words(min - 1))
        assertEquals("Last online 1 min ago", words(min))
        assertEquals("Last online 59 min ago", words(hr - 1))
        assertEquals("Last online 1 hr ago", words(hr))
        assertEquals("Last online 23 hr ago", words(day - 1))
        // A week or more: the date, as the app writes dates.
        assertEquals("Last online ${dateLabel(now - 400 * day)}", words(400 * day))
        // A clock ahead of this one's is not "in the future".
        assertEquals("Last online just now", words(-5 * min))
    }

    @Test
    fun fromADayOnItCountsTheCalendarsDays() {
        for (zone in listOf(utc, ZoneId.of("America/New_York"), ZoneId.of("Asia/Kolkata"))) {
            // Under a day it is hours, whichever side of midnight.
            assertEquals("$zone", "Last online 23 hr ago", words(zone, "2026-11-10T23:59", "2026-11-11T23:00"))
            assertEquals("$zone", "Last online 22 hr ago", words(zone, "2026-11-11T00:01", "2026-11-11T23:00"))
            // Read just after midnight: the day before is "yesterday", the one before that is not.
            assertEquals("$zone", "Last online yesterday", words(zone, "2026-11-10T00:01", "2026-11-11T00:30"))
            assertEquals("$zone", "Last online 2 days ago", words(zone, "2026-11-09T23:59", "2026-11-11T00:30"))
            assertEquals("$zone", "Last online yesterday", words(zone, "2026-11-10T08:00", "2026-11-11T23:59"))
            assertEquals("$zone", "Last online 3 days ago", words(zone, "2026-11-09T23:30", "2026-11-12T00:30"))
            // Across the end of a month.
            assertEquals("$zone", "Last online yesterday", words(zone, "2026-02-28T00:10", "2026-03-01T00:30"))
            assertEquals("$zone", "Last online 2 days ago", words(zone, "2026-02-27T23:59", "2026-03-01T00:30"))
            // Six calendar days is still words; seven is the date.
            assertEquals("$zone", "Last online 6 days ago", words(zone, "2026-11-05T00:01", "2026-11-11T23:59"))
            val weekAgo = at(zone, "2026-11-04T23:59")
            assertEquals("$zone", "Last online ${dateLabel(weekAgo)}", lastOnlineWords(weekAgo, at(zone, "2026-11-11T00:01"), zone))
        }
        // The 25-hour day when New York's clocks go back: a full day passes within one date, so it is said in hours, not "0 days ago".
        val ny = ZoneId.of("America/New_York")
        assertEquals("Last online 24 hr ago", words(ny, "2026-11-01T00:00", "2026-11-01T23:30"))
    }

    @Test
    fun saidOnlyOfSomebodyOfflineWhenTheServerSharesIt() {
        assertTrue(showsLastOnline("offline", isBot = false, mine = false, lastActiveMs = now))
        for (status in listOf("online", "away", "dnd", null)) {
            assertFalse("$status", showsLastOnline(status, isBot = false, mine = false, lastActiveMs = now))
        }
        assertFalse("a bot", showsLastOnline("offline", isBot = true, mine = false, lastActiveMs = now))
        assertFalse("you", showsLastOnline("offline", isBot = false, mine = true, lastActiveMs = now))
        assertFalse("not shared", showsLastOnline("offline", isBot = false, mine = false, lastActiveMs = 0))
    }
}
