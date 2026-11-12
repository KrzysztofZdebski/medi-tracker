package pl.agh.meditracker.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.feature.account.AuthLayout
import pl.agh.meditracker.feature.account.MIN_PASSWORD
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

/** T07: zmiana hasła z poziomu "Moje konto" (spec §6.4). Hasła żyją tylko w polach i w wywołaniu repozytorium. */
class ChangePasswordViewModel(private val change: suspend (old: CharArray, new: CharArray) -> ApiResult<Unit>) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    fun clearError() {
        error = null
    }

    fun change(old: CharArray, new: CharArray, onDone: () -> Unit) {
        if (busy) {
            old.fill(' ')
            new.fill(' ')
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            try {
                when (val result = change(old, new)) {
                    is ApiResult.Ok -> onDone()
                    is ApiResult.Err -> error = result.error
                }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun ChangePasswordScreen(viewModel: ChangePasswordViewModel, onBack: () -> Unit, onSaved: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val busy = viewModel.busy
    val tooShort = new.length < MIN_PASSWORD
    val mismatch = new != repeat
    AuthLayout("Zmień hasło", "Ustaw nowe hasło", "Podaj obecne hasło, aby potwierdzić tę zmianę.", onBack, Icons.Outlined.Lock) {
        when (viewModel.error) {
            null -> Unit
            ApiError.InvalidCredentials -> AppAlert("Obecne hasło jest nieprawidłowe", "Sprawdź je i spróbuj ponownie.", AlertTone.Error)
            ApiError.Network -> AppAlert("Brak połączenia", "Sprawdź połączenie z internetem i spróbuj ponownie.", AlertTone.Error)
            ApiError.Unauthorized -> AppAlert("Sesja wygasła", "Zaloguj się ponownie, aby zmienić hasło.", AlertTone.Error)
            else -> AppAlert("Coś poszło nie tak", "Spróbuj ponownie za chwilę.", AlertTone.Error)
        }
        AppTextField(current, { current = it }, "Obecne hasło", Modifier, enabled = !busy, password = true)
        AppTextField(
            new, { new = it }, "Nowe hasło", Modifier,
            error = if (attempted && tooShort) "Hasło musi mieć co najmniej 10 znaków." else null,
            enabled = !busy, password = true,
        )
        AppTextField(
            repeat, { repeat = it }, "Powtórz nowe hasło", Modifier,
            error = if (attempted && !tooShort && mismatch) "Hasła muszą być identyczne." else null,
            enabled = !busy, password = true,
        )
        PrimaryButton(
            "Zapisz nowe hasło",
            {
                attempted = true
                if (current.isNotEmpty() && !tooShort && !mismatch) {
                    val old = current.toCharArray()
                    val fresh = new.toCharArray()
                    // pola czyścimy od razu po wysłaniu, także przy błędzie: hasła nie zostają w stanie ekranu
                    current = ""
                    new = ""
                    repeat = ""
                    attempted = false
                    viewModel.change(old, fresh, onSaved)
                }
            },
            enabled = !busy, loading = busy,
        )
        SecondaryButton("Anuluj", onBack, enabled = !busy)
    }
}

/** T08. */
@Composable
fun PasswordChangedContent(onBack: () -> Unit) {
    OnboardingLayout("Hasło zmienione", Icons.Outlined.Check, "Zmiana zapisana", "Hasło do Twojego konta zostało zmienione. Możesz dalej korzystać z aplikacji.") {
        PrimaryButton("Wróć do mojego konta", onBack)
    }
}
