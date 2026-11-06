package pl.agh.meditracker.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _remindersEnabled = MutableStateFlow(prefs.getBoolean("reminders_enabled", false))

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    val remindersFlow: StateFlow<Boolean> = _remindersEnabled

    var remindersEnabled: Boolean
        get() = _remindersEnabled.value
        set(value) {
            prefs.edit().putBoolean("reminders_enabled", value).apply()
            _remindersEnabled.value = value
        }

    // request code-y ustawionych alarmów; AlarmManager nie pozwala ich wylistować, a trzeba je umieć anulować
    var scheduledAlarms: Set<String>
        get() = prefs.getStringSet("scheduled_alarms", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("scheduled_alarms", value).apply()
}
