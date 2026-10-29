package pl.agh.meditracker.feature.addmedication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.DaySelector
import pl.agh.meditracker.ui.components.NumberStepper
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton
import pl.agh.meditracker.ui.components.TimeChip

private val frequencies = listOf("Codziennie", "Wybrane dni")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DosageStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    var frequencyPicker by rememberSaveable { mutableStateOf(false) }
    val typography = MaterialTheme.typography
    StepColumn {
        Caption("Krok 1 z 2 · Harmonogram")
        Text("${state.name} ${state.strength}", style = typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Dawka na jedno przyjęcie")
            NumberStepper(state.unit.format(state.doseQty), { viewModel.changeDose(-1) }, { viewModel.changeDose(1) })
        }
        AppTextField(
            value = frequencies[if (state.daily) 0 else 1],
            onValueChange = {},
            label = "Częstotliwość",
            onClick = { frequencyPicker = true },
            trailingIcon = Icons.Outlined.ArrowDropDown,
        )
        Text("Dni przyjmowania", style = typography.bodyLarge)
        DaySelector(state.days, viewModel::toggleDay)
        Text("Godziny", style = typography.titleMedium)
        if (state.times.isEmpty()) {
            AppTextField(
                value = "Nie wybrano",
                onValueChange = {},
                label = "Godziny *",
                error = state.scheduleError,
                onClick = { viewModel.editTime(null) },
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.times.forEachIndexed { index, time ->
                    TimeChip(formatTime(time), { viewModel.editTime(index) }, onEdit = { viewModel.editTime(index) })
                }
            }
            state.scheduleError?.let { Text(it, style = typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        SecondaryButton("+ Dodaj godzinę", { viewModel.editTime(null) })
        AppTextField(
            value = formatDate(state.startDate),
            onValueChange = {},
            label = "Data rozpoczęcia",
            onClick = { viewModel.editDate(end = false) },
            trailingIcon = Icons.Outlined.CalendarToday,
        )
        AppTextField(
            value = state.endDate?.let(::formatDate).orEmpty(),
            onValueChange = {},
            label = "Data zakończenia · opcjonalnie",
            placeholder = "Bez daty zakończenia",
            onClick = { viewModel.editDate(end = true) },
            trailingIcon = Icons.Outlined.CalendarToday,
        )
        PrimaryButton("Dalej · zapas", viewModel::submitDosage)
    }
    if (frequencyPicker) {
        OptionSheet(
            title = "Częstotliwość",
            options = frequencies,
            selected = if (state.daily) 0 else 1,
            onSelect = { viewModel.setDaily(it == 0) },
            onDismiss = { frequencyPicker = false },
        )
    }
}
