package pl.agh.meditracker.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    val state by viewModel.accountState.collectAsState()
    // po wygaśnięciu sesji (U13) zostaje e-mail konta, po resecie hasła ten, który wpisano w U05
    val initialEmail = viewModel.resetEmail.ifEmpty { (state as? AccountState.SessionExpired)?.email.orEmpty() }
    LoginContent(
        initialEmail = initialEmail,
        busy = viewModel.busy,
        error = viewModel.error,
        onBack = onBack,
        onSubmit = { email, password -> viewModel.login(email, password.toCharArray(), onLoggedIn) },
        onForgotPassword = onForgotPassword,
        onCreateAccount = onCreateAccount,
        onDismissError = viewModel::clearError,
    )
}

/**
 * U02 z wariantami: U11 (trwa logowanie), U09 (złe dane), U12 (brak sieci). Wpisane dane zostają w stanie
 * ekranu, więc „Spróbuj ponownie” w U12 nie wymaga pisania od nowa.
 */
@Composable
fun LoginContent(
    initialEmail: String,
    busy: Boolean,
    error: AuthError?,
    onBack: () -> Unit,
    onSubmit: (email: String, password: String) -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onDismissError: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val submit = {
        attempted = true
        if (isValidEmail(email) && password.isNotEmpty()) onSubmit(email, password)
    }

    when {
        busy -> OnboardingLayout("Logowanie", Icons.Outlined.Lock, "Logowanie…", "Sprawdzamy dane Twojego konta.") {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainer)
            PrimaryButton("Logowanie…", {}, loading = true)
        }
        error == AuthError.Network -> OnboardingLayout(
            "Brak połączenia", Icons.Outlined.WifiOff, "Nie można się zalogować",
            "Sprawdź połączenie z internetem i spróbuj ponownie.", onBack = onDismissError,
        ) {
            PrimaryButton("Spróbuj ponownie", { submit() })
            SecondaryButton("Wróć do logowania", onDismissError)
        }
        else -> {
            val failed = error != null
            AuthLayout(
                title = "Zaloguj się",
                headline = if (failed) "Spróbuj ponownie" else "Dobrze Cię widzieć",
                body = if (failed) "Sprawdź dane logowania do swojego konta." else "Zaloguj się, aby otworzyć swój plan leków.",
                onBack = onBack,
            ) {
                if (error != null) AuthErrorAlert(error)
                AppTextField(
                    email, { email = it }, "Adres e-mail",
                    error = if (attempted && !isValidEmail(email)) "Wpisz poprawny adres e-mail." else null,
                    keyboardType = KeyboardType.Email,
                )
                AppTextField(password, { password = it }, "Hasło", password = true)
                PrimaryButton("Zaloguj się", { submit() })
                AppTextButton("Nie pamiętasz hasła?", onForgotPassword, Modifier.align(Alignment.CenterHorizontally))
                Surface(
                    Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Pierwszy raz w mediTracker?", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Utwórz konto i zaplanuj pierwszy lek.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SecondaryButton("Utwórz konto", onCreateAccount)
            }
        }
    }
}
