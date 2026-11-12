package pl.agh.meditracker.data.user

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.core.AppContainer
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.reminder.TestApp
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class UserDatabaseTest {
    @get:org.junit.Rule val drainWrites = pl.agh.meditracker.data.user.DrainAppWritesRule()
    private val app = RuntimeEnvironment.getApplication()
    private val db = inMemoryUserDatabase(app)
    private val store = RoomMedicationStore(db.dao())
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 6)
    private val clock = Clock.fixed(today.atTime(9, 0).atZone(zone).toInstant(), zone)
    private val morning = LocalTime.of(8, 0)
    private val evening = LocalTime.of(20, 0)
    private val metformina = Medication(
        name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        manufacturer = "Producent", ean = "5901234123457", packageSize = "30 tabletek",
        schedule = Schedule(1, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), listOf(morning, evening), today),
        stock = 30, refillThreshold = 5,
    )

    @After fun close() = db.close()

    private fun reopen() = MedicationRepository(clock, store, store.load())

    @Test fun restoresMedicationsDosesAndHistory() = runBlocking {
        val repo = MedicationRepository(clock, store)
        val med = repo.add(metformina)
        val removed = repo.add(metformina.copy(name = "Apap"))
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        repo.setDoseStatus(med.id, today, evening, DoseStatus.Snoozed, LocalTime.of(20, 15))
        repo.refill(med.id, 10)
        repo.remove(removed.id)
        repo.awaitWrites()

        val restored = reopen()
        assertEquals(repo.medications.value, restored.medications.value)
        assertEquals(39, restored.medications.value.single().stock)
        assertEquals(repo.dosesFor(today).first(), restored.dosesFor(today).first())
        assertEquals(repo.history.value, restored.history.value)
    }

    @Test fun historyKeepsSnapshotAndScheduleVersionsSurvive() = runBlocking {
        val repo = MedicationRepository(clock, store)
        val med = repo.add(metformina)
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        // nowy harmonogram od teraz (09:00): poranna dawka zostaje w starej wersji
        repo.update(med.copy(name = "Metformina XR", schedule = metformina.schedule.copy(doseQty = 2, times = listOf(evening))))
        repo.awaitWrites()

        val restored = reopen()
        assertEquals("Metformina", restored.history.value.single().medication.name)
        assertEquals(1, restored.history.value.single().medication.schedule.doseQty)
        val doses = restored.dosesFor(today).first()
        assertEquals(listOf(morning to DoseStatus.Taken, evening to DoseStatus.Pending), doses.map { it.time to it.status })
        assertEquals(2, doses.last().medication.schedule.doseQty)
    }

    @Test fun undoingDoseRemovesItFromDatabase() = runBlocking {
        val repo = MedicationRepository(clock, store)
        val med = repo.add(metformina)
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Pending)
        repo.awaitWrites()

        val restored = reopen()
        assertTrue(restored.history.value.isEmpty())
        assertEquals(30, restored.medications.value.single().stock)
        assertEquals(DoseStatus.Pending, restored.dosesFor(today).first().first().status)
    }

    @Test fun appContainerReadsUserDbAfterRestart() = runBlocking {
        val first = AppContainer(app)
        first.medications.add(metformina)
        first.medications.awaitWrites()
        assertEquals(listOf("Metformina"), AppContainer(app).medications.medications.value.map { it.name })
    }
}
