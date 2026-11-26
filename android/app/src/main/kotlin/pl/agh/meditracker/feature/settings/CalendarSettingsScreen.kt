package pl.agh.meditracker.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.agh.meditracker.calendar.CALENDAR_PERMISSIONS
import pl.agh.meditracker.calendar.CalendarInfo
import pl.agh.meditracker.calendar.hasCalendarPermission
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.feature.addmedication.Caption
import pl.agh.meditracker.ui.components.*

@Composable
fun CalendarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MediTrackerApp).container
    val settings = container.settings
    val preferences by settings.calendar.collectAsStateWithLifecycle()
    // zgodę można cofnąć w ustawieniach Androida, więc sprawdzana jest po każdym powrocie
    var granted by remember { mutableStateOf(hasCalendarPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCalendarPermission(context) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    LaunchedEffect(granted) { calendars = container.calendar.calendars() }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // co zrobić po udzieleniu zgody: włączyć eksport albo usunąć wydarzenia
    var afterGrant by remember { mutableStateOf<String?>(null) }

    fun deleteAll() = scope.launch {
        message = if (container.calendar.deleteAll()) "Usunięto wydarzenia dodane przez mediTracker." else "Nie udało się usunąć wydarzeń z kalendarza."
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        granted = hasCalendarPermission(context)
        val action = afterGrant
        afterGrant = null
        if (!granted) {
            message = "Zgoda nie została udzielona. Możesz ją przyznać w ustawieniach Androida."
        } else if (action == "delete") {
            deleteAll()
        } else {
            settings.calendarEnabled = true
            message = "Dawki będą dodawane do kalendarza."
        }
    }

    CalendarSettingsContent(
        enabled = preferences.enabled, granted = granted, calendars = calendars, selectedCalendarId = preferences.calendarId, message = message,
        onToggle = {
            message = null
            when {
                preferences.enabled -> settings.calendarEnabled = false
                granted -> settings.calendarEnabled = true
                else -> { afterGrant = "enable"; launcher.launch(CALENDAR_PERMISSIONS) }
            }
        },
        onChooseCalendar = { settings.calendarId = it },
        onDelete = {
            if (granted) deleteAll() else { afterGrant = "delete"; launcher.launch(CALENDAR_PERMISSIONS) }
        },
        onSystemSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, "Otwórz ustawienia telefonu i wybierz mediTracker → Uprawnienia.", Toast.LENGTH_LONG).show() }
        },
        onBack = onBack,
    )
}

@Composable
fun CalendarSettingsContent(
    enabled: Boolean,
    granted: Boolean,
    calendars: List<CalendarInfo>,
    selectedCalendarId: Long?,
    message: String?,
    onToggle: () -> Unit,
    onChooseCalendar: (Long?) -> Unit,
    onDelete: () -> Unit,
    onSystemSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val active = enabled && granted
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("Kalendarz", onBack = onBack, designIcons = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Caption("Każda godzina dawki pojawi się w kalendarzu telefonu jako wydarzenie cykliczne. Kalendarz nie wysyła przypomnień — robi to mediTracker.")
            SettingsCard {
                SwitchRow("Dodawaj dawki do kalendarza", active, { onToggle() }, subtitle = if (active) "Włączone" else "Wyłączone")
            }
            if (!granted) {
                AppAlert("Brak dostępu do kalendarza", "Aplikacja poprosi o zgodę po włączeniu eksportu. Jeśli wcześniej jej odmówiono, przyznaj ją w ustawieniach Androida.", AlertTone.Warning)
                SecondaryButton("Otwórz ustawienia Android", onSystemSettings)
            } else {
                Caption("Dostęp do kalendarza: przyznany")
            }
            message?.let { AppAlert("Kalendarz", it, AlertTone.Info) }
            if (granted) {
                Text("Kalendarz docelowy", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                SettingsCard {
                    Column(Modifier.selectableGroup()) {
                        CalendarOption("mediTracker", "Osobny kalendarz na tym telefonie", selectedCalendarId == null) { onChooseCalendar(null) }
                        calendars.forEach { c ->
                            CalendarOption(c.name, c.account, selectedCalendarId == c.id) { onChooseCalendar(c.id) }
                        }
                    }
                }
                Caption("Po zmianie kalendarza wydarzenia zostaną przeniesione.")
            }
            DestructiveButton("Usuń wydarzenia z kalendarza", onDelete)
            Caption("Wyłącza eksport i usuwa wszystkie wydarzenia dodane przez mediTracker.")
        }
    }
}

@Composable
private fun CalendarOption(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(24.dp), tint = colors.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}
