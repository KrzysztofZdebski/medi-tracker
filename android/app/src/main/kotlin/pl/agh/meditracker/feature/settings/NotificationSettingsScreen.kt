package pl.agh.meditracker.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.R
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.notifications.*
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.feature.addmedication.Caption

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MediTrackerApp).container
    val settings = container.settings
    // zgodę na dokładne alarmy można zmienić w ustawieniach Androida, więc sprawdzana jest po każdym powrocie
    var exactAlarms by remember { mutableStateOf(container.reminders.canScheduleExact) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exactAlarms = container.reminders.canScheduleExact }
    val preferences by settings.state.collectAsStateWithLifecycle()
    val controller = remember { NotificationController(context) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val access = rememberNotificationAccess(permissionRevision)
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settings.remindersEnabled = granted
        permissionRevision++
        message = if (granted) "Powiadomienia zostały włączone." else "Zgoda nie została udzielona. Możesz ją zmienić w ustawieniach Androida."
    }
    NotificationSettingsContent(preferences.remindersEnabled, access,
        onToggle = {
            if (preferences.remindersEnabled && access.allowed) settings.remindersEnabled = false
            else if (access.allowed) settings.remindersEnabled = true
            else if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { settings.remindersEnabled = true; controller.openSettings() }
        },
        onSystemSettings = { if (!access.allowed) settings.remindersEnabled = true; controller.openSettings() },
        onTest = { message = if (controller.show(TEST_ID, "MedTracker — powiadomienie testowe", "Powiadomienia działają. Tutaj pojawią się przypomnienia o Twoich lekach.")) "Wysłano powiadomienie testowe. Sprawdź panel powiadomień telefonu." else "Android blokuje powiadomienia. Zezwól na nie w ustawieniach." },
        onBack = onBack, onOpenSettings = onOpenSettings, message = message,
        exactAlarmsAllowed = exactAlarms,
        onAllowExactAlarms = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
            }
        },
    )
}

private const val TEST_ID = 1001

@Composable
fun NotificationSettingsContent(enabled: Boolean, access: NotificationAccess, onToggle: () -> Unit, onSystemSettings: () -> Unit, onTest: () -> Unit, onBack: () -> Unit, onOpenSettings: () -> Unit, message: String? = null, exactAlarmsAllowed: Boolean = true, onAllowExactAlarms: () -> Unit = {}) {
    val active = enabled && access.allowed
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("Powiadomienia", onBack = onBack, designIcons = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DesignBadge(R.raw.design_bell_44)
            Spacer(Modifier.height(24.dp))
            Text(if (active) "Powiadomienia\nsą włączone" else "Powiadomienia\nsą wyłączone", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Text(if (active) "Otrzymasz przypomnienia o zaplanowanych i odroczonych dawkach." else "Włącz przypomnienia i zezwól aplikacji na ich wyświetlanie w ustawieniach Androida.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            if (!active) AppAlert("Przypomnienia nie są aktywne", "Plan i historia pozostają dostępne w aplikacji.", AlertTone.Warning)
            if (active && !exactAlarmsAllowed) {
                AppAlert("Przypomnienia mogą się spóźniać", "Bez zgody na dokładne alarmy Android może opóźnić powiadomienie o kilka minut.", AlertTone.Info)
                SecondaryButton("Zezwól na dokładne godziny", onAllowExactAlarms)
            }
            if (active) Caption("Android może opóźniać powiadomienia w trybie oszczędzania baterii.")
            message?.let { AppAlert("Powiadomienia", it, AlertTone.Info) }
            if (!access.allowed) {
                PrimaryButton("Otwórz ustawienia Android", onSystemSettings)
                SecondaryButton("Poproś o zgodę", onToggle)
            } else {
                PrimaryButton(if (active) "Wyłącz przypomnienia" else "Włącz przypomnienia", onToggle)
                SecondaryButton("Ustawienia powiadomień Android", onSystemSettings)
                if (active) SecondaryButton("Wyślij powiadomienie testowe", onTest)
            }
            SecondaryButton("Wróć do ustawień", onOpenSettings)
        }
    }
}
