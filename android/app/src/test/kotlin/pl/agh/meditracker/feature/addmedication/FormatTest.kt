package pl.agh.meditracker.feature.addmedication

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class FormatTest {
    @Test
    fun formatsDates() {
        val date = LocalDate.of(2026, 10, 22)
        assertEquals("22.10.2026", formatDate(date))
        assertEquals("Czw., 22 października", formatLongDate(date))
    }

    @Test
    fun joinsTimes() {
        val morning = LocalTime.of(8, 0)
        val noon = LocalTime.of(12, 30)
        val evening = LocalTime.of(20, 0)
        assertEquals("08:00", formatTimes(listOf(morning)))
        assertEquals("08:00 i 20:00", formatTimes(listOf(morning, evening)))
        assertEquals("08:00, 12:30 i 20:00", formatTimes(listOf(morning, noon, evening)))
    }

    @Test
    fun formatsDays() {
        assertEquals("codziennie", formatDays(DayOfWeek.entries.toSet()))
        assertEquals("Pn, Pt", formatDays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))
        assertEquals("Około 15 dni stosowania", formatDaysLeft(15))
    }
}
