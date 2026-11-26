package pl.agh.meditracker.calendar

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarSyncTest {
    private val app = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Warsaw")
    private val today = LocalDate.of(2026, 11, 10)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val gateway = FakeCalendarGateway()
    private val settings = SettingsStore(app)
    private val repository = MedicationRepository(clock)
    private var granted = true

    private val metformin = Medication(
        name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), today),
        stock = 30, refillThreshold = 5,
    )

    private fun TestScope.startSync() = CalendarSync(app, repository, settings, gateway, clock, StandardTestDispatcher(testScheduler)) { granted }
        .also { it.start(backgroundScope) }

    private fun TestScope.settle() {
        advanceTimeBy(CalendarSync.DEBOUNCE_MILLIS + 100)
        runCurrent()
    }

    @Test fun followsRepositoryAndSettings() = runTest {
        val med = repository.add(metformin)
        startSync()
        settle()
        assertTrue("wyłączony eksport niczego nie dodaje", gateway.rows.isEmpty())

        settings.calendarEnabled = true
        settle()
        assertEquals(FakeCalendarGateway.LOCAL_ID, gateway.local)
        assertEquals(2, gateway.inCalendar(FakeCalendarGateway.LOCAL_ID).size)

        // przyjęcie dawki zmienia zapas, ale nie wydarzenia
        val writes = gateway.writes
        repository.setDoseStatus(med.id, today, LocalTime.of(8, 0), DoseStatus.Taken)
        settle()
        assertEquals(writes, gateway.writes)

        settings.setShownInCalendar(med.id, false)
        settle()
        assertTrue(gateway.rows.isEmpty())

        settings.setShownInCalendar(med.id, true)
        settle()
        assertEquals(2, gateway.rows.size)

        repository.remove(med.id)
        settle()
        assertTrue(gateway.rows.isEmpty())
    }

    @Test fun turningOffDeletesEventsAndOwnCalendar() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        startSync()
        settle()
        assertEquals(2, gateway.rows.size)
        // mapowanie jest zapisane, więc nowy proces nie doda wydarzeń drugi raz
        assertEquals(2, calendarStateFromJson(settings.calendarEvents).events.size)

        settings.calendarEnabled = false
        settle()
        assertTrue(gateway.rows.isEmpty())
        assertNull(gateway.local)
        assertNull(settings.calendarEvents)
    }

    @Test fun savedMappingKeepsSyncIdempotentAcrossRestarts() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        startSync()
        settle()
        val writes = gateway.writes
        // drugi obiekt czyta to samo mapowanie z preferencji (jak po ponownym uruchomieniu aplikacji)
        startSync()
        settle()
        assertEquals(writes, gateway.writes)
        assertEquals(2, gateway.rows.size)
    }

    @Test fun chosenCalendarReceivesEventsAndOwnCalendarIsRemoved() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        startSync()
        settle()
        settings.calendarId = 7
        settle()
        assertEquals(2, gateway.inCalendar(7).size)
        assertNull(gateway.local)

        // kalendarz zniknął (np. usunięto konto): wracamy do własnego
        gateway.calendars.clear()
        gateway.rows.clear()
        settings.calendarId = 7
        startSync().refresh()
        settle()
        assertNull(settings.calendarId)
        assertEquals(2, gateway.inCalendar(FakeCalendarGateway.LOCAL_ID).size)
    }

    @Test fun revokedPermissionDisablesExportWithoutCrashing() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        granted = false
        startSync()
        settle()
        assertFalse(settings.calendarEnabled)
        assertTrue(gateway.rows.isEmpty())
    }

    @Test fun securityExceptionFromProviderDisablesExport() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        gateway.denied = true
        startSync()
        settle()
        assertFalse(settings.calendarEnabled)
    }

    @Test fun deleteAllRemovesEverythingAndTurnsExportOff() = runTest {
        repository.add(metformin)
        settings.calendarEnabled = true
        val sync = startSync()
        settle()
        assertEquals(2, gateway.rows.size)
        assertTrue(sync.deleteAll())
        assertFalse(settings.calendarEnabled)
        assertTrue(gateway.rows.isEmpty())
        assertNull(gateway.local)
        assertNull(settings.calendarEvents)

        granted = false
        assertFalse(sync.deleteAll())
    }

    @Test fun corruptedMappingIsTreatedAsEmpty() {
        assertTrue(calendarStateFromJson("{nie json").isEmpty)
        val state = CalendarSyncState(
            1, mapOf("m@08:00" to CalendarEntry(5, "m", "a", "b", 10, "FREQ=DAILY", null)), listOf(PastEvent(6, "m")),
        )
        assertEquals(state, calendarStateFromJson(state.toJson()))
    }
}
