package pl.agh.meditracker.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.account.AfterLogin
import pl.agh.meditracker.data.account.BackupSync
import pl.agh.meditracker.data.account.SyncStatus
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.feature.account.RestoreChoiceSheet
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert

/**
 * Wybór po konflikcie 409 (spec §6.3.3): to samo pytanie co po logowaniu, ale bez blokowania. Jeden egzemplarz na
 * aktywność, żeby baner, ekran "Moje konto" i [ConflictChoiceHost] pokazywały ten sam arkusz.
 */
class ConflictChoiceViewModel(private val backup: BackupSync) : ViewModel() {
    val status = backup.status

    var open by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    fun open() {
        error = null
        open = true
    }

    /** Odrzucenie niczego nie rozstrzyga: konflikt zostaje, automatyczne wysyłanie dalej stoi. */
    fun dismiss() {
        if (!busy) open = false
    }

    /** "Spróbuj teraz" w "Moje konto": ręczna próba wysłania na scope ViewModelu aktywności, nie ekranu. */
    fun retryUpload() {
        viewModelScope.launch { backup.upload() }
    }

    fun choose(restore: Boolean) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                when (val result = backup.resolve(restore)) {
                    is ApiResult.Ok -> open = false
                    is ApiResult.Err -> error = result.error
                }
            } finally {
                busy = false
            }
        }
    }
}

/** Baner nad Dzisiaj i Ustawieniami; nie blokuje aplikacji, dotknięcie otwiera wybór. */
@Composable
fun SyncConflictBanner(modifier: Modifier = Modifier) {
    val viewModel = appViewModel(activityViewModelOwner()) { ConflictChoiceViewModel(it.backup) }
    val status by viewModel.status.collectAsState()
    if (status is SyncStatus.Conflict) SyncConflictBannerContent(modifier, viewModel::open)
}

@Composable
fun SyncConflictBannerContent(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    AppAlert(
        "Konto jest używane na innym urządzeniu",
        "Kopia nie jest wysyłana. Dotknij, aby wybrać, które dane zachować.",
        AlertTone.Warning,
        modifier.clickable(role = Role.Button, onClick = onOpen),
    )
}

/** Wybór po konflikcie: zawsze "kopia na koncie istnieje" (inny telefon ją zapisał albo przejął konto). */
@Composable
fun ConflictChoiceSheet(busy: Boolean, error: ApiError?, onLater: () -> Unit, onChoose: (restore: Boolean) -> Unit) {
    RestoreChoiceSheet(AfterLogin.NeedsChoice(fromOtherAccount = false, accountHasBackup = true), busy, error, onLater, onChoose)
}

/** Arkusz konfliktu nad całą aplikacją; otwierają go baner i "Moje konto". */
@Composable
fun ConflictChoiceHost() {
    val viewModel = appViewModel(activityViewModelOwner()) { ConflictChoiceViewModel(it.backup) }
    val status by viewModel.status.collectAsState()
    // po rozstrzygnięciu (albo wylogowaniu) konflikt znika, a arkusz razem z nim
    if (viewModel.open && status is SyncStatus.Conflict) {
        ConflictChoiceSheet(viewModel.busy, viewModel.error, viewModel::dismiss, viewModel::choose)
    }
}

/**
 * ViewModel konfliktu i pytania przywróć / zachowaj muszą być jednym egzemplarzem dla całej aplikacji, a trasy
 * nawigacji mają własne ViewModelStoreOwnery. Stąd właściciel z zakresu aktywności.
 */
@Composable
internal fun activityViewModelOwner(): ViewModelStoreOwner {
    var context: Context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is ViewModelStoreOwner) return context
        context = context.baseContext
    }
    error("Brak aktywności w kontekście")
}
