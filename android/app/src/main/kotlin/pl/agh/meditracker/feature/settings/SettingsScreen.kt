package pl.agh.meditracker.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.catalog.CatalogSync
import pl.agh.meditracker.reminder.SNOOZE_MINUTES
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    val model = appViewModel { SettingsViewModel(it.settings, it.catalog, version) }
    val reminders = (context.applicationContext as MediTrackerApp).container.reminders
    val state by model.state.collectAsStateWithLifecycle()
    fun checkSystem() = model.updateSystemState(
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        reminders.canScheduleExact,
    )
    // użytkownik mógł zmienić zgody w ustawieniach Androida
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checkSystem()
        reminders.refresh()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.setReminders(granted)
        checkSystem()
    }
    SettingsContent(
        state = state,
        onRemindersChange = { enabled ->
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (enabled && needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else model.setReminders(enabled)
        },
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        onAllowExactAlarms = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                )
            }
        },
        onSyncCatalog = model::syncCatalog,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onRemindersChange: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onSyncCatalog: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("Ustawienia", designIcons = true, largeTitle = true)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Przypomnienia", style = MaterialTheme.typography.titleMedium)
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Powiadomienia o dawkach", style = MaterialTheme.typography.titleMedium)
                        Muted("O godzinie z harmonogramu, z przyciskami „Przyjęte” i „Odłóż $SNOOZE_MINUTES min”.")
                    }
                    Switch(
                        checked = state.remindersEnabled,
                        onCheckedChange = onRemindersChange,
                        modifier = Modifier.semantics { contentDescription = "Powiadomienia o dawkach" },
                    )
                }
            }
            if (state.remindersEnabled && !state.notificationsAllowed) {
                AppAlert(
                    "Powiadomienia są zablokowane",
                    "Android nie pokaże przypomnień, dopóki nie zezwolisz na powiadomienia dla aplikacji.",
                    AlertTone.Warning,
                )
                SecondaryButton("Otwórz ustawienia powiadomień", onOpenNotificationSettings)
            } else if (state.remindersEnabled && !state.exactAlarmsAllowed) {
                AppAlert(
                    "Przypomnienia mogą się spóźniać",
                    "Bez zgody na dokładne alarmy Android może opóźnić powiadomienie o kilka minut.",
                    AlertTone.Info,
                )
                SecondaryButton("Zezwól na dokładne godziny", onAllowExactAlarms)
            }

            Text("Katalog leków", style = MaterialTheme.typography.titleMedium)
            SettingsCard {
                Text(
                    state.catalogAsOf?.let { "Stan na $it" } ?: "Katalog nie został jeszcze pobrany",
                    style = MaterialTheme.typography.titleMedium,
                )
                Muted("Katalog jest zapisany na telefonie, więc skanowanie działa bez internetu. Aktualizuje się przy uruchomieniu aplikacji.")
                syncMessage(state.syncResult)?.let { (text, tone) -> AppAlert(text, null, tone) }
                PrimaryButton(if (state.syncing) "Sprawdzanie…" else "Sprawdź aktualizacje", onSyncCatalog, loading = state.syncing)
            }

            Text("Prywatność", style = MaterialTheme.typography.titleMedium)
            SettingsCard {
                Muted("Nie zakładasz konta. Leki, harmonogram i historia zostają na tym telefonie. Aplikacja łączy się z internetem tylko po aktualizację katalogu leków.")
            }

            Text("O aplikacji", style = MaterialTheme.typography.titleMedium)
            SettingsCard {
                Text("MedTracker ${state.version}".trim(), style = MaterialTheme.typography.titleMedium)
                Muted(
                    "Źródło danych o lekach: Rejestr Produktów Leczniczych, URPL / CeZ" +
                        (state.catalogAsOf?.let { ", stan na $it" } ?: "") + ". Licencja CC BY 4.0.",
                )
            }
        }
    }
}

private fun syncMessage(result: CatalogSync?): Pair<String, AlertTone>? = when (result) {
    null -> null
    CatalogSync.Updated -> "Pobrano nowy katalog" to AlertTone.Success
    CatalogSync.UpToDate -> "Katalog jest aktualny" to AlertTone.Success
    CatalogSync.Unsupported -> "Nowszy katalog wymaga aktualizacji aplikacji" to AlertTone.Info
    CatalogSync.Failed -> "Nie udało się sprawdzić aktualizacji. Sprawdź połączenie z internetem." to AlertTone.Error
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
