package pl.agh.meditracker.feature.today

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TodayTextTest {
    private val date = LocalDate.of(2026, 10, 22)
    private val med = Medication(
        name = "Metformina",
        strength = "500 mg",
        form = "tabletki",
        unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), date),
        stock = 30,
        refillThreshold = 5,
    )

    private fun dose(h: Int, status: DoseStatus, snoozedUntil: LocalTime? = null) =
        Dose(med, date, LocalTime.of(h, 0), status, snoozedUntil)

    @Test
    fun relativeTime() {
        assertEquals("Za 40 minut", relativeTimeText(LocalTime.of(7, 20, 45), LocalTime.of(8, 0)))
        assertEquals("Za 1 minutę", relativeTimeText(LocalTime.of(7, 59), LocalTime.of(8, 0)))
        assertEquals("Za 3 minuty", relativeTimeText(LocalTime.of(7, 57), LocalTime.of(8, 0)))
        assertEquals("Za 2 godz.", relativeTimeText(LocalTime.of(6, 0), LocalTime.of(8, 0)))
        assertEquals("Za 1 godz. 30 min", relativeTimeText(LocalTime.of(6, 30), LocalTime.of(8, 0)))
        assertEquals("Teraz", relativeTimeText(LocalTime.of(8, 5), LocalTime.of(8, 0)))
    }

    @Test
    fun nextDoseSkipsResolvedAndUsesSnoozeTime() {
        val snoozed = dose(8, DoseStatus.Snoozed, LocalTime.of(21, 0))
        val evening = dose(20, DoseStatus.Pending)
        assertEquals(evening, nextDose(listOf(dose(6, DoseStatus.Taken), snoozed, evening)))
        assertEquals(null, nextDose(listOf(dose(6, DoseStatus.Skipped))))
    }

    @Test
    fun dateCaptionIsPolishAndCapitalized() {
        assertEquals("Czwartek, 22 października", dateCaption(date))
    }
}
