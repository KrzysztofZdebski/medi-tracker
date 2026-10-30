package pl.agh.meditracker.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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

class MedicationRepository(private val clock: Clock = Clock.systemDefaultZone()) {
    private val _medications = MutableStateFlow<List<Medication>>(emptyList())
    val medications: StateFlow<List<Medication>> = _medications

    private val logs = MutableStateFlow<List<DoseLog>>(emptyList())
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history
    private val schedules = mutableMapOf<String, List<Pair<LocalDateTime, Schedule>>>()

    fun update(medication: Medication) {
        val previous = _medications.value.find { it.id == medication.id } ?: return
        if (previous.schedule != medication.schedule) {
            schedules[medication.id] = schedules.getValue(medication.id) + (LocalDateTime.now(clock) to medication.schedule)
        }
        _medications.update { list -> list.map { if (it.id == medication.id) medication else it } }
    }

    fun remove(id: String) {
        _medications.update { list -> list.filterNot { it.id == id } }
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
        return saved
    }

    fun refill(medicationId: String, amount: Int) {
        if (amount > 0) changeStock(medicationId, amount)
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
        _history.update { entries ->
            val rest = entries.filterNot { it.medication.id == medicationId && it.date == date && it.time == time }
            if (status == DoseStatus.Taken || status == DoseStatus.Skipped) {
                rest + HistoryEntry(med.copy(schedule = schedule), date, time, status, LocalDateTime.now(clock), _medications.value.first { it.id == medicationId }.stock)
            } else rest
        }
        logs.update { list ->
            val rest = list - setOfNotNull(previous)
            if (status == DoseStatus.Pending) rest else rest + DoseLog(medicationId, date, time, status, snoozedUntil)
        }
    }

    private fun changeStock(medicationId: String, delta: Int) = _medications.update { list ->
        list.map { if (it.id == medicationId) it.copy(stock = (it.stock + delta).coerceAtLeast(0)) else it }
    }
}
