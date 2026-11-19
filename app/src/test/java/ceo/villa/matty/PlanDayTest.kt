package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.JoinPill
import ceo.villa.matty.plugins.villanet.joinPillOf
import ceo.villa.matty.plugins.villanet.planDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanDayTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun daysAreCalendarDaysNotHoursFromNow() {
        val now = at(16, 23, 0)
        // Half an hour from now is still today; an hour and a half is tomorrow.
        assertEquals("Today", planDay(at(16, 23, 30), now, zone))
        assertEquals("Tomorrow", planDay(at(17, 0, 30), now, zone))
        // Late tomorrow is more than a day away in hours, and still tomorrow.
        assertEquals("Tomorrow", planDay(at(17, 23, 59), now, zone))
        // Early this morning (a plan already under way) is today, not "tomorrow".
        assertEquals("Today", planDay(at(16, 0, 5), now, zone))
    }

    @Test
    fun theComingWeekIsNamedByWeekday() {
        val now = at(16, 12)
        // 2026-11-19 is a Thursday.
        assertEquals(java.time.DayOfWeek.THURSDAY.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), planDay(at(19, 20), now, zone))
    }

    @Test
    fun somebodyInAPlanCanAlwaysLeaveIt() {
        // Linked: join, or leave; a full plan offers no Join, but still lets its members go.
        assertEquals(JoinPill.Join, joinPillOf(mine = false, linked = true, full = false, joined = false))
        assertEquals(JoinPill.Leave, joinPillOf(mine = false, linked = true, full = false, joined = true))
        assertEquals(JoinPill.Full, joinPillOf(mine = false, linked = true, full = true, joined = false))
        assertEquals(JoinPill.Leave, joinPillOf(mine = false, linked = true, full = true, joined = true))
        // No longer linked (let in, locked, or not known yet): leaving is open to anybody signed in.
        assertEquals(JoinPill.Leave, joinPillOf(mine = false, linked = false, full = false, joined = true))
        assertEquals(JoinPill.Leave, joinPillOf(mine = false, linked = false, full = true, joined = true))
        // Joining is not, and neither is being told it's full.
        assertEquals(JoinPill.Going, joinPillOf(mine = false, linked = false, full = false, joined = false))
        assertEquals(JoinPill.Going, joinPillOf(mine = false, linked = false, full = true, joined = false))
        // The owner's plan is theirs.
        assertEquals(JoinPill.Yours, joinPillOf(mine = true, linked = false, full = true, joined = false))
    }
}
