package pl.agh.meditracker.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppTextField
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.SecondaryButton

/** Usuwanie konta (spec §6.7): hasło potwierdza zamiar; dane na telefonie zostają. */
class DeleteAccountViewModel(private val delete: suspend (CharArray) -> ApiResult<Unit>) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    fun reset() {
        if (!busy) error = null
    }

    fun delete(password: CharArray, onDone: () -> Unit) {
        if (busy) return password.fill(' ')
        busy = true
        error = null
        viewModelScope.launch {
            try {
                when (val result = delete(password)) {
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
fun DeleteAccountSheet(viewModel: DeleteAccountViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val busy = viewModel.busy
    val error = viewModel.error
    AppBottomSheet({ if (!busy) onDismiss() }, title = "Usunąć konto?") {
        Text(
            "Konto i kopia danych na serwerze zostaną trwale usunięte. Dane na tym telefonie zostaną zachowane.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (error) {
            null, ApiError.InvalidCredentials -> Unit
            ApiError.Network -> AppAlert("Brak połączenia", "Sprawdź połączenie z internetem i spróbuj ponownie.", AlertTone.Error)
            ApiError.Unauthorized -> AppAlert("Sesja wygasła", "Zaloguj się ponownie, aby usunąć konto.", AlertTone.Error)
            else -> AppAlert("Coś poszło nie tak", "Spróbuj ponownie za chwilę.", AlertTone.Error)
        }
        AppTextField(
            password, { password = it }, "Hasło", Modifier,
            error = if (error == ApiError.InvalidCredentials) "Nieprawidłowe hasło" else null,
            enabled = !busy, password = true,
        )
        DestructiveButton(
            if (busy) "Usuwanie…" else "Usuń konto",
            {
                val typed = password.toCharArray()
                password = ""
                viewModel.delete(typed, onDone)
            },
            enabled = password.isNotEmpty() && !busy,
        )
        SecondaryButton("Anuluj", onDismiss, enabled = !busy)
    }
}
