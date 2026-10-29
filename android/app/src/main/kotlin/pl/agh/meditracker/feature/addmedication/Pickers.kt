package pl.agh.meditracker.feature.addmedication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
fun OptionSheet(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss, title = title) {
        options.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(index == selected, role = Role.RadioButton) {
                        onSelect(index)
                        onDismiss()
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = index == selected, onClick = null)
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
    val initial = state.editingTime?.let { state.times.getOrNull(it) } ?: LocalTime.of(12, 0)
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    StepColumn {
        Caption("Godzina przyjęcia leku")
        TimePicker(
            state = picker,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = TimePickerDefaults.colors(
                clockDialColor = colors.surfaceContainer,
                selectorColor = colors.primary,
                timeSelectorSelectedContainerColor = colors.primaryContainer,
                timeSelectorSelectedContentColor = colors.onPrimaryContainer,
                timeSelectorUnselectedContainerColor = colors.surfaceContainer,
            ),
        )
        Caption("Format 24-godzinny", Modifier.align(Alignment.CenterHorizontally))
        PrimaryButton("Zapisz godzinę", { viewModel.saveTime(LocalTime.of(picker.hour, picker.minute)) })
        SecondaryButton("Anuluj", viewModel::back)
        if (state.editingTime != null) DestructiveButton("Usuń godzinę", viewModel::removeTime)
    }
}

private fun LocalDate.toMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
    val end = state.editingEndDate
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = (if (end) state.endDate ?: state.startDate else state.startDate).toMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !end || !utcTimeMillis.toLocalDate().isBefore(state.startDate)
        },
    )
    val selected = picker.selectedDateMillis?.toLocalDate()
    StepColumn {
        Caption(if (end) "Data zakończenia" else "Data rozpoczęcia")
        Text(selected?.let(::formatLongDate) ?: "Bez daty zakończenia", style = MaterialTheme.typography.headlineMedium)
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            DatePicker(
                state = picker,
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(containerColor = colors.surface),
            )
        }
        PrimaryButton("Zapisz datę", { viewModel.saveDate(selected) }, enabled = selected != null || end)
        SecondaryButton("Anuluj", viewModel::back)
        if (end) AppTextButton("Bez daty zakończenia", { viewModel.saveDate(null) }, Modifier.align(Alignment.CenterHorizontally))
    }
}
