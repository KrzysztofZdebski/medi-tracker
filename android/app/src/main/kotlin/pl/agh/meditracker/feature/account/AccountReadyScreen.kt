package pl.agh.meditracker.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.PrimaryButton

@Composable
fun AccountReadyScreen(viewModel: AuthViewModel, onNext: () -> Unit) {
    val state by viewModel.accountState.collectAsState()
    val email = (state as? AccountState.ShowRecoveryCode)?.email ?: ""
    AccountReadyContent(email, onNext)
}

/** U04. Następny krok to zapisanie kodu odzyskiwania, więc przycisk mówi tylko „Dalej”. */
@Composable
fun AccountReadyContent(email: String, onNext: () -> Unit) {
    OnboardingLayout(
        title = "Konto gotowe",
        icon = Icons.Outlined.Check,
        headline = "Witaj w mediTracker",
        body = "Twój adres e-mail został potwierdzony. Został jeszcze jeden krok: zapisz kod odzyskiwania.",
    ) {
        Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(email, style = MaterialTheme.typography.bodyLarge)
            }
        }
        PrimaryButton("Dalej", onNext)
    }
}
