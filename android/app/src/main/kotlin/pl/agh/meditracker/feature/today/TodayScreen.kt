package pl.agh.meditracker.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppSnackbarHost
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.DoseCard
import pl.agh.meditracker.ui.components.EmptyState
import pl.agh.meditracker.ui.components.NotificationsAction
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun TodayScreen(onAddMedication: () -> Unit, onOpenHistory: () -> Unit) {
    val viewModel = appViewModel { container -> TodayViewModel(container.medications) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val taken = state.feedback as? TodayFeedback.Taken
    LaunchedEffect(taken) {
        if (taken == null) return@LaunchedEffect
        val result = snackbar.showSnackbar("Zapisano przyjęcie", "Cofnij", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(taken)
    }
    Scaffold(
        topBar = { AppTopBar("Dzisiaj", actions = { NotificationsAction {} }) },
        snackbarHost = { AppSnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        TodayContent(
            state = state,
            onAddMedication = onAddMedication,
            onOpenHistory = onOpenHistory,
            onOpen = viewModel::open,
            onTake = viewModel::take,
            modifier = Modifier.padding(padding),
        )
    }
    TodaySheets(
        sheet = state.sheet,
        onTake = viewModel::take,
        onSkip = viewModel::skip,
        onOpenSnooze = viewModel::openSnooze,
        onSnooze = viewModel::snooze,
        onOpenRefill = viewModel::openRefill,
        onRefill = viewModel::refill,
        onDismiss = viewModel::dismissSheet,
    )
}

@Composable
fun TodayContent(
    state: TodayUiState,
    onAddMedication: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpen: (Dose) -> Unit,
    onTake: (Dose) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) return
    val next = state.next
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(dateCaption(state.date), style = MaterialTheme.typography.bodyMedium, color = muted) }
        if (!state.hasMedications) {
            item { EmptyToday(onAddMedication) }
            return@LazyColumn
        }
        item {
            when (val feedback = state.feedback) {
                is TodayFeedback.Taken -> AppAlert(
                    "Dawka zapisana",
                    "Zapas: ${feedback.stockBefore} → ${feedback.dose.medication.unit.format(feedback.stockAfter)}. Wpis dodano do historii.",
                    AlertTone.Success,
                )
                is TodayFeedback.Skipped -> AppAlert(
                    "Dawka pominięta",
                    "Zapas bez zmian: ${feedback.dose.medication.let { it.unit.format(it.stock) }}. Zapisano w historii.",
                    AlertTone.Info,
                )
                null -> if (next != null) NextDoseCard(next, state)
            }
        }
        item { Text("Dzisiejsze leki", style = MaterialTheme.typography.titleMedium) }
        if (state.doses.isEmpty()) {
            item { Text("Na dziś nie zaplanowano żadnych dawek.", style = MaterialTheme.typography.bodyLarge, color = muted) }
        }
        items(state.doses, key = { "${it.medication.id}-${it.time}" }) { dose ->
            val med = dose.medication
            val time = if (dose.status == DoseStatus.Snoozed && dose.snoozedUntil != null) {
                "${dose.time.hhmm()} · przypomnę o ${dose.snoozedUntil.hhmm()}"
            } else {
                dose.time.hhmm()
            }
            DoseCard(
                time = time,
                name = med.name,
                details = "${med.strength} · ${med.unit.format(med.schedule.doseQty)}",
                status = dose.status,
                onTake = { onTake(dose) },
                onClick = { if (dose.status == DoseStatus.Pending || dose.status == DoseStatus.Snoozed) onOpen(dose) },
                highlighted = dose == next,
            )
        }
        if (state.feedback != null) {
            item { SecondaryButton("Zobacz historię", onOpenHistory) }
        } else {
            item {
                Text(
                    "Przypomnienia są włączone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NextDoseCard(dose: Dose, state: TodayUiState) {
    val colors = MaterialTheme.colorScheme
    val med = dose.medication
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Text("NAJBLIŻSZA DAWKA", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
            }
            Text(
                relativeTimeText(state.now, dose.dueTime),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onPrimaryContainer,
            )
            Text(
                "${dose.time.hhmm()} · ${med.name} ${med.strength}",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onPrimaryContainer,
            )
            Text(
                "${med.unit.format(med.schedule.doseQty)} · zapas: ${med.unit.format(med.stock)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun EmptyToday(onAddMedication: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyState(
            icon = Icons.Outlined.CalendarMonth,
            title = "Zacznij od pierwszego leku",
            message = "Dodaj lek i ustaw godziny. Tutaj zobaczysz swój plan na każdy dzień.",
            actionText = "Dodaj pierwszy lek",
            onAction = onAddMedication,
            modifier = Modifier.padding(top = 48.dp),
        )
        Text(
            "Dane są zapisywane na Twoim telefonie.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
