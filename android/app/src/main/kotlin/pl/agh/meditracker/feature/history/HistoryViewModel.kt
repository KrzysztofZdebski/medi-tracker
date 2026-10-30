package pl.agh.meditracker.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.HistoryEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HistoryFilter(val medicationId: String? = null, val status: DoseStatus? = null, val days: Int? = null)

fun filterHistory(entries: List<HistoryEntry>, filter: HistoryFilter, today: LocalDate = LocalDate.now()) =
    entries.filter {
        (filter.medicationId == null || it.medication.id == filter.medicationId) &&
            (filter.status == null || it.status == filter.status) &&
            (filter.days == null || (!it.date.isBefore(today.minusDays(filter.days - 1L)) && !it.date.isAfter(today)))
    }.sortedWith(compareByDescending<HistoryEntry> { it.date }.thenBy { it.time })

fun historyDate(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val formatted = date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMMM" else "d MMMM yyyy", Locale.forLanguageTag("pl")))
    return when (date) {
        today -> "Dzisiaj · $formatted"
        today.minusDays(1) -> "Wczoraj · $formatted"
        else -> formatted
    }
}

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val medications: List<Pair<String, String>> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val hasHistory: Boolean = false,
)

class HistoryViewModel(repository: MedicationRepository) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter())
    val state = combine(repository.history, filter) { entries, selected ->
        HistoryUiState(filterHistory(entries, selected), entries.map { it.medication.id to it.medication.name }.distinctBy { it.first }, selected, entries.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun applyFilter(value: HistoryFilter) { filter.value = value }
}
