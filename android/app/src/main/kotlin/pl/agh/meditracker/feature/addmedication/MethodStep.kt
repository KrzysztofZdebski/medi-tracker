package pl.agh.meditracker.feature.addmedication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.feature.scanner.scanBarcode
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.IconBadge
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun MethodStep(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    StepColumn {
        Caption("Wybierz sposób dodania leku")
        IconBadge(Icons.Outlined.QrCodeScanner, size = 88.dp, modifier = Modifier.padding(top = 8.dp))
        Text("Szybciej z kodem", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Zeskanuj kod kreskowy EAN z opakowania, aby wyszukać dane leku.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.primaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Polecany sposób", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = colors.primary, modifier = Modifier.size(36.dp))
                Text("Zeskanuj kod kreskowy", style = MaterialTheme.typography.titleLarge)
                Text("Sprawdź dane przed zapisaniem.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
        }
        PrimaryButton(
            "Zeskanuj kod kreskowy",
            {
                scanBarcode(
                    context,
                    onResult = { viewModel.lookup(it.gtin14) },
                    onFallback = { viewModel.showScanSheet(true) },
                )
            },
            loading = state.lookingUp && !state.scanSheet,
        )
        SecondaryButton("Dodaj ręcznie", viewModel::addManually)
    }
    if (state.scanSheet) {
        EanSheet(state.lookingUp, onDismiss = { viewModel.showScanSheet(false) }, onConfirm = viewModel::lookup)
    }
}

@Composable
private fun EanSheet(loading: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var ean by rememberSaveable { mutableStateOf("") }
    AppBottomSheet(onDismiss = onDismiss, title = "Wpisz kod EAN", icon = Icons.Outlined.QrCodeScanner) {
        Caption("Wpisz cyfry spod kodu kreskowego na opakowaniu.")
        AppTextField(
            value = ean,
            onValueChange = { ean = it.filter(Char::isDigit).take(14) },
            label = "Kod EAN",
            keyboardType = KeyboardType.Number,
            placeholder = "5901234123457",
        )
        PrimaryButton("Wyszukaj lek", { onConfirm(ean) }, enabled = isEanInput(ean), loading = loading)
        SecondaryButton("Anuluj", onDismiss)
    }
}
