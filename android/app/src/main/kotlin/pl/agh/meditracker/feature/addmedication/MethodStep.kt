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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.IconBadge
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun MethodStep(viewModel: AddMedicationViewModel) {
    val colors = MaterialTheme.colorScheme
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
        PrimaryButton("Zeskanuj kod kreskowy", viewModel::startScanner)
        SecondaryButton("Dodaj ręcznie", viewModel::addManually)
    }
}
