package pl.agh.meditracker.data

import org.junit.Assert.*
import org.junit.Test
import pl.agh.meditracker.data.notifications.planReminders
import pl.agh.meditracker.model.*
import java.time.*

class ReminderPlanTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val med = Medication(id = "test", name = "Lek", strength = "1 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), date), stock = 30, refillThreshold = 5)

    @Test fun onlyPendingAndSnoozedDosesArePlannedAndDuplicatesAreRemoved() {
        val doses = listOf(Dose(med, date, LocalTime.of(8, 0), DoseStatus.Pending), Dose(med, date, LocalTime.of(9, 0), DoseStatus.Taken), Dose(med, date, LocalTime.of(10, 0), DoseStatus.Skipped))
        val planned = planReminders(doses + doses, ZoneOffset.UTC)
        assertEquals(1, planned.size)
        assertEquals(date.atTime(8, 0).toInstant(ZoneOffset.UTC), planned.single().at)
    }

    @Test fun snoozedDateTakesPriorityOverOriginalScheduledTime() {
        val snoozed = Dose(med, date, LocalTime.of(23, 50), DoseStatus.Snoozed, LocalTime.of(0, 20), date.plusDays(1).atTime(0, 20))
        val planned = planReminders(listOf(snoozed), ZoneId.of("Europe/Warsaw")).single()
        assertEquals(Instant.parse("2026-11-06T23:20:00Z"), planned.at)
    }
}
