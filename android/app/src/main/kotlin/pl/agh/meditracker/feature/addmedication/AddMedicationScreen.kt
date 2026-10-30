package pl.agh.meditracker.feature.addmedication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.feature.scanner.ScannerFlow

@Composable
fun AddMedicationScreen(onClose: () -> Unit, onSaved: () -> Unit, medicationId: String? = null, editSchedule: Boolean = false) {
    val viewModel = appViewModel { c -> AddMedicationViewModel(c.medications, c.catalog, medicationId, editSchedule) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.exit) {
        when (state.exit) {
            Exit.Closed -> onClose()
            Exit.Saved -> onSaved()
            null -> Unit
        }
    }
    AddMedicationContent(state, viewModel)
}

@Composable
fun AddMedicationContent(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    BackHandler(enabled = state.exit == null) { viewModel.back() }
    if (state.step in setOf(Step.Scanner, Step.Ean, Step.Recognizing, Step.NotFound, Step.LookupError)) {
        ScannerFlow(state, viewModel)
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title(state), onBack = viewModel::back, designIcons = state.editingId != null)
        when (state.step) {
            Step.Method -> MethodStep(viewModel)
            Step.Found -> FoundStep(state, viewModel)
            Step.Details -> DetailsStep(state, viewModel)
            Step.Dosage -> DosageStep(state, viewModel)
            Step.Stock -> StockStep(state, viewModel)
            Step.Time -> TimeStep(state, viewModel)
            Step.Date -> DateStep(state, viewModel)
            else -> Unit
        }
    }
    if (state.discardDialog) {
        DiscardSheet(onContinue = viewModel::dismissDiscard, onDiscard = viewModel::discard)
    }
}

private fun title(state: AddMedicationUiState) = when (state.step) {
    Step.Method -> "Dodaj lek"
    Step.Found -> "Lek znaleziony"
    Step.Details -> if (state.editingId != null) "Edytuj lek" else if (state.showDetailsErrors) "Sprawdź dane" else "Dane leku"
    Step.Dosage -> if (state.editingId != null) "Edytuj harmonogram" else "Dawkowanie"
    Step.Stock -> "Zapas i podsumowanie"
    Step.Time -> "Wybierz godzinę"
    Step.Date -> "Wybierz datę"
    else -> "Zeskanuj kod"
}

@Composable
private fun DiscardSheet(onContinue: () -> Unit, onDiscard: () -> Unit) {
    AppBottomSheet(onDismiss = onContinue, title = "Odrzucić zmiany?") {
        Text(
            "Dane tego leku nie zostały jeszcze zapisane. Możesz wrócić do formularza i dokończyć dodawanie.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Kontynuuj edycję", onContinue)
        DestructiveButton("Odrzuć zmiany", onDiscard)
    }
}
