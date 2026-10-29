package pl.agh.meditracker.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun polishPlurals() {
        assertEquals(listOf("1 tabletka", "3 tabletki", "5 tabletek", "12 tabletek", "22 tabletki"),
            listOf(1, 3, 5, 12, 22).map(DoseUnit.Tablet::format))
    }
}
