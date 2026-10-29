package pl.agh.meditracker.feature.addmedication

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun DetailsStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    var unitPicker by rememberSaveable { mutableStateOf(false) }
    val errors = state.detailsErrors
    StepColumn {
        when {
            state.showDetailsErrors ->
                AppAlert("Popraw zaznaczone pola", "Wprowadzone dane zostały zachowane.", AlertTone.Error)
            state.notFound -> AppAlert("Nie znaleziono leku", "Uzupełnij dane ręcznie.", AlertTone.Info)
        }
        Caption("Pola oznaczone * są wymagane")
        AppTextField(state.name, { viewModel.updateDetails(name = it) }, "Nazwa leku *", error = errors?.name)
        AppTextField(state.strength, { viewModel.updateDetails(strength = it) }, "Moc *", error = errors?.strength)
        AppTextField(
            value = state.unit.pickerLabel,
            onValueChange = {},
            label = "Postać i jednostka *",
            onClick = { unitPicker = true },
            trailingIcon = Icons.Outlined.ArrowDropDown,
        )
        AppTextField(state.manufacturer, { viewModel.updateDetails(manufacturer = it) }, "Producent · opcjonalnie")
        AppTextField(
            value = state.ean,
            onValueChange = { viewModel.updateDetails(ean = it) },
            label = "EAN · opcjonalnie",
            error = errors?.ean,
            keyboardType = KeyboardType.Number,
        )
        AppTextField(state.packageSize, { viewModel.updateDetails(packageSize = it) }, "Wielkość opakowania · opcjonalnie")
        if (state.showDetailsErrors) {
            PrimaryButton("Popraw dane", viewModel::submitDetails)
        } else {
            PrimaryButton("Dalej · dawkowanie", viewModel::submitDetails)
            SecondaryButton("Anuluj", viewModel::back)
        }
    }
    if (unitPicker) {
        OptionSheet(
            title = "Postać i jednostka",
            options = DoseUnit.entries.map { it.pickerLabel },
            selected = state.unit.ordinal,
            onSelect = { viewModel.selectUnit(DoseUnit.entries[it]) },
            onDismiss = { unitPicker = false },
        )
    }
}
