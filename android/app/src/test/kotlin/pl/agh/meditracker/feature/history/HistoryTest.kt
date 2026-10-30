package pl.agh.meditracker.feature.history

import org.junit.Assert.*
import org.junit.Test
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class HistoryTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val time = LocalTime.of(8, 0)
    private val repository = MedicationRepository()
    private val medication = repository.add(Medication(
        name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(time), today.minusDays(30)), stock = 30, refillThreshold = 5,
    ))

    @Test fun editingAndDeletingMedicationPreservesRecordedIdentityDoseAndStock() {
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Taken)
        repository.update(repository.medications.value.single().copy(name = "Nowa nazwa", schedule = medication.schedule.copy(doseQty = 2)))
        repository.refill(medication.id, 30)
        repository.remove(medication.id)
        val entry = repository.history.value.single()
        assertEquals("Metformina", entry.medication.name)
        assertEquals(1, entry.medication.schedule.doseQty)
        assertEquals(29, entry.stockAfter)
        assertTrue(repository.medications.value.isEmpty())
    }

    @Test fun undoRestoresOriginalDoseQuantityAfterScheduleEdit() {
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Taken)
        repository.update(repository.medications.value.single().copy(schedule = medication.schedule.copy(doseQty = 2)))
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Pending)
        assertEquals(30, repository.medications.value.single().stock)
        assertTrue(repository.history.value.isEmpty())
    }

    @Test fun repeatedConfirmationDoesNotChangeStockOrDuplicateEntry() {
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Taken)
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Taken)
        assertEquals(29, repository.medications.value.single().stock)
        assertEquals(1, repository.history.value.size)
    }

    @Test fun filtersCombineMedicationStatusAndInclusiveDateRange() {
        val other = repository.add(medication.copy(name = "Inny lek"))
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Taken)
        repository.setDoseStatus(medication.id, today.minusDays(6), time, DoseStatus.Skipped)
        repository.setDoseStatus(medication.id, today.minusDays(7), time, DoseStatus.Skipped)
        repository.setDoseStatus(other.id, today, time, DoseStatus.Skipped)
        val entries = filterHistory(repository.history.value, HistoryFilter(medication.id, DoseStatus.Skipped, 7), today)
        assertEquals(listOf(today.minusDays(6)), entries.map { it.date })
        assertEquals(30, repository.medications.value.first { it.id == other.id }.stock)
    }

    @Test fun snoozingDoesNotCreateAnIntakeRecord() {
        repository.setDoseStatus(medication.id, today, time, DoseStatus.Snoozed, time.plusMinutes(15))
        assertTrue(repository.history.value.isEmpty())
        assertEquals(30, repository.medications.value.single().stock)
    }

    @Test fun relativeDateLabelsUsePolishAndIncludeYearForOlderEntries() {
        assertEquals("Dzisiaj · 30 października", historyDate(today, today))
        assertEquals("Wczoraj · 29 października", historyDate(today.minusDays(1), today))
        assertEquals("30 października 2025", historyDate(today.minusYears(1), today))
    }

    @Test fun scheduleChangesOnlyAffectFutureDoses() = runTest {
        val repo = MedicationRepository(Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC))
        val original = repo.add(medication.copy(schedule = medication.schedule.copy(times = listOf(time, LocalTime.of(20, 0)))))
        repo.update(original.copy(schedule = original.schedule.copy(doseQty = 2, times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))))
        val doses = repo.dosesFor(today).first()
        assertEquals(listOf(time, LocalTime.of(21, 0)), doses.map { it.time })
        assertEquals(listOf(1, 2), doses.map { it.medication.schedule.doseQty })
    }
}
