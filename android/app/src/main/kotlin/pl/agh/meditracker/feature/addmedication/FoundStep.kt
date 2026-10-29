package pl.agh.meditracker.feature.addmedication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun FoundStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
    StepColumn {
        AppAlert("Znaleziono dane leku", "Sprawdź zgodność z informacjami na opakowaniu.", AlertTone.Success)
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Medication, contentDescription = null, tint = colors.primary, modifier = Modifier.size(36.dp))
                    Text(state.name, style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    listOf(state.strength, state.form).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 52.dp),
                )
                Info("Producent", state.manufacturer)
                Info("Opakowanie", state.packageSize)
                Info("EAN", state.ean)
            }
        }
        PrimaryButton("Dodaj do moich leków", viewModel::acceptFound)
        SecondaryButton("Popraw dane", viewModel::editFound)
        Caption("W następnym kroku ustawisz dawkowanie i zapas.")
    }
}

@Composable
private fun Info(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
