package pl.agh.meditracker.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.account.LogoutResult
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.PrimaryButton

/**
 * T06. Wylogowanie kasuje dane z telefonu (spec §6.6), więc bez [force] BackupSync oddaje [LogoutResult.Unsynced],
 * gdy ostatnie zmiany nie są w kopii. Szczególny przypadek: nierozstrzygnięta decyzja po logowaniu (R19), kiedy
 * dane telefonu mogą nie należeć do tego konta.
 */
class LogoutViewModel(
    private val logout: suspend (force: Boolean) -> LogoutResult,
    private val decisionPending: () -> Boolean,
) : ViewModel() {
    enum class Phase { Confirm, Unsynced, DecisionPending }

    var phase by mutableStateOf(Phase.Confirm)
        private set
    var busy by mutableStateOf(false)
        private set

    fun reset() {
        if (!busy) phase = Phase.Confirm
    }

    fun logout(force: Boolean, onDone: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                if (logout(force) == LogoutResult.Done) onDone()
                else phase = if (decisionPending()) Phase.DecisionPending else Phase.Unsynced
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun LogoutSheet(viewModel: LogoutViewModel, onDone: () -> Unit, onDismiss: () -> Unit, onChooseData: () -> Unit) {
    val busy = viewModel.busy
    val title = if (viewModel.phase == LogoutViewModel.Phase.Confirm) "Wylogować się?" else "Nie wszystko jest w kopii"
    AppBottomSheet({ if (!busy) onDismiss() }, title = title) {
        when (viewModel.phase) {
            LogoutViewModel.Phase.Confirm -> {
                Text(
                    "Leki i historia zostaną usunięte z tego telefonu. Kopia na koncie zostanie zachowana — zaloguj się ponownie, aby je przywrócić.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Zostań w aplikacji", onDismiss, enabled = !busy)
                DestructiveButton(if (busy) "Wylogowywanie…" else "Wyloguj się", { viewModel.logout(false, onDone) }, enabled = !busy)
            }
            LogoutViewModel.Phase.Unsynced -> {
                AppAlert("Ostatnie zmiany nie są jeszcze w kopii na koncie.", "Jeśli wylogujesz się teraz, te zmiany zostaną utracone.", AlertTone.Warning)
                PrimaryButton("Spróbuj ponownie", { viewModel.logout(false, onDone) }, enabled = !busy, loading = busy)
                DestructiveButton("Wyloguj i usuń mimo to", { viewModel.logout(true, onDone) }, enabled = !busy)
                AppTextButton("Zostań w aplikacji", onDismiss, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
            }
            LogoutViewModel.Phase.DecisionPending -> {
                AppAlert(
                    "Najpierw wybierz, które dane zachować.",
                    "Dane na tym telefonie nie są jeszcze w kopii tego konta. Po wylogowaniu znikną.",
                    AlertTone.Warning,
                )
                PrimaryButton("Wybierz, które dane zachować", onChooseData, enabled = !busy)
                DestructiveButton("Wyloguj i usuń mimo to", { viewModel.logout(true, onDone) }, enabled = !busy)
                AppTextButton("Zostań w aplikacji", onDismiss, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
            }
        }
    }
}
