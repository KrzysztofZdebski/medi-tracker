package pl.agh.meditracker.feature.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun VerifyEmailScreen(viewModel: AuthViewModel, onVerified: () -> Unit, onChangeEmail: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    val state by viewModel.accountState.collectAsState()
    val email = (state as? AccountState.AwaitingVerification)?.email ?: ""
    VerifyEmailContent(
        email = email,
        busy = viewModel.busy,
        error = viewModel.error,
        notice = viewModel.notice,
        onVerify = { viewModel.verify(it, onVerified) },
        onResend = viewModel::resendCode,
        onChangeEmail = { viewModel.changeEmail(onChangeEmail) },
    )
}

/** U03; po złym kodzie wygląda jak U14 (nagłówek, błąd przy polu, „Spróbuj ponownie” i „Wyślij nowy kod”). */
@Composable
fun VerifyEmailContent(
    email: String,
    busy: Boolean,
    error: AuthError?,
    notice: String?,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val wrongCode = error == AuthError.InvalidCode
    AuthLayout(
        title = if (wrongCode) "Sprawdź kod" else "Potwierdź e-mail",
        headline = if (wrongCode) "Kod jest nieprawidłowy" else "Sprawdź swoją skrzynkę",
        body = if (wrongCode) "Wpisz aktualny kod potwierdzający swój adres e-mail." else "Wpisz kod, który wysłaliśmy na $email.",
        onBack = null,
        icon = Icons.Outlined.MailOutline,
    ) {
        if (error != null && !wrongCode) AuthErrorAlert(error)
        AppTextField(
            code, { code = it.filter(Char::isDigit).take(6) }, "Kod potwierdzający",
            error = if (wrongCode) "Kod jest nieprawidłowy lub wygasł." else null,
            enabled = !busy, keyboardType = KeyboardType.Number,
        )
        PrimaryButton(
            if (wrongCode) "Spróbuj ponownie" else "Potwierdź konto",
            onClick = { onVerify(code) }, enabled = code.length == 6 || busy, loading = busy,
        )
        SecondaryButton(if (wrongCode) "Wyślij nowy kod" else "Wyślij kod ponownie", onResend, enabled = !busy)
        if (notice != null) Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        AppTextButton("Zmień adres e-mail", onChangeEmail, androidx.compose.ui.Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
        Text("Nie widzisz wiadomości? Sprawdź folder Spam.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
