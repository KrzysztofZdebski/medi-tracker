package pl.agh.meditracker.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseLog
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.model.Schedule
import java.time.LocalDateTime
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// Stan w pamięci (StateFlow dla UI) z zapisem do bazy: każda zmiana trafia do store po kolei, poza wątkiem UI.
// Bez store (testy) repozytorium działa tylko w pamięci.
class MedicationRepository(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val store: MedicationStore? = null,
    initial: StoredState = StoredState(),
) {
    private val _medications = MutableStateFlow(initial.medications)
    val medications: StateFlow<List<Medication>> = _medications

    private val logs = MutableStateFlow(initial.logs)
    private val _history = MutableStateFlow(initial.history)
    val history: StateFlow<List<HistoryEntry>> = _history
    private val schedules = initial.schedules.toMutableMap()

    // jeden wątek naraz, więc zapisy wykonują się w kolejności wywołań
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun persist(write: MedicationStore.() -> Unit) {
        val store = store ?: return
        writes.launch {
            runCatching { store.write() }.onFailure { Log.e("MedicationRepository", "Zapis do bazy nie powiódł się", it) }
        }
    }

    // czeka, aż wszystkie dotychczasowe zmiany będą w bazie (np. zanim system zamknie proces po akcji z powiadomienia)
    suspend fun awaitWrites() = writes.launch {}.join()

    fun update(medication: Medication) {
        val previous = _medications.value.find { it.id == medication.id } ?: return
        if (previous.schedule != medication.schedule) {
            val versions = schedules.getValue(medication.id) + (LocalDateTime.now(clock) to medication.schedule)
            schedules[medication.id] = versions
            persist { saveSchedule(medication.id, versions.lastIndex, versions.last().first, medication.schedule) }
        }
        _medications.update { list -> list.map { if (it.id == medication.id) medication else it } }
        persist { saveMedication(medication) }
    }

    fun remove(id: String) {
        _medications.update { list -> list.filterNot { it.id == id } }
        val at = LocalDateTime.now(clock)
        persist { archiveMedication(id, at) }
    }

    fun dosesFor(date: LocalDate): Flow<List<Dose>> = combine(_medications, logs) { meds, logs ->
        meds.flatMap { med ->
            val versions = schedules.getValue(med.id)
            val times = versions.flatMap { it.second.times }.distinct()
            times.mapNotNull { time ->
                val schedule = versions.last { !it.first.isAfter(LocalDateTime.of(date, time)) }.second
                val log = logs.find { it.medicationId == med.id && it.date == date && it.time == time }
                if (log == null && (!schedule.appliesTo(date) || time !in schedule.times)) null
                else Dose(med.copy(schedule = schedule), date, time, log?.status ?: DoseStatus.Pending, log?.snoozedUntil)
            }
        }.sortedBy { it.time }
    }

    fun add(medication: Medication): Medication {
        val saved = medication.copy(id = UUID.randomUUID().toString())
        schedules[saved.id] = listOf(LocalDateTime.MIN to saved.schedule)
        _medications.update { it + saved }
        persist {
            saveMedication(saved)
            saveSchedule(saved.id, 0, LocalDateTime.MIN, saved.schedule)
        }
        return saved
    }

    fun refill(medicationId: String, amount: Int) {
        if (amount <= 0) return
        changeStock(medicationId, amount)
        val med = _medications.value.find { it.id == medicationId } ?: return
        persist { saveMedication(med) }
    }

    fun setDoseStatus(
        medicationId: String,
        date: LocalDate,
        time: LocalTime,
        status: DoseStatus,
        snoozedUntil: LocalTime? = null,
    ) {
        val previous = logs.value.find { it.medicationId == medicationId && it.date == date && it.time == time }
        val med = _medications.value.find { it.id == medicationId } ?: return
        if (previous?.status == status && previous.snoozedUntil == snoozedUntil) return
        val oldEntry = _history.value.find { it.medication.id == medicationId && it.date == date && it.time == time }
        val schedule = schedules.getValue(med.id).last { !it.first.isAfter(LocalDateTime.of(date, time)) }.second
        val qty = schedule.doseQty
        if (previous?.status == DoseStatus.Taken) changeStock(medicationId, oldEntry?.medication?.schedule?.doseQty ?: qty)
        if (status == DoseStatus.Taken) changeStock(medicationId, -qty)
        val updated = _medications.value.first { it.id == medicationId }
        val entry = if (status == DoseStatus.Taken || status == DoseStatus.Skipped) {
            HistoryEntry(med.copy(schedule = schedule), date, time, status, LocalDateTime.now(clock), updated.stock)
        } else null
        val log = if (status == DoseStatus.Pending) null else DoseLog(medicationId, date, time, status, snoozedUntil)
        _history.update { entries ->
            entries.filterNot { it.medication.id == medicationId && it.date == date && it.time == time } + listOfNotNull(entry)
        }
        logs.update { list -> list - setOfNotNull(previous) + listOfNotNull(log) }
        persist { saveDose(updated, date, time, log, entry) }
    }

    private fun changeStock(medicationId: String, delta: Int) = _medications.update { list ->
        list.map { if (it.id == medicationId) it.copy(stock = (it.stock + delta).coerceAtLeast(0)) else it }
    }
}
