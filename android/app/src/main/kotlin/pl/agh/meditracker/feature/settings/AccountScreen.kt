package pl.agh.meditracker.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.account.SyncStatus
import pl.agh.meditracker.feature.account.AuthLayout
import pl.agh.meditracker.feature.account.RestoreChoiceViewModel
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.SecondaryButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BACKUP_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** T05 ze stanem z kontenera; arkusze wylogowania i usuwania konta otwierają się nad nim. */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as MediTrackerApp).container
    val state by container.account.state.collectAsState()
    val status by container.backup.status.collectAsState()
    val pendingChoice by container.backup.pendingChoice.collectAsState()
    // wspólne z banerem i arkuszami nad aplikacją, więc z zakresu aktywności, nie tej trasy
    val owner = activityViewModelOwner()
    val conflict = appViewModel(owner) { ConflictChoiceViewModel(it.backup) }
    val restoreChoice = appViewModel(owner) { RestoreChoiceViewModel(it.backup) }
    val logout = appViewModel { c -> LogoutViewModel(c.backup::logout) { c.account.decisionPending } }
    val delete = appViewModel { c -> DeleteAccountViewModel(c.account::deleteAccount) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }

    // po wylogowaniu i usunięciu konta stan staje się LoggedOut jeszcze przed nawigacją
    LaunchedEffect(state) { if (state is AccountState.LoggedOut) onSignedOut() }
    val email = when (val s = state) {
        is AccountState.LoggedIn -> s.email
        is AccountState.SessionExpired -> s.email
        else -> ""
    }
    AccountContent(
        email, status, decisionPending = pendingChoice != null,
        onBack = onBack, onChangePassword = onChangePassword,
        onLogout = { logout.reset(); sheet = "logout" },
        onDelete = { delete.reset(); sheet = "delete" },
        onRetryUpload = { conflict.retryUpload() },
        onOpenConflict = conflict::open,
        onChooseData = restoreChoice::reopen,
    )
    when (sheet) {
        "logout" -> LogoutSheet(logout, onDone = { sheet = null; onSignedOut() }, onDismiss = { sheet = null }, onChooseData = { sheet = null; restoreChoice.reopen() })
        "delete" -> DeleteAccountSheet(delete, onDone = { sheet = null; onSignedOut() }, onDismiss = { sheet = null })
    }
}

/** T05: adres, stan kopii (spec §6.3.5), zmiana hasła, wylogowanie i usunięcie konta. */
@Composable
fun AccountContent(
    email: String,
    status: SyncStatus,
    decisionPending: Boolean,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
    onRetryUpload: () -> Unit,
    onOpenConflict: () -> Unit,
    onChooseData: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colors = MaterialTheme.colorScheme
    AuthLayout("Moje konto", "Twoje konto", "Dane logowania do mediTracker.", onBack) {
        Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADRES E-MAIL", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(email, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = colors.primary)
                    Text("Adres potwierdzony", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                }
                BackupStatus(status, zone, onRetryUpload, onOpenConflict)
            }
        }
        if (decisionPending) {
            AppAlert("Dane czekają na Twoją decyzję", "Kopia nie jest wysyłana, dopóki nie wybierzesz, które dane zachować.", AlertTone.Warning)
            SecondaryButton("Wybierz, które dane zachować", onChooseData)
        }
        SecondaryButton("Zmień hasło", onChangePassword)
        DestructiveButton("Wyloguj się", onLogout)
        Text(
            "Leki i historia są szyfrowane na telefonie i zapisywane na Twoim koncie. Po wylogowaniu znikną z tego urządzenia.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
        )
        TextButton(onDelete, Modifier.align(Alignment.CenterHorizontally)) {
            Text("Usuń konto", style = MaterialTheme.typography.labelLarge, color = colors.error)
        }
    }
}

@Composable
private fun BackupStatus(status: SyncStatus, zone: ZoneId, onRetry: () -> Unit, onConflict: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = when (status) {
        is SyncStatus.Idle -> status.lastBackupAt?.let { "Ostatnia kopia: ${BACKUP_TIME.format(it.atZone(zone))}" } ?: "Kopia jeszcze nie została wysłana"
        SyncStatus.Pending -> "Kopia oczekuje na wysłanie"
        SyncStatus.Uploading -> "Wysyłanie kopii…"
        SyncStatus.Failed -> "Nie udało się wysłać kopii. Spróbujemy ponownie."
        SyncStatus.Conflict -> "Konto jest używane na innym urządzeniu"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (status is SyncStatus.Conflict) colors.error else colors.onSurfaceVariant)
    when (status) {
        SyncStatus.Failed -> AppTextButton("Spróbuj teraz", onRetry)
        SyncStatus.Conflict -> AppTextButton("Wybierz dane", onConflict)
        else -> Unit
    }
}
