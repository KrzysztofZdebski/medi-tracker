package pl.agh.meditracker.data.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders_enabled", false)
        set(value) = prefs.edit().putBoolean("reminders_enabled", value).apply()
}
