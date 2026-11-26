package pl.agh.meditracker.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class MedicationRepositoryTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val morning = LocalTime.of(8, 0)
    private val repo = MedicationRepository()
    private val med = repo.add(
        Medication(
            name = "Metformina",
            strength = "500 mg",
            form = "Tabletki",
            unit = DoseUnit.Tablet,
            schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(20, 0), morning), today),
            stock = 30,
            refillThreshold = 5,
        )
    )

    @Test
    fun takingAndUndoingDoseUpdatesStock() = runTest {
        assertEquals(listOf(morning, LocalTime.of(20, 0)), repo.dosesFor(today).first().map { it.time })

        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        assertEquals(29, repo.medications.value.single().stock)
        assertEquals(DoseStatus.Taken, repo.dosesFor(today).first().first().status)

        repo.setDoseStatus(med.id, today, morning, DoseStatus.Pending)
        assertEquals(30, repo.medications.value.single().stock)
        assertEquals(DoseStatus.Pending, repo.dosesFor(today).first().first().status)
    }

    @Test
    fun removeThenRestoreYieldsIdenticalState() = runTest {
        val other = repo.add(med.copy(name = "Apap"))
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        // druga wersja harmonogramu (od teraz): po przywróceniu obie wersje muszą zostać
        repo.update(repo.medications.value.first().let { it.copy(schedule = it.schedule.copy(doseQty = 2)) })
        val future = LocalDate.now().plusDays(1)
        val meds = repo.medications.value
        val doses = repo.dosesFor(today).first() to repo.dosesFor(future).first()
        val history = repo.history.value

        assertEquals(med.id, repo.remove(med.id)?.id)
        assertEquals(listOf(other.id), repo.medications.value.map { it.id })
        assertTrue(repo.dosesFor(today).first().none { it.medication.id == med.id })
        // dziennik i historia zostają (historia odwołuje się do usuniętego leku)
        assertEquals(history, repo.history.value)

        assertEquals(med.id, repo.restore(med.id)?.id)
        assertEquals(meds, repo.medications.value)
        assertEquals(doses, repo.dosesFor(today).first() to repo.dosesFor(future).first())
        assertEquals(2, repo.dosesFor(future).first().first { it.medication.id == med.id }.medication.schedule.doseQty)
        assertEquals(history, repo.history.value)
        // drugie „Cofnij” niczego nie dubluje
        assertNull(repo.restore(med.id))
        assertEquals(meds, repo.medications.value)
    }

    @Test
    fun removeAllAndRestoreAllKeepOrderWithOneChangeEach() = runTest {
        listOf("B", "C", "D", "E").forEach { repo.add(med.copy(name = it)) }
        val ids = repo.medications.value.map { it.id }
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.medications.collect { seen += it.map { m -> m.name } } }
        fun names() = repo.medications.value.map { it.name }

        assertEquals(listOf("B", "D"), repo.removeAll(listOf(ids[1], ids[3], "brak")).map { it.name })
        assertEquals(listOf("Metformina", "C", "E"), names())
        repo.remove(ids[0])
        assertEquals(listOf("C", "E"), names())

        // cofnięcie w innej kolejności niż usuwanie i tak odtwarza pierwotny porządek
        assertEquals(setOf("B", "D"), repo.restoreAll(listOf(ids[1], ids[3])).map { it.name }.toSet())
        assertEquals(listOf("B", "C", "D", "E"), names())
        repo.restore(ids[0])
        assertEquals(listOf("Metformina", "B", "C", "D", "E"), names())
        // stan początkowy + po jednej emisji na każde usunięcie i przywrócenie
        assertEquals(5, seen.size)
        assertTrue(repo.restoreAll(ids).isEmpty())
    }

    @Test
    fun polishPlurals() {
        assertEquals(listOf("1 tabletka", "3 tabletki", "5 tabletek", "12 tabletek", "22 tabletki"),
            listOf(1, 3, 5, 12, 22).map(DoseUnit.Tablet::format))
    }
}
