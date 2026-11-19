package ceo.villa.matty

import ceo.villa.matty.ui.chat.dateLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DateLabelTest {
    private fun ms(date: LocalDate) = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun thisYearLeavesTheYearOut() {
        val now = LocalDate.now()
        val earlier = now.withDayOfYear(1).plusDays(((now.dayOfYear - 1) / 2).toLong())
        listOf(true, false).forEach { long ->
            assertFalse(dateLabel(ms(earlier), long).contains(now.year.toString()))
        }
    }

    @Test
    fun otherYearsShowIt() {
        val lastYear = LocalDate.now().minusYears(1).withMonth(3).withDayOfMonth(14)
        listOf(true, false).forEach { long ->
            assertTrue(dateLabel(ms(lastYear), long).contains(lastYear.year.toString()))
        }
        val nextYear = LocalDate.now().plusYears(1).withMonth(2).withDayOfMonth(3)
        assertTrue(dateLabel(ms(nextYear)).contains(nextYear.year.toString()))
    }
}
