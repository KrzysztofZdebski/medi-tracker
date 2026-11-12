package pl.agh.meditracker.feature.account

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
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
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

// --- opakowania z ViewModelem ---

@Composable
fun ResetEmailScreen(viewModel: AuthViewModel, initialEmail: String, onBack: () -> Unit, onSent: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    ResetEmailContent(initialEmail, viewModel.busy, viewModel.error, onBack, { viewModel.requestReset(it, onSent) })
}

@Composable
fun ResetCodeScreen(viewModel: AuthViewModel, onBack: () -> Unit, onVerified: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    ResetCodeContent(
        email = viewModel.resetEmail, busy = viewModel.busy, error = viewModel.error, notice = viewModel.notice,
        onBack = onBack, onSubmit = { viewModel.verifyReset(it, onVerified) }, onResend = viewModel::resendResetCode,
    )
}

@Composable
fun ResetRecoveryScreen(viewModel: AuthViewModel, onBack: () -> Unit, onCodeAccepted: () -> Unit, onNoCode: () -> Unit) {
    LaunchedEffect(Unit) {
        viewModel.clearError()
        viewModel.cancelNoRecoveryCode()
    }
    ResetRecoveryContent(
        busy = viewModel.busy, error = viewModel.error, onBack = onBack,
        onSubmit = { viewModel.checkRecoveryCode(it, onCodeAccepted) },
        onNoCode = { viewModel.chooseNoRecoveryCode(); onNoCode() },
    )
}

@Composable
fun NewPasswordScreen(viewModel: AuthViewModel, onBack: () -> Unit, onSaved: (showCode: Boolean) -> Unit) {
    LaunchedEffect(Unit) { viewModel.clearError() }
    NewPasswordContent(
        busy = viewModel.busy, error = viewModel.error, onBack = onBack,
        onSubmit = { viewModel.setNewPassword(it.toCharArray(), onSaved) },
    )
}

// --- treść ekranów ---

/** U05. Odpowiedź serwera jest taka sama dla każdego adresu, więc ekran nie zdradza, czy konto istnieje. */
@Composable
fun ResetEmailContent(initialEmail: String, busy: Boolean, error: AuthError?, onBack: () -> Unit, onSubmit: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var attempted by remember { mutableStateOf(false) }
    AuthLayout("Odzyskaj dostęp", "Nie pamiętasz hasła?", "Podaj adres użyty podczas tworzenia konta. Wyślemy instrukcję odzyskania dostępu.", onBack, Icons.Outlined.Lock) {
        if (error != null) AuthErrorAlert(error)
        AppTextField(
            email, { email = it }, "Adres e-mail", enabled = !busy, keyboardType = KeyboardType.Email,
            error = if (attempted && !isValidEmail(email)) "Wpisz poprawny adres e-mail." else null,
        )
        PrimaryButton("Wyślij kod resetujący", { attempted = true; if (isValidEmail(email)) onSubmit(email) }, loading = busy)
        SecondaryButton("Wróć do logowania", onBack, enabled = !busy)
    }
}

