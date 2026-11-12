package pl.agh.meditracker.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.PrimaryButton

@Composable
fun RegisterScreen(viewModel: AuthViewModel, onBack: () -> Unit, onRegistered: () -> Unit, onLogin: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    RegisterContent(
        busy = viewModel.busy,
        error = viewModel.error,
        onBack = onBack,
        onSubmit = { email, password -> viewModel.register(email, password.toCharArray(), onRegistered) },
        onLogin = onLogin,
    )
}

/** U01 z walidacją U10; błąd sieci i serwera (R16) pokazuje w tym samym miejscu, formularz zostaje wypełniony. */
@Composable
fun RegisterContent(
    busy: Boolean,
    error: AuthError?,
    onBack: () -> Unit,
    onSubmit: (email: String, password: String) -> Unit,
    onLogin: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val emailError = if (attempted && !isValidEmail(email)) "Wpisz poprawny adres e-mail." else null
    val passwordError = if (attempted && password.length < MIN_PASSWORD) "Hasło musi mieć co najmniej $MIN_PASSWORD znaków." else null
    val repeatError = if (attempted && password != repeat) "Hasła muszą być identyczne." else null
    val hasErrors = emailError != null || passwordError != null || repeatError != null

    AuthLayout("Utwórz konto", "Zacznij od swojego konta", "Podaj e-mail i ustaw hasło do mediTracker.", onBack) {
        if (hasErrors) {
            AppAlert("Popraw zaznaczone pola", "Sprawdź adres e-mail i oba pola hasła.", AlertTone.Error)
        } else if (error != null) {
            AuthErrorAlert(error)
        }
        AppTextField(email, { email = it }, "Adres e-mail", error = emailError, enabled = !busy, keyboardType = KeyboardType.Email)
        AppTextField(password, { password = it }, "Hasło", error = passwordError, enabled = !busy, password = true)
        AppTextField(repeat, { repeat = it }, "Powtórz hasło", error = repeatError, enabled = !busy, password = true)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Użyj długiego, unikalnego hasła.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Twoje leki są szyfrowane na telefonie — serwer nie może ich odczytać.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        PrimaryButton(
            if (hasErrors) "Popraw dane" else "Utwórz konto",
            onClick = {
                attempted = true
                if (isValidEmail(email) && password.length >= MIN_PASSWORD && password == repeat) onSubmit(email, password)
            },
            loading = busy,
        )
        AppTextButton("Mam już konto · zaloguj się", onLogin, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
    }
}
