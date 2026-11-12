package pl.agh.meditracker.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.account.AfterLogin
import pl.agh.meditracker.data.account.BackupSync
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.IconBadge
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

class RestoreChoiceViewModel(private val backup: BackupSync) : ViewModel() {
    val choice = backup.pendingChoice

    /**
     * R23: "Później" chowa pytanie, ale niczego nie rozstrzyga (decisionPending zostaje, nic nie jest wysyłane); wraca przy
     * następnym starcie aplikacji. Zapisane w ViewModelu aktywności, więc ustawienia (Task 13) mogą je otworzyć
     * ponownie przez [reopen].
     */
    var postponed by mutableStateOf(false)
        private set

    fun postpone() {
        if (!busy) postponed = true
    }

    /** Nowe pytanie (kolejne logowanie) nie dziedziczy odłożenia po poprzednim. */
    fun clearPostponed() {
        postponed = false
    }

    /**
     * Pokazuje odłożone pytanie jeszcze raz (wiersz "Wybierz, które dane zachować" w ustawieniach). Gdy decyzja
     * wcześniej się nie udała (brak sieci, nieczytelna kopia), nie ma czego pokazać, więc ponawia ją; pytanie pojawia
     * się wtedy przez pendingChoice, a błąd trafia do [error].
     */
    fun reopen() {
        postponed = false
        if (backup.pendingChoice.value != null || busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val result = backup.afterLogin()
                if (result is AfterLogin.Failed) error = result.error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.Server
            } finally {
                busy = false
            }
        }
    }
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    /** Przy błędzie sheet zostaje otwarty, a dane na telefonie bez zmian (BackupSync odpowiada za tę gwarancję). */
    fun choose(restore: Boolean) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val result = backup.resolve(restore)
                if (result is ApiResult.Err) error = result.error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.Server
            } finally {
                busy = false
            }
        }
    }
}

/**
 * Pokazuje pytanie „przywróć czy zachowaj” nad bieżącym ekranem, kiedy BackupSync ma nierozstrzygnięty wybór: zaraz po
 * logowaniu i po restarcie aplikacji z decyzją w toku (R17).
 */
@Composable
fun RestoreChoiceHost() {
    val viewModel = appViewModel { RestoreChoiceViewModel(it.backup) }
    val choice by viewModel.choice.collectAsState()
    // nowe pytanie (kolejne logowanie) nie dziedziczy odłożenia po poprzednim
    LaunchedEffect(choice == null) { if (choice == null) viewModel.clearPostponed() }
    val current = choice
    if (current != null && !viewModel.postponed) {
        RestoreChoiceSheet(current, viewModel.busy, viewModel.error, viewModel::postpone, viewModel::choose)
    }
}

/**
 * Domyślny (pierwszy) przycisk to zawsze „przywróć” albo „zacznij z pustym kontem”: nie nadpisuje niczego, czego
 * użytkownik nie wybrał wprost. „Później” (także gest i wstecz) tylko chowa pytanie: bez sieci nie może blokować aplikacji (R23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreChoiceSheet(choice: AfterLogin.NeedsChoice, busy: Boolean, error: ApiError?, onLater: () -> Unit, onChoose: (restore: Boolean) -> Unit) {
    val withBackup = choice.accountHasBackup
    val explanation = when {
        choice.fromOtherAccount && withBackup ->
            "Dane na tym telefonie pochodzą z innego konta, a Twoje konto ma już kopię zapasową. Wybierz, które dane zostaną."
        choice.fromOtherAccount ->
            "Dane na tym telefonie pochodzą z innego konta, a to konto nie ma jeszcze kopii zapasowej. Możesz zacząć z pustym kontem albo przenieść te dane na to konto."
        else -> "Na tym telefonie są leki i historia, a Twoje konto ma już kopię zapasową. Wybierz, które dane zostaną."
    }
    val restoreLabel = if (withBackup) "Przywróć dane z konta" else "Zacznij z pustym kontem"
    val keepLabel = if (withBackup) "Zachowaj dane z tego telefonu" else "Przenieś dane z tego telefonu na konto"
    val restoreNote = if (withBackup) "Dane na telefonie zostaną zastąpione danymi z konta." else "Dane z tego telefonu znikną, a konto zostanie puste."
    val keepNote = if (withBackup) "Kopia na koncie zostanie zastąpiona danymi z tego telefonu." else "Dane z tego telefonu staną się kopią na koncie."
    ModalBottomSheet(
        onDismissRequest = onLater,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(Icons.Outlined.Lock, size = 56.dp)
            Text("Co zrobić z danymi?", style = MaterialTheme.typography.titleLarge)
            Text(explanation, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) {
                AppAlert(
                    "Nie udało się",
                    if (error == ApiError.UnreadableBackup) "Nie udało się odczytać kopii z konta. Dane na telefonie pozostały bez zmian."
                    else "Sprawdź połączenie z internetem i spróbuj ponownie. Dane na telefonie pozostały bez zmian.",
                    AlertTone.Error,
                )
            }
            PrimaryButton(restoreLabel, { onChoose(true) }, loading = busy)
            Text(restoreNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(keepLabel, { onChoose(false) }, enabled = !busy)
            Text(keepNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextButton("Później", onLater, Modifier.align(Alignment.CenterHorizontally), enabled = !busy)
        }
    }
}
