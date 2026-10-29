package pl.agh.meditracker.feature.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.ui.components.StockLevel
import java.time.DayOfWeek

data class MedicationItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val scheduleText: String,
    val stockText: String,
    val stockLevel: StockLevel,
    val stockProgress: Float,
    val daysText: String?,
)

data class MedicationsUiState(val loading: Boolean = true, val items: List<MedicationItem> = emptyList()) {
    val countText get() = "${items.size} ${medicationsWord(items.size)} w Twojej apteczce"
}

class MedicationsViewModel(repository: MedicationRepository) : ViewModel() {
    val uiState: StateFlow<MedicationsUiState> = repository.medications
        .map { meds -> MedicationsUiState(loading = false, items = meds.map { it.toItem() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationsUiState())
}

private val dayLabels = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")

internal fun medicationsWord(count: Int): String {
    val last = count % 10
    val lastTwo = count % 100
    return when {
        count == 1 -> "lek"
        last in 2..4 && lastTwo !in 12..14 -> "leki"
        else -> "leków"
    }
}

internal fun scheduleText(schedule: Schedule): String {
    val days = if (schedule.isDaily) "Codziennie" else DayOfWeek.entries
        .filter { it in schedule.days }
        .joinToString(", ") { dayLabels[it.ordinal] }
    val times = schedule.times.sorted().map { "%02d:%02d".format(it.hour, it.minute) }
    val timesText = if (times.size < 2) times.joinToString() else times.dropLast(1).joinToString(", ") + " i " + times.last()
    return listOf(days, timesText).filter { it.isNotEmpty() }.joinToString(" · ")
}

internal fun Medication.toItem(): MedicationItem {
    val days = daysLeft
    return MedicationItem(
        id = id,
        name = name,
        subtitle = listOf(strength, form.lowercase()).filter { it.isNotBlank() }.joinToString(" · "),
        scheduleText = scheduleText(schedule),
        stockText = unit.format(stock),
        stockLevel = when {
            stock == 0 -> StockLevel.Empty
            stock <= refillThreshold -> StockLevel.Low
            else -> StockLevel.Normal
        },
        stockProgress = if (stock == 0 || days == null) 0f else (days / 30f).coerceIn(0f, 1f),
        daysText = days?.let { "Około $it ${if (it == 1) "dzień" else "dni"} stosowania" },
    )
}
