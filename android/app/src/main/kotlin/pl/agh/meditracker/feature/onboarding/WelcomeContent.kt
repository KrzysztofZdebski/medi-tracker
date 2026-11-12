package pl.agh.meditracker.feature.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

private val features = listOf(
    Icons.Outlined.QrCodeScanner to "Dodaj lek jednym skanem",
    Icons.Outlined.Notifications to "Pamiętaj o każdej dawce",
    Icons.Outlined.Smartphone to "Dane zostają na Twoim telefonie",
)

@Composable
fun WelcomeContent(onCreateAccount: () -> Unit, onLogin: () -> Unit, onSkip: () -> Unit) {
    OnboardingLayout(
        title = "mediTracker",
        icon = Icons.Outlined.Medication,
        headline = "Twoje leki.\nSpokojniejszy dzień.",
        body = "Zaplanuj przyjmowanie leków, pilnuj zapasu i miej swój dziennik zawsze pod ręką.",
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                features.forEach { (icon, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        PrimaryButton("Utwórz konto", onClick = onCreateAccount)
        SecondaryButton("Zaloguj się", onClick = onLogin)
        AppTextButton("Kontynuuj bez konta", onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
