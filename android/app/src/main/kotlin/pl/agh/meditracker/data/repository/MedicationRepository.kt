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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class MedicationRepository {
    private val _medications = MutableStateFlow<List<Medication>>(emptyList())
    val medications: StateFlow<List<Medication>> = _medications

    private val logs = MutableStateFlow<List<DoseLog>>(emptyList())

    fun dosesFor(date: LocalDate): Flow<List<Dose>> = combine(_medications, logs) { meds, logs ->
        meds.filter { it.schedule.appliesTo(date) }
            .flatMap { med ->
                med.schedule.times.map { time ->
                    val log = logs.find { it.medicationId == med.id && it.date == date && it.time == time }
                    Dose(med, date, time, log?.status ?: DoseStatus.Pending, log?.snoozedUntil)
                }
            }
            .sortedBy { it.time }
    }

    fun add(medication: Medication): Medication {
        val saved = medication.copy(id = UUID.randomUUID().toString())
        _medications.update { it + saved }
        return saved
    }

    fun refill(medicationId: String, amount: Int) = changeStock(medicationId, amount)

    fun setDoseStatus(
        medicationId: String,
        date: LocalDate,
        time: LocalTime,
        status: DoseStatus,
        snoozedUntil: LocalTime? = null,
    ) {
        val previous = logs.value.find { it.medicationId == medicationId && it.date == date && it.time == time }
        val qty = _medications.value.find { it.id == medicationId }?.schedule?.doseQty ?: return
        if (previous?.status == DoseStatus.Taken) changeStock(medicationId, qty)
        if (status == DoseStatus.Taken) changeStock(medicationId, -qty)
        logs.update { list ->
            val rest = list - setOfNotNull(previous)
            if (status == DoseStatus.Pending) rest else rest + DoseLog(medicationId, date, time, status, snoozedUntil)
        }
    }

    private fun changeStock(medicationId: String, delta: Int) = _medications.update { list ->
        list.map { if (it.id == medicationId) it.copy(stock = (it.stock + delta).coerceAtLeast(0)) else it }
    }
}
