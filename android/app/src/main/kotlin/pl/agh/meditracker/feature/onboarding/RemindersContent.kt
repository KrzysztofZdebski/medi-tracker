package pl.agh.meditracker.feature.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun RemindersContent(onBack: () -> Unit, onEnable: () -> Unit, onSkip: () -> Unit) {
    OnboardingLayout(
        title = "Przypomnienia",
        icon = Icons.Outlined.Notifications,
        headline = "Łatwiej pamiętać",
        body = "Włącz powiadomienia, aby otrzymywać przypomnienia o zaplanowanych dawkach.",
        onBack = onBack,
    ) {
        AppAlert("Ty wybierasz godziny", "Powiadomienia działają lokalnie na Twoim telefonie.", AlertTone.Info)
        PrimaryButton("Włącz przypomnienia", onClick = onEnable)
        SecondaryButton("Teraz nie", onClick = onSkip)
        Text(
            "System Android poprosi o zgodę. Możesz zmienić ją później w ustawieniach.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
