package pl.agh.meditracker.feature.addmedication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.CatalogProduct
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class Step { Method, Found, Details, Dosage, Stock, Time, Date }

enum class Exit { Closed, Saved }

data class AddMedicationUiState(
    val step: Step = Step.Method,
    val history: List<Step> = emptyList(),
    val scanSheet: Boolean = false,
    val lookingUp: Boolean = false,
    val found: CatalogProduct? = null,
    val notFound: Boolean = false,
    val name: String = "",
    val strength: String = "",
    val unit: DoseUnit = DoseUnit.Tablet,
    val form: String = DoseUnit.Tablet.label.lowercase(),
    val manufacturer: String = "",
    val ean: String = "",
    val packageSize: String = "",
    val detailsErrors: DetailsErrors? = null,
    val doseQty: Int = 1,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val daily: Boolean = true,
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val scheduleError: String? = null,
    val editingTime: Int? = null,
    val editingEndDate: Boolean = false,
    val stock: Int = 30,
    val threshold: String = "5",
    val discardDialog: Boolean = false,
    val exit: Exit? = null,
) {
    val showDetailsErrors get() = detailsErrors?.any == true

    val hasData
        get() = found != null || listOf(name, strength, manufacturer, ean, packageSize).any { it.isNotBlank() }

    fun toMedication() = Medication(
        name = name.trim(),
        strength = strength.trim(),
        form = form,
        unit = unit,
        manufacturer = manufacturer.trim().ifEmpty { null },
        ean = ean.ifEmpty { null },
        packageSize = packageSize.trim().ifEmpty { null },
        schedule = Schedule(doseQty, days, times, startDate, endDate),
        stock = stock,
        refillThreshold = threshold.toIntOrNull() ?: 0,
    )
}

class AddMedicationViewModel(
    private val medications: MedicationRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddMedicationUiState())
    val state: StateFlow<AddMedicationUiState> = _state.asStateFlow()

    private fun go(step: Step) = _state.update { it.copy(step = step, history = it.history + it.step) }

    fun back() = _state.update { s ->
        when {
            s.hasData && s.history.lastOrNull() == Step.Method -> s.copy(discardDialog = true)
            s.history.isEmpty() -> s.copy(exit = Exit.Closed)
            else -> s.copy(step = s.history.last(), history = s.history.dropLast(1))
        }
    }

    fun dismissDiscard() = _state.update { it.copy(discardDialog = false) }

    fun discard() = _state.update { it.copy(discardDialog = false, exit = Exit.Closed) }

    fun showScanSheet(show: Boolean) = _state.update { it.copy(scanSheet = show) }

    fun addManually() = go(Step.Details)

    fun lookup(ean: String) {
        _state.update { it.copy(lookingUp = true) }
        viewModelScope.launch {
            val product = runCatching { catalog.findByEan(ean) }.getOrNull()
            _state.update { it.copy(lookingUp = false, scanSheet = false, ean = ean) }
            if (product != null) {
                val unit = unitForForm(product.form)
                _state.update {
                    it.copy(
                        found = product,
                        notFound = false,
                        name = product.name,
                        strength = product.strength.orEmpty(),
                        unit = unit,
                        form = product.form ?: unit.label.lowercase(),
                        manufacturer = product.manufacturer.orEmpty(),
                        packageSize = product.packageSize.orEmpty(),
                    )
                }
                go(Step.Found)
            } else {
                _state.update { it.copy(found = null, notFound = true) }
                go(Step.Details)
            }
        }
    }

    fun acceptFound() {
        val s = _state.value
        val errors = validateDetails(s.name, s.strength, s.ean)
        if (errors.any) {
            _state.update { it.copy(detailsErrors = errors) }
            go(Step.Details)
        } else {
            toDosage()
        }
    }

    fun editFound() = go(Step.Details)

    fun updateDetails(
        name: String = _state.value.name,
        strength: String = _state.value.strength,
        manufacturer: String = _state.value.manufacturer,
        ean: String = _state.value.ean,
        packageSize: String = _state.value.packageSize,
    ) {
        val digits = ean.filter(Char::isDigit).take(14)
        _state.update {
            it.copy(
                name = name,
                strength = strength,
                manufacturer = manufacturer,
                ean = digits,
                packageSize = packageSize,
                detailsErrors = it.detailsErrors?.let { validateDetails(name, strength, digits) },
            )
        }
    }

    fun selectUnit(unit: DoseUnit) = _state.update { it.copy(unit = unit, form = unit.label.lowercase()) }

    fun submitDetails() {
        val s = _state.value
        val errors = validateDetails(s.name, s.strength, s.ean)
        _state.update { it.copy(detailsErrors = errors) }
        if (!errors.any) toDosage()
    }

    private fun toDosage() {
        _state.update { it.copy(stock = initialStock(it.found?.packageCount, it.packageSize)) }
        go(Step.Dosage)
    }

    fun changeDose(delta: Int) = _state.update { it.copy(doseQty = (it.doseQty + delta).coerceAtLeast(1)) }

    fun setDaily(daily: Boolean) = _state.update {
        if (daily) it.copy(daily = true, days = DayOfWeek.entries.toSet(), scheduleError = null) else it.copy(daily = false)
    }

    fun toggleDay(day: DayOfWeek) = _state.update {
        val days = if (day in it.days) it.days - day else it.days + day
        it.copy(days = days, daily = days.size == 7, scheduleError = null)
    }

    fun editTime(index: Int?) {
        _state.update { it.copy(editingTime = index) }
        go(Step.Time)
    }

    fun saveTime(time: LocalTime) {
        _state.update { s ->
            val rest = s.times.filterIndexed { i, _ -> i != s.editingTime }
            s.copy(times = (rest + time).distinct().sorted(), scheduleError = null, editingTime = null)
        }
        back()
    }

    fun removeTime() {
        _state.update { s -> s.copy(times = s.times.filterIndexed { i, _ -> i != s.editingTime }, editingTime = null) }
        back()
    }

    fun editDate(end: Boolean) {
        _state.update { it.copy(editingEndDate = end) }
        go(Step.Date)
    }

    fun saveDate(date: LocalDate?) {
        _state.update { s ->
            if (s.editingEndDate) {
                s.copy(endDate = date)
            } else {
                val start = date ?: s.startDate
                s.copy(startDate = start, endDate = s.endDate?.takeIf { !it.isBefore(start) })
            }
        }
        back()
    }

    fun submitDosage() {
        val error = validateSchedule(_state.value.days, _state.value.times)
        _state.update { it.copy(scheduleError = error) }
        if (error == null) go(Step.Stock)
    }

    fun changeStock(delta: Int) = _state.update { it.copy(stock = (it.stock + delta).coerceAtLeast(0)) }

    fun setThreshold(value: String) = _state.update { it.copy(threshold = value.filter(Char::isDigit).take(4)) }

    fun save() {
        medications.add(_state.value.toMedication())
        _state.update { it.copy(exit = Exit.Saved) }
    }
}
