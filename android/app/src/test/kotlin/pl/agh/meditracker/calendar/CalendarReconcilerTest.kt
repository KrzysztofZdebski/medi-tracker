package pl.agh.meditracker.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val zone = ZoneId.of("Europe/Warsaw")
private val eight = LocalTime.of(8, 0)
private val nine = LocalTime.of(9, 0)
private val evening = LocalTime.of(20, 0)
private val start = LocalDate.of(2026, 11, 1)

private fun med(
    id: String = "m1",
    name: String = "Metformina",
    times: List<LocalTime> = listOf(eight, evening),
    days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    startDate: LocalDate = start,
    endDate: LocalDate? = null,
) = Medication(
    id = id, name = name, strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
    schedule = Schedule(1, days, times, startDate, endDate), stock = 30, refillThreshold = 5,
)

private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

class CalendarReconcilerTest {
    private val gateway = FakeCalendarGateway()
    private val reconciler = CalendarReconciler(gateway, CalendarSyncState())
    // wtorek 10 listopada 2026, 12:00
    private var now = LocalDateTime.of(2026, 11, 10, 12, 0)

    private fun sync(vararg meds: Medication, excluded: Set<String> = emptySet(), calendarId: Long = 1) =
        reconciler.sync(calendarId, calendarEvents(meds.toList(), excluded, zone), now.atZone(zone))

    private fun values() = gateway.rows.values.map { it.values }.sortedBy { it.startMillis }

    @Test fun addsOneRecurringEventPerDoseTime() {
        sync(med())
        val (morning, night) = values()
        assertEquals("Metformina 500 mg", morning.title)
        assertTrue(morning.description.contains("Dawka: 1 tabletka"))
        assertEquals(millis(start.atTime(eight)), morning.startMillis)
        assertEquals(millis(start.atTime(evening)), night.startMillis)
        assertEquals("FREQ=DAILY", morning.rrule)
        assertEquals("PT15M", morning.duration)
        assertEquals("Europe/Warsaw", morning.timeZone)
        assertEquals(setOf(1L), gateway.rows.values.map { it.calendarId }.toSet())
        assertEquals(setOf("m1@08:00", "m1@20:00"), reconciler.state.events.keys)
    }

    @Test fun selectedDaysAndEndDateBecomeWeeklyRuleWithUntil() {
        sync(med(times = listOf(eight), days = setOf(FRIDAY, MONDAY, WEDNESDAY), endDate = LocalDate.of(2026, 11, 30)))
        val event = values().single()
        // 1 listopada to niedziela, więc pierwsza dawka wypada w poniedziałek
        assertEquals(millis(LocalDateTime.of(2026, 11, 2, 8, 0)), event.startMillis)
        assertEquals("FREQ=WEEKLY;WKST=MO;BYDAY=MO,WE,FR;UNTIL=20261130T070000Z", event.rrule)
    }

    @Test fun repeatedSyncChangesNothing() {
        sync(med())
        val before = gateway.rows.toMap()
        val writes = gateway.writes
        sync(med())
        now = now.plusDays(3)
        sync(med())
        assertEquals(writes, gateway.writes)
        assertEquals(before, gateway.rows)
    }

    @Test fun changedTimeEndsOldSeriesNowAndStartsNewOneAtNextDose() {
        sync(med())
        val ids = reconciler.state.events.mapValues { it.value.eventId }
        sync(med(times = listOf(nine, evening)))

        // 08:00 zostaje w kalendarzu jako przeszłość, zakończone teraz (12:00 w Warszawie = 11:00 UTC)
        val old = gateway.rows.getValue(ids.getValue("m1@08:00")).values
        assertEquals("FREQ=DAILY;UNTIL=20261110T110000Z", old.rrule)
        assertEquals(listOf(PastEvent(ids.getValue("m1@08:00"), "m1")), reconciler.state.past)
        // 09:00 dziś już minęła, więc nowa seria rusza jutro
        val new = gateway.rows.getValue(reconciler.state.events.getValue("m1@09:00").eventId).values
        assertEquals(millis(LocalDateTime.of(2026, 11, 11, 9, 0)), new.startMillis)
        assertEquals("FREQ=DAILY", new.rrule)
        // 20:00 bez zmian, to samo wydarzenie
        assertEquals(ids.getValue("m1@20:00"), reconciler.state.events.getValue("m1@20:00").eventId)
        assertEquals("FREQ=DAILY", gateway.rows.getValue(ids.getValue("m1@20:00")).values.rrule)
        assertEquals(3, gateway.rows.size)
    }

