package pl.agh.meditracker.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.Medication
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed interface TodayFeedback {
    data class Taken(val dose: Dose, val stockBefore: Int, val stockAfter: Int) : TodayFeedback
    data class Skipped(val dose: Dose) : TodayFeedback
}

sealed interface TodaySheet {
    data class Confirm(val dose: Dose) : TodaySheet
    data class Snooze(val dose: Dose) : TodaySheet
    data class LowStock(val medication: Medication) : TodaySheet
    data class NoStock(val medication: Medication) : TodaySheet
    data class Refill(val medication: Medication) : TodaySheet
}

data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val now: LocalTime = LocalTime.now(),
    val hasMedications: Boolean = false,
    val doses: List<Dose> = emptyList(),
    val feedback: TodayFeedback? = null,
    val sheet: TodaySheet? = null,
) {
    val next: Dose? get() = nextDose(doses)
}

private data class Overlay(val feedback: TodayFeedback? = null, val sheet: TodaySheet? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: MedicationRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val overlay = MutableStateFlow(Overlay())

    private val ticker = flow {
        while (true) {
            val now = LocalDateTime.now(clock)
            emit(now)
            delay(60_000L - now.second * 1000L)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val doses = ticker.map { it.toLocalDate() }.distinctUntilChanged().flatMapLatest { repository.dosesFor(it) }

    val uiState: StateFlow<TodayUiState> = combine(ticker, repository.medications, doses, overlay) { now, meds, doses, overlay ->
        TodayUiState(
            loading = false,
            date = now.toLocalDate(),
            now = now.toLocalTime(),
            hasMedications = meds.isNotEmpty(),
            doses = doses,
            feedback = overlay.feedback,
            sheet = overlay.sheet,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun open(dose: Dose) = setSheet(TodaySheet.Confirm(dose))

    fun openSnooze(dose: Dose) = setSheet(TodaySheet.Snooze(dose))

    fun openRefill(medication: Medication) = setSheet(TodaySheet.Refill(medication))

    fun dismissSheet() = setSheet(null)

    fun take(dose: Dose) {
        val med = repository.medications.value.find { it.id == dose.medication.id } ?: return
        val qty = dose.medication.schedule.doseQty
        if (med.stock < qty) return setSheet(TodaySheet.NoStock(med))
        repository.setDoseStatus(med.id, dose.date, dose.time, DoseStatus.Taken)
        val after = med.stock - qty
        overlay.value = Overlay(
            feedback = TodayFeedback.Taken(dose, med.stock, after),
            sheet = if (after in 1..med.refillThreshold) TodaySheet.LowStock(med.copy(stock = after)) else null,
        )
    }

    fun undo(feedback: TodayFeedback.Taken) {
        val dose = feedback.dose
        repository.setDoseStatus(dose.medication.id, dose.date, dose.time, DoseStatus.Pending)
        overlay.update { if (it.feedback == feedback) Overlay(sheet = it.sheet.takeUnless { s -> s is TodaySheet.LowStock }) else it }
    }

    fun skip(dose: Dose) {
        repository.setDoseStatus(dose.medication.id, dose.date, dose.time, DoseStatus.Skipped)
        overlay.value = Overlay(feedback = TodayFeedback.Skipped(dose))
    }

    fun snooze(dose: Dose, minutes: Int) {
        val until = LocalTime.now(clock).plusMinutes(minutes.toLong())
        repository.setDoseStatus(dose.medication.id, dose.date, dose.time, DoseStatus.Snoozed, until)
        dismissSheet()
    }

    fun refill(medication: Medication, amount: Int) {
        repository.refill(medication.id, amount)
        dismissSheet()
    }

    private fun setSheet(sheet: TodaySheet?) = overlay.update { it.copy(sheet = sheet) }
}
