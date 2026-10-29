package pl.agh.meditracker.feature.addmedication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.NumberStepper
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton
import pl.agh.meditracker.ui.components.StockIndicator
import pl.agh.meditracker.ui.components.StockLevel

@Composable
fun StockStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val medication = state.toMedication()
    val level = when {
        state.stock == 0 -> StockLevel.Empty
        state.stock <= medication.refillThreshold -> StockLevel.Low
        else -> StockLevel.Normal
    }
    val capacity = maxOf(state.stock, state.found?.packageCount ?: 0, 1)
    StepColumn {
        Caption("Krok 2 z 2 · Gotowe do zapisania")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Ile ${state.unit.many} masz teraz?")
            NumberStepper(state.stock.toString(), { viewModel.changeStock(-1) }, { viewModel.changeStock(1) })
        }
        AppTextField(
            value = state.threshold,
            onValueChange = viewModel::setThreshold,
            label = "Ostrzegaj, gdy pozostanie (${state.unit.many})",
            keyboardType = KeyboardType.Number,
        )
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sprawdź swój plan", style = typography.titleLarge)
                Text("${state.name} ${state.strength}", style = typography.titleMedium)
                Text(
                    "${state.unit.format(state.doseQty)} · ${formatDays(state.days)}",
                    style = typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
                Text(formatTimes(state.times), style = typography.titleLarge, color = colors.primary)
                Caption(
                    "Od ${formatDate(state.startDate)} · " +
                        (state.endDate?.let { "do ${formatDate(it)}" } ?: "bez daty końcowej"),
                )
                StockIndicator(
                    text = state.unit.format(state.stock),
                    level = level,
                    progress = state.stock / capacity.toFloat(),
                    daysText = medication.daysLeft?.let(::formatDaysLeft),
                )
            }
        }
        PrimaryButton("Zapisz lek", viewModel::save)
        SecondaryButton("Wróć do dawkowania", viewModel::back)
    }
}
