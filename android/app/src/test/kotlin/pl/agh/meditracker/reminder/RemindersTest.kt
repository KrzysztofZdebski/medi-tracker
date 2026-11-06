package pl.agh.meditracker.reminder

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

class RemindersTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val medication = Medication(
        id = "m", name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(2, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), date),
        stock = 30, refillThreshold = 5,
    )

    private fun dose(time: String, status: DoseStatus, snoozedUntil: String? = null) =
        Dose(medication, date, LocalTime.parse(time), status, snoozedUntil?.let(LocalTime::parse))

    @Test fun pendingFutureDoseGetsReminderWithText() {
        val reminder = remindersFor(listOf(dose("08:00", DoseStatus.Pending)), date.atTime(7, 0)).single()
        assertEquals(date.atTime(8, 0), reminder.at)
        assertEquals("Czas na Metformina 500 mg", reminder.title)
        assertEquals("2 tabletki · zaplanowano na 08:00", reminder.text)
    }

    @Test fun pastAndClosedDosesAreSkipped() {
        val doses = listOf(
            dose("06:00", DoseStatus.Pending),
            dose("09:00", DoseStatus.Taken),
            dose("10:00", DoseStatus.Skipped),
            dose("11:00", DoseStatus.Pending),
        )
        assertEquals(listOf(date.atTime(11, 0)), remindersFor(doses, date.atTime(7, 0)).map { it.at })
    }

    @Test fun snoozedDoseRemindsAtSnoozeTimeAndCrossesMidnight() {
        val doses = listOf(dose("08:00", DoseStatus.Snoozed, "08:15"), dose("23:50", DoseStatus.Snoozed, "00:05"))
        val reminders = remindersFor(doses, date.atTime(8, 5))
        assertEquals(listOf(date.atTime(8, 15), date.plusDays(1).atTime(0, 5)), reminders.map { it.at })
        // kod zależy od godziny z harmonogramu, więc odłożenie nadpisuje ten sam alarm
        assertEquals(reminderCode("m", date, LocalTime.of(8, 0)), reminders.first().code)
    }
}