    @Test fun changedDaysCloseSeriesAndStartWeeklyOnFirstMatchingDay() {
        sync(med())
        val oldIds = reconciler.state.events.values.map { it.eventId }.toSet()
        sync(med(days = setOf(MONDAY, WEDNESDAY, FRIDAY)))

        oldIds.forEach { assertEquals("FREQ=DAILY;UNTIL=20261110T110000Z", gateway.rows.getValue(it).values.rrule) }
        val current = reconciler.state.events.values.associate { it.eventId to gateway.rows.getValue(it.eventId).values }
        assertTrue(current.keys.none { it in oldIds })
        // wtorek: najbliższe dawki obu godzin są w środę (20:00 dziś to wtorek, poza wybranymi dniami)
        assertEquals(
            listOf(millis(LocalDateTime.of(2026, 11, 11, 8, 0)), millis(LocalDateTime.of(2026, 11, 11, 20, 0))),
            current.values.map { it.startMillis }.sorted(),
        )
        current.values.forEach { assertEquals("FREQ=WEEKLY;WKST=MO;BYDAY=MO,WE,FR", it.rrule) }
        assertEquals(4, gateway.rows.size)
    }

    @Test fun renameUpdatesSeriesInPlace() {
        sync(med())
        val ids = gateway.rows.keys.toSet()
        sync(med(name = "Metformax"))
        assertEquals(ids, gateway.rows.keys)
        assertTrue(gateway.rows.values.all { it.values.title == "Metformax 500 mg" && it.values.rrule == "FREQ=DAILY" })
        assertTrue(reconciler.state.past.isEmpty())
    }

    @Test fun seriesThatHasNotStartedIsReplacedWithoutLeavingPast() {
        val later = LocalDate.of(2026, 12, 1)
        sync(med(startDate = later))
        sync(med(startDate = later, times = listOf(nine, evening)))
        assertTrue(reconciler.state.past.isEmpty())
        assertEquals(
            listOf(millis(later.atTime(nine)), millis(later.atTime(evening))),
            values().map { it.startMillis },
        )
    }

    @Test fun excludedMedicationLosesAllItsEventsIncludingPast() {
        sync(med(), med(id = "m2", name = "Apap", times = listOf(evening)))
        sync(med(times = listOf(nine, evening)), med(id = "m2", name = "Apap", times = listOf(evening)))
        assertEquals(1, reconciler.state.past.size)

        sync(med(times = listOf(nine, evening)), med(id = "m2", name = "Apap", times = listOf(evening)), excluded = setOf("m1"))
        assertEquals(listOf("Apap 500 mg"), values().map { it.title })
        assertTrue(reconciler.state.past.isEmpty())
        assertEquals(setOf("m2@20:00"), reconciler.state.events.keys)

        // ponowne pokazanie dodaje serie od daty rozpoczęcia harmonogramu
        sync(med(times = listOf(nine, evening)), med(id = "m2", name = "Apap", times = listOf(evening)))
        assertEquals(millis(start.atTime(nine)), values().first().startMillis)
    }

    @Test fun removedMedicationDisappearsAndOthersStayUntouched() {
        sync(med(), med(id = "m2", name = "Apap", times = listOf(evening)))
        val apap = reconciler.state.events.getValue("m2@20:00")
        val writes = gateway.writes
        sync(med(id = "m2", name = "Apap", times = listOf(evening)))
        assertEquals(writes + 2, gateway.writes)
        assertEquals(setOf(apap.eventId), gateway.rows.keys)
        assertEquals(setOf("m2@20:00"), reconciler.state.events.keys)
    }

    @Test fun removeAllDeletesEverythingTheAppCreated() {
        sync(med())
        sync(med(times = listOf(nine)))
        assertEquals(3, gateway.rows.size)
        reconciler.removeAll()
        assertTrue(gateway.rows.isEmpty())
        assertTrue(reconciler.state.isEmpty)
    }

    @Test fun changingTargetCalendarMovesEvents() {
        sync(med())
        sync(med(), calendarId = 7)
        assertEquals(2, gateway.inCalendar(7).size)
        assertTrue(gateway.inCalendar(1).isEmpty())
        assertEquals(7L, reconciler.state.calendarId)
    }

    @Test fun eventDeletedOutsideTheAppIsRecreatedOnNextContentChange() {
        sync(med())
        gateway.rows.clear()
        sync(med(name = "Metformax"))
        assertEquals(2, gateway.rows.size)
        assertTrue(gateway.rows.values.all { it.values.title == "Metformax 500 mg" })
    }

    @Test fun reconcilerResumesFromSavedState() {
        sync(med(endDate = LocalDate.of(2026, 12, 31)))
        sync(med(times = listOf(nine), endDate = LocalDate.of(2026, 12, 31)))
        // nowy obiekt z tym samym stanem (jak po restarcie procesu); zapis JSON sprawdza CalendarSyncTest
        val restored = CalendarReconciler(gateway, reconciler.state)
        val writes = gateway.writes
        restored.sync(1, calendarEvents(listOf(med(times = listOf(nine), endDate = LocalDate.of(2026, 12, 31))), emptySet(), zone), now.atZone(zone))
        assertEquals(writes, gateway.writes)
    }
}
