package pl.agh.meditracker.data.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class AppTheme(val label: String) { System("Systemowy"), Light("Jasny"), Dark("Ciemny") }

data class AppSettings(
    val remindersEnabled: Boolean = false,
    val snoozeMinutes: Int = 15,
    val theme: AppTheme = AppTheme.System,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun read() = AppSettings(
        prefs.getBoolean("reminders_enabled", false),
        prefs.getInt("snooze_minutes", 15).takeIf { it in snoozeOptions } ?: 15,
        AppTheme.entries.find { it.name == prefs.getString("theme", null) } ?: AppTheme.System,
    )
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    val remindersFlow: Flow<Boolean> = state.map { it.remindersEnabled }.distinctUntilChanged()

    var remindersEnabled: Boolean
        get() = state.value.remindersEnabled
        set(value) {
            prefs.edit().putBoolean("reminders_enabled", value).apply()
            _state.value = read()
        }

    var snoozeMinutes: Int
        get() = state.value.snoozeMinutes
        set(value) {
            require(value in snoozeOptions)
            prefs.edit().putInt("snooze_minutes", value).apply()
            _state.value = read()
        }

    var theme: AppTheme
        get() = state.value.theme
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
            _state.value = read()
        }

    // request code-y ustawionych alarmów; AlarmManager nie pozwala ich wylistować, a trzeba je umieć anulować
    var scheduledAlarms: Set<String>
        get() = prefs.getStringSet("scheduled_alarms", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("scheduled_alarms", value).apply()

    companion object { val snoozeOptions = listOf(10, 15, 30, 60) }
}
