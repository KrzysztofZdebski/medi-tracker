package pl.agh.meditracker.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.feature.addmedication.OptionSheet
import pl.agh.meditracker.feature.addmedication.formatTime
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.R
import androidx.compose.foundation.shape.CircleShape

@Composable
fun HistoryScreen(onOpenToday: () -> Unit) {
    val model = appViewModel { HistoryViewModel(it.medications) }
    val state by model.state.collectAsStateWithLifecycle()
    HistoryContent(state, onOpenToday, model::applyFilter)
}

@Composable
fun HistoryContent(state: HistoryUiState, onOpenToday: () -> Unit, onFilter: (HistoryFilter) -> Unit) {
    var filters by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar("Historia", designIcons = true, largeTitle = true, actions = { NotificationsAction(designIcon = true) {} })
        if (!state.hasHistory) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 72.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DesignBadge(R.raw.design_history_44)
                Text("Tutaj powstanie\nTwój dziennik", style = MaterialTheme.typography.headlineMedium)
                MutedHistory("Gdy oznaczysz dawkę jako przyjętą lub pominiętą, zobaczysz ją w historii.")
                SecondaryButton("Przejdź do dzisiejszych leków", onOpenToday)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MutedHistory("Twój dziennik przyjmowania", Modifier.weight(1f))
                        IconButton(onClick = { filters = true }) { DesignIcon(R.raw.design_filter_24, 24.dp, "Filtruj historię") }
                    }
                }
                if (state.entries.isEmpty()) item {
                    Text("Brak wpisów dla wybranych filtrów", style = MaterialTheme.typography.titleMedium)
                    SecondaryButton("Wyczyść filtry", { onFilter(HistoryFilter()) })
                }
                state.entries.groupBy { it.date }.forEach { (date, entries) ->
                    item(key = date.toString()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(historyDate(date), style = MaterialTheme.typography.titleMedium)
                            HistoryCard(entries)
                        }
                    }
                }
                item { Text("Zapisujesz rzeczywiste przyjęcia i pominięcia.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (filters) HistoryFilterSheet(state, { onFilter(it); filters = false }, { filters = false })
}

@Composable
fun HistoryCard(entries: List<HistoryEntry>) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                val style = statusStyle(entry.status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.size(40.dp), shape = CircleShape, color = style.container) {
                        Box(contentAlignment = Alignment.Center) {
                            if (entry.status == DoseStatus.Taken) DesignIcon(R.raw.design_check_20, 20.dp)
                            else Icon(style.icon, null, Modifier.size(20.dp), tint = style.content)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${formatTime(entry.time)} · ${entry.medication.name}", style = MaterialTheme.typography.bodyLarge)
                        Text("${entry.medication.unit.format(entry.medication.schedule.doseQty)} · ${style.label}", style = MaterialTheme.typography.bodyMedium, color = style.content)
                    }
                }
                if (entries.size == 1) {
                    val action = if (entry.status == DoseStatus.Taken) "Przyjęto" else "Pominięto"
                    Text("$action o ${formatTime(entry.recordedAt.toLocalTime())} · zapas po dawce: ${entry.stockAfter}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MutedHistory(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun HistoryFilterSheet(state: HistoryUiState, onApply: (HistoryFilter) -> Unit, onDismiss: () -> Unit) {
    var medication by rememberSaveable { mutableStateOf(state.filter.medicationId) }
    var status by remember { mutableStateOf(state.filter.status) }
    var days by rememberSaveable { mutableStateOf(state.filter.days) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val medications = listOf(null to "Wszystkie leki") + state.medications
    val statuses = listOf(null to "Wszystkie statusy", DoseStatus.Taken to "Przyjęto", DoseStatus.Skipped to "Pominięto")
    val ranges = listOf(null to "Cała historia", 7 to "Ostatnie 7 dni", 30 to "Ostatnie 30 dni", 90 to "Ostatnie 90 dni")
    AppBottomSheet(onDismiss, title = "Filtruj historię") {
        AppTextField(medications.find { it.first == medication }?.second.orEmpty(), {}, "Lek", onClick = { picker = "medication" }, trailingIcon = Icons.Outlined.ArrowDropDown, tall = true)
        AppTextField(statuses.first { it.first == status }.second, {}, "Status", onClick = { picker = "status" }, trailingIcon = Icons.Outlined.ArrowDropDown, tall = true)
        AppTextField(ranges.first { it.first == days }.second, {}, "Zakres dat", onClick = { picker = "days" }, trailingIcon = Icons.Outlined.ArrowDropDown, tall = true)
        PrimaryButton("Pokaż wyniki", { onApply(HistoryFilter(medication, status, days)) })
        SecondaryButton("Wyczyść filtry", { onApply(HistoryFilter()) })
    }
    when (picker) {
        "medication" -> OptionSheet("Lek", medications.map { it.second }, medications.indexOfFirst { it.first == medication }, { medication = medications[it].first }, { picker = null })
        "status" -> OptionSheet("Status", statuses.map { it.second }, statuses.indexOfFirst { it.first == status }, { status = statuses[it].first }, { picker = null })
        "days" -> OptionSheet("Zakres dat", ranges.map { it.second }, ranges.indexOfFirst { it.first == days }, { days = ranges[it].first }, { picker = null })
    }
}
