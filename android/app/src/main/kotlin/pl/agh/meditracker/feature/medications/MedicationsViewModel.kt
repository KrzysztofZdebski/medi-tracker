package pl.agh.meditracker.feature.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

// Usunięcie czekające na „Cofnij” w snackbarze. Kolejne usunięcie, zanim snackbar zniknie, dołącza do tego samego
// komunikatu, więc „Cofnij” zawsze przywraca wszystko, co zniknęło od ostatniego potwierdzenia.
data class RemovalNotice(val ids: List<String>, val names: List<String>) {
    val message get() = if (names.size == 1) "Usunięto ${names.single()}" else "Usunięto ${names.size} ${medicationsWord(names.size)}"
}

data class MedicationsUiState(
    val loading: Boolean = true,
    val items: List<MedicationItem> = emptyList(),
    // zaznaczone karty; niepusty zbiór = tryb zaznaczania
    val selected: Set<String> = emptySet(),
    val removal: RemovalNotice? = null,
    // rośnie po każdym „Cofnij”: karta przywrócona w trakcie animacji znikania wraca na miejsce po przesunięciu
    val restoreCount: Int = 0,
) {
    val countText get() = "${items.size} ${medicationsWord(items.size)} w Twojej apteczce"
    val selecting get() = selected.isNotEmpty()
}

class MedicationsViewModel(private val repository: MedicationRepository) : ViewModel() {
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val removal = MutableStateFlow<RemovalNotice?>(null)
    private val restoreCount = MutableStateFlow(0)

    val uiState: StateFlow<MedicationsUiState> = combine(repository.medications, selected, removal, restoreCount) { meds, selected, removal, restores ->
        val ids = meds.map { it.id }.toSet()
        MedicationsUiState(loading = false, items = meds.map { it.toItem() }, selected = selected intersect ids, removal = removal, restoreCount = restores)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationsUiState())

    // długie przytrzymanie karty i dotknięcia w trybie zaznaczania
    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }

    fun selectAll() {
        selected.value = repository.medications.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    // przesunięcie karty albo akcja ułatwień dostępu „Usuń”
    fun remove(id: String) = notify(repository.removeAll(listOf(id)))

    fun removeSelected() {
        val ids = selected.value
        selected.value = emptySet()
        notify(repository.removeAll(ids))
    }

    // lek usunięty na ekranie szczegółów: ten sam snackbar z „Cofnij” po powrocie na listę
    fun showRemoved(ids: List<String>) = notify(repository.recentlyRemoved(ids))

    fun undo(notice: RemovalNotice) {
        repository.restoreAll(notice.ids)
        restoreCount.update { it + 1 }
        clearRemoval(notice)
    }

    // snackbar zniknął bez „Cofnij” albo ekran opuścił kompozycję: usunięcie zostaje
    fun clearRemoval(notice: RemovalNotice) = removal.update { if (it == notice) null else it }

    private fun notify(removed: List<Medication>) {
        if (removed.isEmpty()) return
        removal.update { old ->
            val known = old?.ids.orEmpty().toSet()
            val fresh = removed.filter { it.id !in known }
            RemovalNotice(old?.ids.orEmpty() + fresh.map { it.id }, old?.names.orEmpty() + fresh.map { it.name })
        }
    }
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