/** U06. */
@Composable
fun ResetCodeContent(
    email: String, busy: Boolean, error: AuthError?, notice: String?,
    onBack: () -> Unit, onSubmit: (String) -> Unit, onResend: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val wrongCode = error == AuthError.InvalidCode
    AuthLayout(
        "Sprawdź e-mail", "Wpisz kod z wiadomości",
        "Jeśli konto dla adresu $email istnieje, wysłaliśmy kod resetujący.", onBack, Icons.Outlined.MailOutline,
    ) {
        if (error != null && !wrongCode) AuthErrorAlert(error)
        AppTextField(
            code, { code = it.filter(Char::isDigit).take(6) }, "Kod resetujący", enabled = !busy, keyboardType = KeyboardType.Number,
            error = if (wrongCode) "Kod jest nieprawidłowy lub wygasł." else null,
        )
        PrimaryButton("Dalej", { onSubmit(code) }, enabled = code.length == 6 || busy, loading = busy)
        SecondaryButton("Wyślij kod ponownie", onResend, enabled = !busy)
        if (notice != null) Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        AppTextButton("Wróć do logowania", onBack, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
    }
}

/**
 * „Wpisz kod odzyskiwania”: zły kod nie wychodzi z telefonu i można próbować dalej. „Nie mam kodu odzyskiwania”
 * najpierw pokazuje ostrzeżenie (kopia na koncie przepada, dane na telefonie zostają), dopiero potwierdzenie idzie dalej.
 */
@Composable
fun ResetRecoveryContent(busy: Boolean, error: AuthError?, onBack: () -> Unit, onSubmit: (String) -> Unit, onNoCode: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var warning by rememberSaveable { mutableStateOf(false) }
    if (warning) {
        BackHandler { warning = false }
        OnboardingLayout(
            "Reset bez kodu", Icons.Outlined.Lock, "Usunąć kopię danych z konta?",
            "Bez kodu odzyskiwania nie da się odczytać kopii zapisanej na koncie.", onBack = { warning = false },
        ) {
            AppAlert("Tej operacji nie można cofnąć", "Kopia danych na koncie zostanie trwale usunięta. Dane na tym telefonie zostaną zachowane.", AlertTone.Warning)
            DestructiveButton("Usuń kopię i ustaw nowe hasło", onNoCode)
            SecondaryButton("Wróć", { warning = false })
        }
        return
    }
    AuthLayout(
        "Kod odzyskiwania", "Wpisz kod odzyskiwania",
        "Kod dostałeś po założeniu konta. Dzięki niemu odzyskasz swoje dane po zmianie hasła.", onBack, Icons.Outlined.Lock,
    ) {
        if (error != null) AuthErrorAlert(error)
        AppTextField(code, { code = it }, "Kod odzyskiwania", enabled = !busy, placeholder = "XXXX-XXXX-XXXX-XXXX-XXXX-XX")
        PrimaryButton("Dalej · nowe hasło", { onSubmit(code) }, enabled = code.isNotBlank() || busy, loading = busy)
        AppTextButton("Nie mam kodu odzyskiwania", { warning = true }, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
    }
}

/** U07: to samo dla resetu z kodem i bez kodu; różnicę niesie ViewModel. */
@Composable
fun NewPasswordContent(busy: Boolean, error: AuthError?, onBack: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val passwordError = if (attempted && password.length < MIN_PASSWORD) "Hasło musi mieć co najmniej $MIN_PASSWORD znaków." else null
    val repeatError = if (attempted && password != repeat) "Hasła muszą być identyczne." else null
    AuthLayout("Nowe hasło", "Ustaw nowe hasło", "Wybierz nowe, unikalne hasło do swojego konta.", onBack, Icons.Outlined.Lock) {
        if (error != null) AuthErrorAlert(error)
        AppTextField(password, { password = it }, "Nowe hasło", error = passwordError, enabled = !busy, password = true)
        AppTextField(repeat, { repeat = it }, "Powtórz nowe hasło", error = repeatError, enabled = !busy, password = true)
        PrimaryButton(
            "Zapisz nowe hasło",
            { attempted = true; if (password.length >= MIN_PASSWORD && password == repeat) onSubmit(password) },
            loading = busy,
        )
        SecondaryButton("Anuluj", onBack, enabled = !busy)
    }
}

/** U08. Serwer unieważnił wszystkie sesje, więc dalej jest logowanie. */
@Composable
fun ResetDoneContent(onLogin: () -> Unit) {
    OnboardingLayout("Hasło zmienione", Icons.Outlined.Check, "Możesz się zalogować", "Twoje hasło zostało zmienione. Użyj nowego hasła podczas logowania.") {
        PrimaryButton("Przejdź do logowania", onLogin)
    }
}
