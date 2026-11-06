package pl.agh.meditracker.feature.settings

import android.content.Context
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.R
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.notifications.NotificationAccess
import pl.agh.meditracker.data.notifications.NotificationController
import pl.agh.meditracker.data.settings.*
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.feature.addmedication.Caption

fun appVersion(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1"

@Composable
fun rememberNotificationAccess(refreshKey: Any? = null): NotificationAccess {
    val context = LocalContext.current
    val controller = remember(context) { NotificationController(context) }
    var access by remember { mutableStateOf(controller.access()) }
    LaunchedEffect(refreshKey) { access = controller.access() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = controller.access() }
    return access
}

@Composable
fun SettingsScreen(onNotifications: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as MediTrackerApp).container.settings
    val preferences by settings.state.collectAsStateWithLifecycle()
    val access = rememberNotificationAccess()
    SettingsContent(preferences, access, appVersion(context), onNotifications,
        onSound = { NotificationController(context).openSettings(channel = true) },
        onAbout = onAbout, onSnooze = { settings.snoozeMinutes = it }, onTheme = { settings.theme = it })
}

@Composable
fun SettingsContent(
    preferences: AppSettings, access: NotificationAccess, version: String,
    onNotifications: () -> Unit, onSound: () -> Unit, onAbout: () -> Unit,
    onSnooze: (Int) -> Unit, onTheme: (AppTheme) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("Ustawienia", designIcons = true, largeTitle = true, actions = { NotificationsAction(designIcon = true, onClick = onNotifications) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Caption("Dostosuj aplikację do siebie")
            Text("Przypomnienia", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            SettingsCard {
                SettingRow(R.raw.design_bell_24, "Powiadomienia", when { !access.allowed -> "Zablokowane w Androidzie"; preferences.remindersEnabled -> "Włączone"; else -> "Wyłączone" }, onNotifications)
                SettingRow(R.raw.design_clock_24, "Przypomnij później", "Domyślnie ${preferences.snoozeMinutes} minut", { sheet = "snooze" })
                SettingRow(R.raw.design_volume_24, "Dźwięk i wibracje", access.soundSummary, onSound)
            }
            Text("Wygląd", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            SettingsCard { SettingRow(R.raw.design_moon_24, "Motyw aplikacji", preferences.theme.label, { sheet = "theme" }) }
            Text("O aplikacji", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            SettingsCard { SettingRow(R.raw.design_info_24, "Informacje o aplikacji", "MedTracker · wersja $version", onAbout) }
            Caption("Leki i historia są zapisane lokalnie na tym urządzeniu.")
        }
    }
    when (sheet) {
        "snooze" -> SettingsChoiceSheet("Domyślne odroczenie", SettingsStore.snoozeOptions.map { "$it minut" }, SettingsStore.snoozeOptions.indexOf(preferences.snoozeMinutes), R.raw.design_clock_24,
            { onSnooze(SettingsStore.snoozeOptions[it]); sheet = null }, { sheet = null })
        "theme" -> SettingsChoiceSheet("Motyw aplikacji", AppTheme.entries.map { it.label }, preferences.theme.ordinal, R.raw.design_moon_24,
            { onTheme(AppTheme.entries[it]); sheet = null }, { sheet = null })
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(@RawRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick, color = colors.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DesignIcon(icon, 24.dp, tint = colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            DesignIcon(R.raw.design_chevron_20, 20.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsChoiceSheet(title: String, options: List<String>, current: Int, @RawRes icon: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(current) }
    val colors = MaterialTheme.colorScheme
    AppBottomSheet(onDismiss, title = title) {
        options.forEachIndexed { index, label ->
            Surface(color = if (selected == index) colors.primaryContainer else colors.surface, shape = MaterialTheme.shapes.extraSmall) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected == index, role = Role.RadioButton, onClick = { selected = index }).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DesignIcon(if (selected == index) R.raw.design_check_20 else icon, if (selected == index) 20.dp else 24.dp, tint = if (selected == index) colors.primary else colors.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        PrimaryButton("Zapisz", { onSave(selected) })
        SecondaryButton("Anuluj", onDismiss)
    }
}
