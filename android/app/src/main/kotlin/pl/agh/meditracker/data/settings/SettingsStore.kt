package pl.agh.meditracker.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _state.value = read() }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders_enabled", false)
        set(value) { prefs.edit().putBoolean("reminders_enabled", value).apply(); _state.value = read() }

    var snoozeMinutes: Int
        get() = state.value.snoozeMinutes
        set(value) {
            require(value in snoozeOptions)
            prefs.edit().putInt("snooze_minutes", value).apply()
            _state.value = read()
        }

    var theme: AppTheme
        get() = state.value.theme
        set(value) { prefs.edit().putString("theme", value.name).apply(); _state.value = read() }

    companion object { val snoozeOptions = listOf(10, 15, 30, 60) }
}
